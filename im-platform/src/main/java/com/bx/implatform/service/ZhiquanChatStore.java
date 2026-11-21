package com.bx.implatform.service;

import com.bx.implatform.config.props.ZhiquanChatProperties;
import com.bx.implatform.dto.GroupMessageHistoryDTO;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.GroupVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 共享群聊读 zhiquan_chat。客服和本地私聊不经过这里。
 * 分区是抓取账号的 user#{id}。网页上的登录，包括这个账号自己，都读这一份。
 * 抓取进程带着自己的 User-Agent 时仍读 MySQL，新消息才能继续写进表里。
 */
@Slf4j
@Service
public class ZhiquanChatStore {

    private static final int HISTORY_LIMIT = 100;
    private static final String VIEWERS = "zhiquan:chat:viewers:";
    private static final String RECALLED = "zhiquan:chat:recalled:";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ZhiquanChatProperties properties;
    private final ObjectProvider<DynamoDbClient> clients;
    private final ObjectProvider<LambdaClient> lambdas;
    private final RedisTemplate<String, Object> redisTemplate;
    private final Object cacheLock = new Object();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile Cache cache = Cache.empty(0);

    public ZhiquanChatStore(ZhiquanChatProperties properties, ObjectProvider<DynamoDbClient> clients,
                            RedisTemplate<String, Object> redisTemplate, ObjectProvider<LambdaClient> lambdas) {
        this.properties = properties;
        this.clients = clients;
        this.redisTemplate = redisTemplate;
        this.lambdas = lambdas;
    }

    public boolean enabled() {
        return properties.isEnabled() && properties.getSourceUserId() != null && clients.getIfAvailable() != null;
    }

    public boolean isSourceUser(Long userId) {
        return userId != null && userId.equals(properties.getSourceUserId());
    }

    /**
     * 这次请求要不要把表里的群和消息发给对方。
     * 抓取进程除外，它必须继续看见 MySQL 里的新消息。
     */
    public boolean serveTo(Long userId) {
        if (!enabled()) {
            return false;
        }
        return !(isSourceUser(userId) && scraperRequest());
    }

    private boolean scraperRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return false;
        }
        String agent = servlet.getRequest().getHeader("User-Agent");
        return agent != null && agent.contains("zhiquan-chat-scraper");
    }

    public int offlineLimit() {
        return Math.max(properties.getOfflinePerGroup(), 1);
    }

    public boolean contains(Long groupId) {
        return groupId != null && current().groups.containsKey(groupId);
    }

    public GroupVO group(Long groupId) {
        GroupVO vo = current().groups.get(groupId);
        return vo == null ? null : copy(vo);
    }

    public List<GroupVO> groupsExcept(Set<Long> excludeIds) {
        List<GroupVO> vos = new ArrayList<>();
        for (GroupVO vo : current().groups.values()) {
            if (excludeIds == null || !excludeIds.contains(vo.getId())) {
                vos.add(copy(vo));
            }
        }
        return vos;
    }

    /** 打开过这个房间的人，半小时内能收到新发言的长连接。 */
    public void touchViewer(Long groupId, Long userId) {
        if (groupId == null || userId == null || !contains(groupId)) {
            return;
        }
        try {
            String key = VIEWERS + groupId;
            redisTemplate.opsForSet().add(key, userId.toString());
            redisTemplate.expire(key, 30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("记录聊天室观众失败: {}", e.toString());
        }
    }

    /**
     * 比上次推送更新的消息。afterSortKey 为空时只记下当前最新一条，不回放历史。
     */
    public List<Incoming> incoming(Long groupId, String gid, String afterSortKey, int limit) {
        if (groupId == null || gid == null || gid.isBlank() || !enabled()) {
            return List.of();
        }
        try {
            DynamoDbClient client = clients.getIfAvailable();
            if (client == null) {
                return List.of();
            }
            if (afterSortKey == null || afterSortKey.isBlank()) {
                String tip = newestSortKey(client, gid);
                return tip == null ? List.of() : List.of(Incoming.mark(tip));
            }
            int size = Math.max(limit, 1);
            var response = client.query(QueryRequest.builder()
                .tableName(properties.getTable())
                .keyConditionExpression("pk = :pk AND sk BETWEEN :start AND :end")
                .expressionAttributeValues(Map.of(
                    ":pk", AttributeValue.fromS(partition()),
                    ":start", AttributeValue.fromS(afterSortKey + "\u0000"),
                    ":end", AttributeValue.fromS("group#" + gid + "#msg#~")))
                .scanIndexForward(true)
                .limit(size)
                .build());
            List<Incoming> rows = new ArrayList<>();
            for (Map<String, AttributeValue> item : response.items()) {
                String sk = text(item.get("sk"));
                GroupMessageVO vo = toMessage(item);
                if (vo == null) {
                    if (!sk.isBlank()) {
                        rows.add(Incoming.mark(sk));
                    }
                    continue;
                }
                rows.add(new Incoming(sk, vo, "1".equals(text(item.get("liveDelivered")))));
            }
            return rows;
        } catch (Exception e) {
            log.warn("读取新聊天失败 group={}: {}", groupId, e.toString());
            return List.of();
        }
    }

    public void keepViewers(Long groupId) {
        if (groupId == null) {
            return;
        }
        try {
            redisTemplate.expire(VIEWERS + groupId, 30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("续上聊天室观众失败: {}", e.toString());
        }
    }

    public Map<Long, String> groupKeys() {
        Map<Long, String> keys = new LinkedHashMap<>();
        Cache current = current();
        for (Map.Entry<Long, String> entry : current.gidKeys().entrySet()) {
            keys.put(entry.getKey(), entry.getValue());
        }
        return keys;
    }

    public int cachedCount(Long groupId) {
        List<GroupMessageVO> page = current().latest.get(groupId);
        return page == null ? 0 : page.size();
    }

    public List<Long> viewers(Long groupId) {
        if (groupId == null) {
            return List.of();
        }
        try {
            Set<Object> ids = redisTemplate.opsForSet().members(VIEWERS + groupId);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<Long> userIds = new ArrayList<>();
            for (Object id : ids) {
                if (id == null) {
                    continue;
                }
                try {
                    userIds.add(Long.parseLong(id.toString()));
                } catch (NumberFormatException ignored) {
                    // 脏数据跳过
                }
            }
            return userIds;
        } catch (Exception e) {
            log.warn("读取聊天室观众失败: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 把刚发出的消息写进 zhiquan_chat，其他登录用户不用等下一轮抓取。
     */
    private boolean recalled(Long groupId, Long messageId) {
        if (groupId == null || messageId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(RECALLED + groupId, messageId.toString()));
        } catch (Exception e) {
            return false;
        }
    }

    public void markRecalled(Long groupId, Long messageId) {
        if (groupId == null || messageId == null) {
            return;
        }
        try {
            String key = RECALLED + groupId;
            redisTemplate.opsForSet().add(key, messageId.toString());
            redisTemplate.expire(key, 60, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("记下撤回失败: {}", e.toString());
        }
    }

    public void remember(GroupMessageVO vo) {
        if (!enabled() || vo == null || vo.getId() == null || vo.getSeqNo() == null || vo.getGroupId() == null) {
            return;
        }
        try {
            DynamoDbClient client = clients.getIfAvailable();
            if (client == null) {
                return;
            }
            String content = vo.getContent() == null ? "" : vo.getContent();
            if (content.length() > 3500) {
                content = content.substring(0, 3500);
            }
            Map<String, AttributeValue> item = new HashMap<>();
            String pk = partition();
            String gid = current().gidKeys().getOrDefault(vo.getGroupId(), String.valueOf(vo.getGroupId()));
            long sendTime = vo.getSendTime() == null ? System.currentTimeMillis() : vo.getSendTime().getTime();
            String nick = vo.getSendNickName() == null ? "" : vo.getSendNickName();
            int type = vo.getType() == null ? 0 : vo.getType();
            String sk = messageSk(gid, sendTime, vo.getLocalId());
            item.put("pk", AttributeValue.fromS(pk));
            item.put("sk", AttributeValue.fromS(sk));
            item.put("gid", AttributeValue.fromS(gid));
            item.put("kind", AttributeValue.fromS("group_msg"));
            item.put("entity", AttributeValue.fromS("msg"));
            item.put("groupId", AttributeValue.fromN(vo.getGroupId().toString()));
            item.put("msgId", AttributeValue.fromN(vo.getId().toString()));
            item.put("seqNo", AttributeValue.fromN(vo.getSeqNo().toString()));
            if (vo.getSendId() != null) {
                item.put("sendId", AttributeValue.fromN(vo.getSendId().toString()));
                item.put("sendUid", AttributeValue.fromS(vo.getSendId().toString()));
            }
            item.put("sendNickName", AttributeValue.fromS(nick));
            item.put("nickName", AttributeValue.fromS(nick));
            item.put("type", AttributeValue.fromN(String.valueOf(type)));
            item.put("msgType", AttributeValue.fromS(String.valueOf(type)));
            item.put("content", AttributeValue.fromS(content));
            item.put("sendTime", AttributeValue.fromN(Long.toString(sendTime)));
            item.put("time", AttributeValue.fromN(Long.toString(sendTime)));
            item.put("status", AttributeValue.fromN(String.valueOf(vo.getStatus() == null ? 0 : vo.getStatus())));
            item.put("updatedAt", AttributeValue.fromN(Long.toString(System.currentTimeMillis() / 1000)));
            if (vo.getLocalId() != null && !vo.getLocalId().isBlank()) {
                item.put("localId", AttributeValue.fromS(vo.getLocalId()));
            }
            // 这条已经当场广播过。补推再送一次，发送者的本地记录就会变成两条。
            item.put("liveDelivered", AttributeValue.fromS("1"));
            client.putItem(PutItemRequest.builder().tableName(properties.getTable()).item(item).build());
            appendLatest(vo);
            String sendUid = vo.getSendId() == null ? "" : vo.getSendId().toString();
            notifyLive(gid, sk, sendTime, String.valueOf(type), nick, sendUid, content);
        } catch (Exception e) {
            log.warn("写入 zhiquan_chat 发言失败: {}", e.toString());
        }
    }

    /**
     * 撤回只改网关里原来那一条，标成原站的 91。不再另写一条 type 10，10 在网关是红包。
     */
    public void recallOnGateway(Long groupId, Date sendTime, String localId, String recallJson, String nick, String sendUid) {
        if (!enabled() || groupId == null || recallJson == null || recallJson.isBlank()) {
            return;
        }
        try {
            DynamoDbClient client = clients.getIfAvailable();
            if (client == null) {
                return;
            }
            long stamp = sendTime == null ? System.currentTimeMillis() : sendTime.getTime();
            String gid = current().gidKeys().getOrDefault(groupId, String.valueOf(groupId));
            String sk = locateMessageSk(client, gid, stamp, localId);
            if (sk == null && !gid.equals(String.valueOf(groupId))) {
                sk = locateMessageSk(client, String.valueOf(groupId), stamp, localId);
            }
            if (sk == null) {
                log.warn("网关里没有可撤回的消息 group={} localId={}", groupId, localId);
                return;
            }
            client.updateItem(UpdateItemRequest.builder()
                .tableName(properties.getTable())
                .key(key(partition(), sk))
                .updateExpression("SET recalled = :recalled, recall = :recall, content = :recall, msgType = :msgType, #ty = :typeNum, "
                    + "originContent = if_not_exists(originContent, content), originType = if_not_exists(originType, msgType)")
                .expressionAttributeNames(Map.of("#ty", "type"))
                .expressionAttributeValues(Map.of(
                    ":recalled", AttributeValue.fromS("1"),
                    ":recall", AttributeValue.fromS(recallJson),
                    ":msgType", AttributeValue.fromS("91"),
                    ":typeNum", AttributeValue.fromN("91")))
                .build());
            notifyLive(gid, sk, stamp, "91", nick == null ? "" : nick, sendUid == null ? "" : sendUid, recallJson);
        } catch (Exception e) {
            log.warn("撤回网关消息失败: {}", e.toString());
        }
    }

    public GroupMember readerMember(Long groupId) {
        GroupVO vo = current().groups.get(groupId);
        if (vo == null) {
            return null;
        }
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setQuit(false);
        member.setIsDnd(Boolean.TRUE.equals(vo.getIsDnd()));
        member.setIsTop(false);
        member.setIsMuted(false);
        member.setIsManager(false);
        member.setIsTopMessage(false);
        member.setRemarkGroupName(vo.getRemarkGroupName());
        member.setVersion(0L);
        member.setCreatedTime(new Date(0));
        return member;
    }

    /**
     * 登录拉离线消息。已经从 MySQL 覆盖的群不再重复给。
     * 只有每个群最新的一条保持未读，避免把整段历史算进未读。
     */
    public List<GroupMessageVO> offlineExcept(Set<Long> coveredGroupIds) {
        List<GroupMessageVO> vos = new ArrayList<>();
        for (Map.Entry<Long, List<GroupMessageVO>> entry : current().latest.entrySet()) {
            if (coveredGroupIds != null && coveredGroupIds.contains(entry.getKey())) {
                continue;
            }
            List<GroupMessageVO> page = entry.getValue();
            Long newestId = page.isEmpty() ? null : page.get(page.size() - 1).getId();
            for (GroupMessageVO src : page) {
                GroupMessageVO vo = copy(src);
                if (!MessageStatus.RECALL.code().equals(vo.getStatus())) {
                    vo.setStatus(vo.getId().equals(newestId)
                        ? MessageStatus.PENDING.code() : MessageStatus.READED.code());
                }
                vos.add(vo);
            }
        }
        return vos;
    }

    public List<GroupMessageVO> history(GroupMessageHistoryDTO dto) {
        if (dto == null || dto.getGroupId() == null || !contains(dto.getGroupId())) {
            return List.of();
        }
        List<GroupMessageVO> matched;
        if (!CollectionUtils.isEmpty(dto.getLocalIds())) {
            matched = filterLocalIds(dto.getGroupId(), dto.getLocalIds());
        } else if (!CollectionUtils.isEmpty(dto.getSeqNos())) {
            matched = bySeqNos(dto.getGroupId(), dto.getSeqNos());
        } else if (dto.getMinSeqNo() != null && dto.getMaxSeqNo() != null) {
            matched = bySeqRange(dto.getGroupId(), dto.getMinSeqNo(), dto.getMaxSeqNo());
        } else {
            matched = bySeqRange(dto.getGroupId(), 0L, Long.MAX_VALUE);
        }
        List<GroupMessageVO> vos = new ArrayList<>(Math.min(matched.size(), HISTORY_LIMIT));
        for (int i = 0; i < matched.size() && i < HISTORY_LIMIT; i++) {
            GroupMessageVO vo = copy(matched.get(i));
            if (recalled(dto.getGroupId(), vo.getId())) {
                vo.setStatus(MessageStatus.RECALL.code());
            } else if (!MessageStatus.RECALL.code().equals(vo.getStatus())) {
                vo.setStatus(MessageStatus.READED.code());
            }
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 缓存还在有效期就直接用。过期后读请求仍拿上一份，刷新在后台做。
     * 刷新要查 DynamoDB，不能占着锁，否则群列表、历史和发消息会一起等十几秒。
     */
    private Cache current() {
        if (!enabled()) {
            return Cache.empty(0);
        }
        Cache snapshot = this.cache;
        long now = System.currentTimeMillis();
        if (now < snapshot.expireAt) {
            return snapshot;
        }
        if (snapshot.loaded) {
            startRefresh();
            return snapshot;
        }
        return loadBlocking();
    }

    private void startRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                replaceCache(load());
            } catch (Exception e) {
                log.warn("读取 zhiquan_chat 失败: {}", e.toString());
                holdCache();
            } finally {
                synchronized (cacheLock) {
                    refreshing.set(false);
                    cacheLock.notifyAll();
                }
            }
        }, "zhiquan-chat-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /** 还没有缓存时，这次请求等刷新完成。同时来的请求只等，不再各查一遍。 */
    private Cache loadBlocking() {
        synchronized (cacheLock) {
            if (!refreshing.compareAndSet(false, true)) {
                while (refreshing.get() && !this.cache.loaded) {
                    try {
                        cacheLock.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return this.cache;
                    }
                }
                return this.cache;
            }
        }
        try {
            replaceCache(load());
            return this.cache;
        } catch (Exception e) {
            log.warn("读取 zhiquan_chat 失败: {}", e.toString());
            holdCache();
            return this.cache;
        } finally {
            synchronized (cacheLock) {
                refreshing.set(false);
                cacheLock.notifyAll();
            }
        }
    }

    private void holdCache() {
        long hold = System.currentTimeMillis() + Math.max(properties.getCacheSeconds(), 5) * 1000L;
        synchronized (cacheLock) {
            Cache current = this.cache;
            this.cache = current.loaded ? current.hold(hold) : Cache.empty(hold);
        }
    }

    private void replaceCache(Cache loaded) {
        synchronized (cacheLock) {
            Cache previous = this.cache;
            this.cache = previous.loaded ? mergeFresh(loaded, previous) : loaded;
        }
    }

    private Cache mergeFresh(Cache loaded, Cache previous) {
        int limit = Math.max(properties.getOfflinePerGroup(), 1);
        Map<Long, List<GroupMessageVO>> latest = new LinkedHashMap<>(loaded.latest);
        for (Map.Entry<Long, List<GroupMessageVO>> entry : previous.latest.entrySet()) {
            List<GroupMessageVO> page = mergePage(latest.getOrDefault(entry.getKey(), List.of()), entry.getValue(), limit);
            if (!page.isEmpty()) {
                latest.put(entry.getKey(), page);
            }
        }
        return new Cache(loaded.loaded, loaded.expireAt, loaded.groups, latest, loaded.gidKeys);
    }

    /** 刷新期间刚写进缓存、但查询还没读到的发言留在页尾。更早的不补回来。 */
    static List<GroupMessageVO> mergePage(List<GroupMessageVO> loaded, List<GroupMessageVO> previous, int limit) {
        if (loaded == null || loaded.isEmpty()) {
            return List.of();
        }
        List<GroupMessageVO> page = new ArrayList<>(loaded);
        Set<Long> ids = new HashSet<>();
        long newest = 0L;
        for (GroupMessageVO vo : page) {
            if (vo.getId() != null) {
                ids.add(vo.getId());
            }
            long time = vo.getSendTime() == null ? 0L : vo.getSendTime().getTime();
            if (time > newest) {
                newest = time;
            }
        }
        for (GroupMessageVO vo : previous) {
            if (vo.getId() == null || !ids.add(vo.getId())) {
                continue;
            }
            long time = vo.getSendTime() == null ? 0L : vo.getSendTime().getTime();
            if (time >= newest) {
                page.add(vo);
            }
        }
        if (page.size() > limit) {
            page = new ArrayList<>(page.subList(page.size() - limit, page.size()));
        }
        return List.copyOf(page);
    }

    private Cache load() {
        DynamoDbClient client = clients.getIfAvailable();
        String pk = partition();
        int limit = offlineLimit();
        Map<Long, GroupVO> groups = new LinkedHashMap<>();
        Map<Long, String> gidKeys = new LinkedHashMap<>();
        for (Map<String, AttributeValue> item : queryGroups(client, pk)) {
            GroupVO vo = toGroup(item);
            if (vo == null) {
                continue;
            }
            groups.put(vo.getId(), vo);
            gidKeys.put(vo.getId(), gidKey(item, vo.getId()));
        }
        Map<Long, List<GroupMessageVO>> latest = new LinkedHashMap<>();
        for (Map.Entry<Long, String> entry : gidKeys.entrySet()) {
            latest.put(entry.getKey(), latestMessages(client, pk, entry.getValue(), limit));
        }
        int messages = latest.values().stream().mapToInt(List::size).sum();
        log.info("zhiquan_chat 已刷新 partition={} groups={} perGroup={} offlineMessages={}",
            pk, groups.size(), limit, messages);
        long expireAt = System.currentTimeMillis() + Math.max(properties.getCacheSeconds(), 1) * 1000L;
        return new Cache(true, expireAt, groups, latest, Map.copyOf(gidKeys));
    }

    private String partition() {
        String partition = properties.getPartition();
        if (partition != null && !partition.isBlank()) {
            return partition.trim();
        }
        return "user#" + properties.getSourceUserId();
    }

    /**
     * 群资料的排序键是 group#{gid}，消息是 group#{gid}#msg#...。
     * 按 group# 前缀整段读会把全部分组消息都扫出来。读到一个群后从 group#{gid}#~ 继续，跳过这个群的消息。
     */
    private List<Map<String, AttributeValue>> queryGroups(DynamoDbClient client, String pk) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        String previousGid = "";
        for (int step = 0; step < 1000; step++) {
            Map<String, AttributeValue> values = new HashMap<>();
            values.put(":pk", AttributeValue.fromS(pk));
            values.put(":prefix", AttributeValue.fromS("group#"));
            var builder = QueryRequest.builder()
                .tableName(properties.getTable())
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(values)
                .limit(1);
            if (start != null) {
                builder.exclusiveStartKey(start);
            }
            var response = client.query(builder.build());
            if (response.items().isEmpty()) {
                break;
            }
            Map<String, AttributeValue> item = response.items().get(0);
            String sk = text(item.get("sk"));
            String gid = groupIdFromSk(sk);
            String resume = afterGroup(sk);
            if (gid.isEmpty() || resume.isEmpty() || gid.equals(previousGid)) {
                break;
            }
            previousGid = gid;
            if ("group".equals(text(item.get("kind"))) || sk.equals("group#" + gid)) {
                items.add(item);
            }
            start = key(pk, resume);
        }
        return items;
    }

    /** 从这个排序键所在的群后面继续查，消息键 group#{gid}#msg#... 会落在它前面。 */
    static String afterGroup(String sk) {
        String gid = groupIdFromSk(sk);
        return gid.isEmpty() ? "" : "group#" + gid + "#~";
    }

    static String groupIdFromSk(String sk) {
        if (sk == null || !sk.startsWith("group#")) {
            return "";
        }
        String rest = sk.substring("group#".length());
        int cut = rest.indexOf('#');
        String gid = cut < 0 ? rest : rest.substring(0, cut);
        return gid.isBlank() ? "" : gid;
    }

    private List<Map<String, AttributeValue>> batchGroups(DynamoDbClient client, String pk, List<Long> groupIds) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (int from = 0; from < groupIds.size(); from += 100) {
            List<Long> slice = groupIds.subList(from, Math.min(from + 100, groupIds.size()));
            Map<String, KeysAndAttributes> request = Map.of(properties.getTable(), KeysAndAttributes.builder()
                .keys(slice.stream().map(id -> key(pk, "group#" + id)).toList())
                .build());
            Map<String, KeysAndAttributes> pending = request;
            for (int attempt = 0; attempt < 3 && pending != null && !pending.isEmpty(); attempt++) {
                var response = client.batchGetItem(BatchGetItemRequest.builder().requestItems(pending).build());
                items.addAll(response.responses().getOrDefault(properties.getTable(), List.of()));
                pending = response.unprocessedKeys();
            }
        }
        return items;
    }

    private String newestSortKey(DynamoDbClient client, String gid) {
        var response = client.query(QueryRequest.builder()
            .tableName(properties.getTable())
            .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(partition()),
                ":prefix", AttributeValue.fromS("group#" + gid + "#msg#")))
            .scanIndexForward(false)
            .limit(1)
            .build());
        if (response.items().isEmpty()) {
            return null;
        }
        return text(response.items().get(0).get("sk"));
    }

    private List<GroupMessageVO> latestMessages(DynamoDbClient client, String pk, String gid, int limit) {
        var response = client.query(QueryRequest.builder()
            .tableName(properties.getTable())
            .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(pk),
                ":prefix", AttributeValue.fromS("group#" + gid + "#msg#")))
            .scanIndexForward(false)
            .limit(limit)
            .build());
        List<GroupMessageVO> vos = new ArrayList<>();
        for (int i = response.items().size() - 1; i >= 0; i--) {
            GroupMessageVO vo = toMessage(response.items().get(i));
            if (vo != null) {
                vos.add(vo);
            }
        }
        for (int i = 0; i < vos.size(); i++) {
            vos.get(i).setSeqNo((long) i + 1);
        }
        return vos;
    }

    private List<GroupMessageVO> bySeqRange(Long groupId, long minSeq, long maxSeq) {
        if (minSeq > maxSeq) {
            return List.of();
        }
        List<GroupMessageVO> page = current().latest.getOrDefault(groupId, List.of());
        List<GroupMessageVO> vos = new ArrayList<>();
        for (GroupMessageVO vo : page) {
            if (vo.getSeqNo() == null || vo.getSeqNo() < minSeq || vo.getSeqNo() > maxSeq) {
                continue;
            }
            vos.add(copy(vo));
            if (vos.size() >= HISTORY_LIMIT) {
                break;
            }
        }
        return vos;
    }

    private List<GroupMessageVO> bySeqNos(Long groupId, List<Long> seqNos) {
        Set<Long> wanted = new HashSet<>();
        for (Long seq : seqNos) {
            if (seq != null) {
                wanted.add(seq);
            }
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<GroupMessageVO> vos = new ArrayList<>();
        for (GroupMessageVO vo : current().latest.getOrDefault(groupId, List.of())) {
            if (vo.getSeqNo() != null && wanted.contains(vo.getSeqNo())) {
                vos.add(copy(vo));
            }
        }
        vos.sort((a, b) -> Long.compare(a.getSeqNo(), b.getSeqNo()));
        return vos;
    }

    private List<GroupMessageVO> filterLocalIds(Long groupId, List<String> localIds) {
        List<GroupMessageVO> latest = current().latest.getOrDefault(groupId, List.of());
        List<GroupMessageVO> vos = new ArrayList<>();
        for (GroupMessageVO vo : latest) {
            if (vo.getLocalId() != null && localIds.contains(vo.getLocalId())) {
                vos.add(vo);
            }
        }
        return vos;
    }

    static GroupVO toGroup(Map<String, AttributeValue> item) {
        Long id = number(item.get("groupId"));
        if (id == null) {
            id = parseLong(text(item.get("gid")));
        }
        if (id == null) {
            return null;
        }
        GroupVO vo = new GroupVO();
        vo.setId(id);
        String name = text(item.get("name"));
        if (name.isEmpty()) {
            name = "群聊";
        }
        vo.setName(name);
        String remark = text(item.get("remarkGroupName"));
        vo.setRemarkGroupName(remark.isEmpty() ? null : remark);
        vo.setShowGroupName(remark.isEmpty() ? name : remark);
        vo.setOwnerId(number(item.get("ownerId")));
        if (vo.getOwnerId() == null) {
            Map<String, AttributeValue> rawOwner = mapOf(item.get("raw"));
            vo.setOwnerId(parseLong(firstText(
                text(rawOwner.get("g_owner_user_uid")),
                text(item.get("g_owner_user_uid")))));
        }
        String head = text(item.get("headImage"));
        vo.setHeadImage(head);
        vo.setHeadImageThumb(head);
        vo.setIsDnd(bool(item.get("isDnd")));
        vo.setQuit(false);
        vo.setDissolve(false);
        vo.setIsMuted(false);
        vo.setIsTop(false);
        vo.setIsAllMuted(false);
        vo.setIsBanned(false);
        vo.setIsAllowInvite(false);
        vo.setIsAllowShareCard(false);
        vo.setVersion(0L);
        applyNotice(vo, item);
        return vo;
    }

    /**
     * 原站群公告在抓取项的 g_notice。enabled=0 时不展示。
     */
    static void applyNotice(GroupVO vo, Map<String, AttributeValue> item) {
        Map<String, AttributeValue> raw = mapOf(item.get("raw"));
        String notice = firstText(
            text(item.get("notice")),
            text(item.get("g_notice")),
            text(raw.get("g_notice")),
            text(raw.get("notice")));
        if (notice.isEmpty()) {
            return;
        }
        String config = firstText(text(item.get("g_notice_show_config")), text(raw.get("g_notice_show_config")));
        if (jsonInt(config, "enabled", 1) == 0) {
            return;
        }
        if (notice.length() > 1024) {
            notice = notice.substring(0, 1024);
        }
        vo.setNotice(notice);
        vo.setNoticeAllowClose(jsonInt(config, "allowClose", 1) != 0);
        vo.setNoticeReshowHours(Math.max(jsonInt(config, "closeReshowHours", 24), 0));
    }

    private static Map<String, AttributeValue> mapOf(AttributeValue value) {
        return value != null && value.hasM() ? value.m() : Map.of();
    }

    private static String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static int jsonInt(String json, String key, int fallback) {
        if (json == null || json.isBlank() || key == null) {
            return fallback;
        }
        int mark = json.indexOf("\"" + key + "\"");
        if (mark < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', mark + key.length() + 2);
        if (colon < 0) {
            return fallback;
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        if (end < json.length() && json.charAt(end) == '-') {
            end++;
        }
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start || (end == start + 1 && json.charAt(start) == '-')) {
            return fallback;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static boolean isClaimNotice(String content) {
        if (content == null) {
            return false;
        }
        String text = content.trim();
        if ("领取了红包".equals(text) || "已领取了红包".equals(text)) {
            return true;
        }
        // 「等12/200人已领取了红包」是原站的人数提示，保留。点名某人领了的不展示。
        return text.endsWith("已领取了红包") && !text.contains("人已领取");
    }

    static GroupMessageVO toMessage(Map<String, AttributeValue> item) {
        Long groupId = number(item.get("groupId"));
        if (groupId == null) {
            groupId = parseLong(text(item.get("gid")));
        }
        Long sendTime = number(item.get("sendTime"));
        if (sendTime == null) {
            sendTime = number(item.get("time"));
        }
        if (sendTime != null && sendTime > 0 && sendTime < 10_000_000_000L) {
            sendTime = sendTime * 1000;
        }
        Long id = number(item.get("msgId"));
        Long seqNo = number(item.get("seqNo"));
        if (id == null) {
            id = sendTime;
        }
        if (seqNo == null) {
            seqNo = sendTime;
        }
        if (groupId == null || id == null || seqNo == null) {
            return null;
        }
        GroupMessageVO vo = new GroupMessageVO();
        vo.setId(id);
        vo.setGroupId(groupId);
        vo.setSeqNo(seqNo);
        Long sendId = number(item.get("sendId"));
        if (sendId == null) {
            sendId = parseLong(text(item.get("sendUid")));
        }
        vo.setSendId(sendId);
        String nick = text(item.get("sendNickName"));
        if (nick.isEmpty()) {
            nick = text(item.get("nickName"));
        }
        vo.setSendNickName(nick);
        vo.setType(messageType(item));
        vo.setContent(text(item.get("content")));
        String origin = text(item.get("originContent"));
        if (!origin.isEmpty()) {
            vo.setContent(origin);
            Integer originType = plainType(item.get("originType"));
            if (originType != null) {
                vo.setType(originType);
            }
        }
        vo.setSendTime(new Date(sendTime == null ? 0L : sendTime));
        Long status = number(item.get("status"));
        vo.setStatus(status == null ? MessageStatus.READED.code() : status.intValue());
        String localId = text(item.get("localId"));
        if (localId.isEmpty()) {
            localId = text(item.get("fp"));
        }
        vo.setLocalId(localId.isEmpty() ? "ddb-" + groupId + "-" + seqNo : localId);
        vo.setReceipt(false);
        vo.setReceiptOk(false);
        vo.setReadedCount(0);
        vo.setAtUserIds(List.of());
        vo.setDeleted(false);
        if (isClaimNotice(vo.getContent())) {
            return null;
        }
        return vo;
    }

    static List<Long> groupIds(Map<String, AttributeValue> meta) {
        if (meta == null || meta.isEmpty()) {
            return List.of();
        }
        AttributeValue value = meta.get("groupIds");
        if (value == null || !value.hasL()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (AttributeValue entry : value.l()) {
            Long id = number(entry);
            if (id == null && entry.s() != null && !entry.s().isBlank()) {
                try {
                    id = Long.parseLong(entry.s().trim());
                } catch (NumberFormatException ignored) {
                    id = null;
                }
            }
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void appendLatest(GroupMessageVO src) {
        synchronized (cacheLock) {
            Cache current = this.cache;
            if (!current.loaded) {
                return;
            }
            List<GroupMessageVO> page = new ArrayList<>(current.latest.getOrDefault(src.getGroupId(), List.of()));
            page.removeIf(message -> src.getId().equals(message.getId()));
            page.add(copy(src));
            int limit = Math.max(properties.getOfflinePerGroup(), 1);
            if (page.size() > limit) {
                page = new ArrayList<>(page.subList(page.size() - limit, page.size()));
            }
            Map<Long, List<GroupMessageVO>> latest = new LinkedHashMap<>(current.latest);
            latest.put(src.getGroupId(), List.copyOf(page));
            this.cache = new Cache(true, current.expireAt, current.groups, latest, current.gidKeys());
        }
    }

    static String messageSk(String gid, long sendTime, String localId) {
        String sk = "group#" + gid + "#msg#" + String.format("%016d", sendTime);
        String tail = localTail(localId);
        return tail.isEmpty() ? sk : sk + "#" + tail;
    }

    /** 排序键末段保留完整 localId，只留字母和数字，最长 64。 */
    static String localTail(String localId) {
        if (localId == null || localId.isBlank()) {
            return "";
        }
        String tail = localId.replaceAll("[^A-Za-z0-9]", "");
        if (tail.length() > 64) {
            tail = tail.substring(tail.length() - 64);
        }
        return tail;
    }

    /** 旧数据只留了后 16 位，撤回时还要能找到。 */
    static String legacyLocalTail(String localId) {
        if (localId == null || localId.isBlank()) {
            return "";
        }
        String tail = localId.replaceAll("[^A-Za-z0-9]", "");
        if (tail.length() > 16) {
            tail = tail.substring(tail.length() - 16);
        }
        return tail;
    }

    private String locateMessageSk(DynamoDbClient client, String gid, long sendTime, String localId) {
        String current = messageSk(gid, sendTime, localId);
        if (itemExists(client, current)) {
            return current;
        }
        String oldTail = legacyLocalTail(localId);
        if (oldTail.isEmpty() || oldTail.equals(localTail(localId))) {
            return null;
        }
        String legacy = "group#" + gid + "#msg#" + String.format("%016d", sendTime) + "#" + oldTail;
        return itemExists(client, legacy) ? legacy : null;
    }

    private boolean itemExists(DynamoDbClient client, String sk) {
        var response = client.getItem(GetItemRequest.builder()
            .tableName(properties.getTable())
            .key(key(partition(), sk))
            .projectionExpression("pk")
            .build());
        return response.hasItem() && !response.item().isEmpty();
    }

    private void notifyLive(String gid, String sk, long sendTime, String type, String nick, String sendUid, String content) {
        String function = properties.getLiveFunction();
        if (function == null || function.isBlank() || lambdas == null) {
            return;
        }
        LambdaClient lambda = lambdas.getIfAvailable();
        if (lambda == null) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "group_msg");
        row.put("gid", gid);
        row.put("sk", sk);
        row.put("time", sendTime);
        row.put("msgType", type);
        row.put("nickName", nick);
        row.put("sendUid", sendUid);
        row.put("content", content);
        try {
            String payload = JSON.writeValueAsString(Map.of("items", List.of(row)));
            lambda.invoke(InvokeRequest.builder()
                .functionName(function)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String(payload))
                .build());
        } catch (JsonProcessingException e) {
            log.warn("组装网关推送失败: {}", e.toString());
        } catch (Exception e) {
            log.warn("推送聊天到网关失败: {}", e.toString());
        }
    }

    private static String gidKey(Map<String, AttributeValue> item, Long id) {
        String gid = text(item.get("gid"));
        if (!gid.isEmpty()) {
            return gid;
        }
        String sk = text(item.get("sk"));
        if (sk.startsWith("group#")) {
            String rest = sk.substring("group#".length());
            int cut = rest.indexOf('#');
            return cut < 0 ? rest : rest.substring(0, cut);
        }
        return String.valueOf(id);
    }

    /** 撤回前的消息类型。只认 0 到 9，避免把网关的 91 当成正文类型。 */
    private static Integer plainType(AttributeValue value) {
        Long type = number(value);
        if (type == null) {
            type = parseLong(text(value));
        }
        if (type == null || type < 0 || type >= 10) {
            return null;
        }
        return type.intValue();
    }

    private static int messageType(Map<String, AttributeValue> item) {
        Long type = number(item.get("type"));
        if (type == null) {
            type = number(item.get("msgType"));
        }
        if (type == null) {
            type = parseLong(text(item.get("msgType")));
        }
        // 10 在这个客户端是撤回，正文必须是 JSON。抓取侧的 10 不是撤回，解析失败会把登录踢掉。
        if (type == null || type < 0 || type >= 10) {
            return 0;
        }
        return type.intValue();
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk));
    }

    private static Long number(AttributeValue value) {
        if (value == null) {
            return null;
        }
        if (value.n() != null) {
            try {
                return Long.parseLong(value.n().split("\\.")[0]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(AttributeValue value) {
        return value == null || value.s() == null ? "" : value.s();
    }

    private static boolean bool(AttributeValue value) {
        return value != null && Boolean.TRUE.equals(value.bool());
    }

    private static GroupVO copy(GroupVO src) {
        GroupVO vo = new GroupVO();
        vo.setId(src.getId());
        vo.setName(src.getName());
        vo.setOwnerId(src.getOwnerId());
        vo.setHeadImage(src.getHeadImage());
        vo.setHeadImageThumb(src.getHeadImageThumb());
        vo.setShowGroupName(src.getShowGroupName());
        vo.setRemarkGroupName(src.getRemarkGroupName());
        vo.setIsDnd(src.getIsDnd());
        vo.setQuit(src.getQuit());
        vo.setDissolve(src.getDissolve());
        vo.setIsMuted(src.getIsMuted());
        vo.setIsTop(src.getIsTop());
        vo.setIsAllMuted(src.getIsAllMuted());
        vo.setIsBanned(src.getIsBanned());
        vo.setIsAllowInvite(src.getIsAllowInvite());
        vo.setIsAllowShareCard(src.getIsAllowShareCard());
        vo.setVersion(src.getVersion());
        vo.setNotice(src.getNotice());
        vo.setNoticeAllowClose(src.getNoticeAllowClose());
        vo.setNoticeReshowHours(src.getNoticeReshowHours());
        return vo;
    }

    private static GroupMessageVO copy(GroupMessageVO src) {
        GroupMessageVO vo = new GroupMessageVO();
        vo.setId(src.getId());
        vo.setLocalId(src.getLocalId());
        vo.setSeqNo(src.getSeqNo());
        vo.setGroupId(src.getGroupId());
        vo.setSendId(src.getSendId());
        vo.setSendNickName(src.getSendNickName());
        vo.setContent(src.getContent());
        vo.setType(src.getType());
        vo.setReceipt(src.getReceipt());
        vo.setReceiptOk(src.getReceiptOk());
        vo.setReadedCount(src.getReadedCount());
        vo.setAtUserIds(src.getAtUserIds());
        vo.setStatus(src.getStatus());
        vo.setSendTime(src.getSendTime());
        vo.setDeleted(src.getDeleted());
        return vo;
    }

    public record Incoming(String sortKey, GroupMessageVO message, boolean liveDelivered) {
        static Incoming mark(String sortKey) {
            return new Incoming(sortKey, null, false);
        }
    }

    private record Cache(boolean loaded, long expireAt, Map<Long, GroupVO> groups,
                          Map<Long, List<GroupMessageVO>> latest, Map<Long, String> gidKeys) {
        static Cache empty(long expireAt) {
            return new Cache(false, expireAt, Map.of(), Map.of(), Map.of());
        }

        Cache hold(long expireAt) {
            return new Cache(loaded, expireAt, groups, latest, gidKeys);
        }
    }
}
