package com.bx.implatform.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.IMSendResult;
import com.bx.implatform.annotation.NotifyCheck;
import com.bx.implatform.config.props.NotifyProperties;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.service.FriendService;
import com.bx.implatform.service.GroupService;
import com.bx.implatform.service.OfflineNotifyService;
import com.bx.implatform.session.OfflineNotifySession;
import com.bx.implatform.thirdparty.UniPushService;
import com.bx.implatform.util.MessageUtils;
import com.bx.implatform.vo.FriendRequestVO;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.PrivateMessageVO;
import com.bx.implatform.vo.SystemMessageVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 推送离线通知 author: blue date: 2024-08-23 version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineNotifyServiceImpl implements OfflineNotifyService {

    private final Cache<Long, String> cidLocalCache =
        Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Cache<Long, String> localeLocalCache =
        Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Cache<Long, Boolean> sessionLocalCache =
        Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Cache<String, Boolean> groupDndLocalCache =
        Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();

    private final UniPushService uniPushService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final NotifyProperties notifyProps;
    private final FriendService friendService;
    private final GroupService groupService;

    @NotifyCheck
    @Override
    public void sendPrivateOfflineNotify(List<IMSendResult<PrivateMessageVO>> results) {
        // 接收用户列表
        Set<Long> recvIds = results.stream().map(r -> r.getReceiver().getId()).collect(Collectors.toSet());
        // Map<接收用户id,cid>
        Map<Long, String> cidMap = getCId(recvIds);
        // Map<接收用户id,locale>
        Map<Long, String> localeMap = getLocale(recvIds);
        // Map<接收用户id,session>
        Map<Long, OfflineNotifySession> sessionMap = findNotifySession(cidMap.keySet());
        results.forEach(ret -> {
            Long sendId = ret.getSender().getId();
            Long recvId = ret.getReceiver().getId();
            String cid = cidMap.get(recvId);
            String locale = localeMap.get(recvId);
            // 没有cid，则不推送
            if (StrUtil.isEmpty(cid)) {
                return;
            }
            // 通知会话信息
            OfflineNotifySession session = sessionMap.get(recvId);
            // 已达到最大数量，则不推送
            if (notifyProps.getMaxSize() > 0 && !Objects.isNull(
                session) && session.getMessageSize() >= notifyProps.getMaxSize()) {
                log.info("用户'{}'已到达推送数量上线,不再推送离线通知", recvId);
                sessionMap.remove(recvId);
                return;
            }
            // 接收方开启免打扰，则不推送
            if (friendService.isDnd(recvId, sendId)) {
                return;
            }
            if (Objects.isNull(session)) {
                session = createEmptySession(recvId);
                sessionMap.put(recvId, session);
            }
            session.getFriendIds().add(sendId);
            session.setMessageSize(session.getMessageSize() + 1);
            // 推送离线通知
            sendNotifyMessage(recvId, cid, locale, session);
        });
        // 保存session
        saveNotifySession(sessionMap);
    }

    @NotifyCheck
    @Override
    public void sendGroupOfflineNotify(List<IMSendResult<GroupMessageVO>> results) {
        // 接收用户列表
        Set<Long> recvIds = results.stream().map(r -> r.getReceiver().getId()).collect(Collectors.toSet());
        // Map<接收用户id,cid>
        Map<Long, String> cidMap = getCId(recvIds);
        // 过滤掉没有cid的用户，减少后续的代码循环
        recvIds = cidMap.keySet();
        // Map<接收用户id,locale>
        Map<Long, String> localeMap = getLocale(recvIds);
        // 获取离线通知session
        Map<Long, OfflineNotifySession> sessionMap = findNotifySession(recvIds);
        results.forEach(ret -> {
            Long groupId = ret.getData().getGroupId();
            Long recvId = ret.getReceiver().getId();
            String cid = cidMap.get(recvId);
            String locale = localeMap.get(recvId);
            // 如果没有cid,则不推送
            if (StrUtil.isEmpty(cid)) {
                return;
            }
            OfflineNotifySession session = sessionMap.get(recvId);
            // 如果已达到最大数量，则不推送
            if (notifyProps.getMaxSize() > 0 && !Objects.isNull(
                session) && session.getMessageSize() >= notifyProps.getMaxSize()) {
                sessionMap.remove(recvId);
                return;
            }
            // 如果接收方开启了免打扰，则不推送
            String key = StrUtil.join("-", recvId, groupId);
            Boolean isDnd = groupDndLocalCache.getIfPresent(key);
            if (Objects.isNull(isDnd)) {
                isDnd = groupService.isDnd(recvId, groupId);
                groupDndLocalCache.put(key, isDnd);
            }
            if (isDnd) {
                return;
            }
            if (Objects.isNull(session)) {
                session = createEmptySession(recvId);
                sessionMap.put(recvId, session);
            }
            session.getGroupIds().add(groupId);
            session.setMessageSize(session.getMessageSize() + 1);
            // 推送离线通知
            sendNotifyMessage(recvId, cid, locale, session);
        });
        // 保存session
        saveNotifySession(sessionMap);
    }

    @NotifyCheck
    @Override
    public void sendSystemOfflineNotify(List<IMSendResult<SystemMessageVO>> results) {
        // 接收用户列表
        Set<Long> recvIds = results.stream().map(r -> r.getReceiver().getId()).collect(Collectors.toSet());
        // Map<用户id,cid>
        Map<Long, String> cidMap = getCId(recvIds);
        // Map<用户id,locale>
        Map<Long, String> localeMap = getLocale(recvIds);
        results.forEach(ret -> {
            SystemMessageVO messageVo = ret.getData();
            Long recvId = ret.getReceiver().getId();
            String cid = cidMap.get(recvId);
            if (StrUtil.isEmpty(cid)) {
                return;
            }
            // 推送
            String locale = localeMap.get(recvId);
            String notifyId = Math.abs(messageVo.hashCode()) + "";
            String title = MessageUtils.getMessage(locale, "notify.systemNotice");
            uniPushService.asyncSend(cid, title, messageVo.getTitle(), "", notifyId, messageVo);
        });
    }

    @NotifyCheck
    @Override
    public void sendFriendRequestNotify(PrivateMessageVO messageInfo) {
        if (messageInfo.getType().equals(MessageType.FRIEND_REQ_APPLY.code())) {
            String key = buildCidKey(messageInfo.getRecvId());
            String cid = (String)redisTemplate.opsForValue().get(key);
            if (StrUtil.isEmpty(cid)) {
                return;
            }
            String locale = getLocale(messageInfo.getRecvId());
            String notifyId = Math.abs(messageInfo.hashCode()) + "";
            FriendRequestVO vo = JSON.parseObject(messageInfo.getContent(), FriendRequestVO.class);
            String body = MessageUtils.getMessage(locale, "notify.friendRequestApply");
            uniPushService.asyncSend(cid, vo.getSendNickName(), body, vo.getSendHeadImage(), notifyId, messageInfo);
        }
    }

    private void sendNotifyMessage(Long recvId, String cid, String locale, OfflineNotifySession session) {
        int linkmanSize = session.getFriendIds().size() + session.getGroupIds().size();
        int messageSize = session.getMessageSize();
        String body = MessageUtils.getMessage(locale, "notify.offlineMessageBody", linkmanSize, messageSize);
        String notifyId = Math.abs(buildSessionKey(recvId).hashCode()) + "";
        uniPushService.asyncSend(cid, notifyProps.getAppName(), body, "", notifyId, null);
    }

    /**
     * 批量获取离线通知session
     */
    private Map<Long, OfflineNotifySession> findNotifySession(Set<Long> userIds) {
        Map<Long, OfflineNotifySession> sessionMap = new HashMap<>();
        // 优先查本地缓存
        Integer maxSize = notifyProps.getMaxSize();
        List<Long> missIds = new ArrayList<>();
        for (Long userId : userIds) {
            if (Boolean.TRUE.equals(sessionLocalCache.getIfPresent(userId))) {
                OfflineNotifySession session = new OfflineNotifySession();
                session.setMessageSize(maxSize);
                sessionMap.put(userId, session);
            } else {
                missIds.add(userId);
            }
        }
        // 再从redis查询
        if (!missIds.isEmpty()) {
            List<String> keys = missIds.stream().map(this::buildSessionKey).collect(Collectors.toList());
            List<Object> sessions = redisTemplate.opsForValue().multiGet(keys);
            int idx = 0;
            for (Long userId : missIds) {
                Object obj = sessions.get(idx++);
                if (!Objects.isNull(obj)) {
                    OfflineNotifySession session = (OfflineNotifySession)obj;
                    sessionMap.put(userId, session);
                    // 到达推送上限的用户加入缓存
                    if (maxSize > 0 && session.getMessageSize() >= maxSize) {
                        log.info("用户'{}'已到达推送数量上线,不再推送离线通知", userId);
                        sessionLocalCache.put(userId, Boolean.TRUE);
                    }
                }
            }
        }
        return sessionMap;
    }

    /**
     * 批量获取用户cid（先读本地缓存，未命中的 userId 再 multiGet Redis）
     */
    private Map<Long, String> getCId(Set<Long> userIds) {
        Map<Long, String> cidMap = new HashMap<>();
        // 优先查本地缓存
        List<Long> missIds = new ArrayList<>();
        Long timeStamp = System.currentTimeMillis();
        for (Long userId : userIds) {
            String cid = cidLocalCache.getIfPresent(userId);
            if (!Objects.isNull(cid)) {
                if (StrUtil.isNotEmpty(cid)) {
                    cidMap.put(userId, cid);
                }
            } else {
                missIds.add(userId);
            }
        }
        log.info("耗时：{}", System.currentTimeMillis() - timeStamp);

        // 再查redis缓存
        if (!missIds.isEmpty()) {
            List<String> keys = missIds.stream().map(this::buildCidKey).collect(Collectors.toList());
            List<Object> cids = redisTemplate.opsForValue().multiGet(keys);
            int idx = 0;
            for (Long userId : missIds) {
                Object val = cids.get(idx++);
                String cid = Objects.isNull(val) ? "" : val.toString();
                cidLocalCache.put(userId, cid);
                if (StrUtil.isNotEmpty(cid)) {
                    cidMap.put(userId, cid);
                }
            }
        }
        return cidMap;
    }

    /**
     * 批量保存session
     */
    private void saveNotifySession(Map<Long, OfflineNotifySession> sessionMap) {
        Map<String, OfflineNotifySession> map = sessionMap.entrySet().stream()
            .collect(Collectors.toMap(entry -> buildSessionKey(entry.getKey()), Map.Entry::getValue));
        redisTemplate.opsForValue().multiSet(map);
    }

    /**
     * 创建一个空会话
     */
    private OfflineNotifySession createEmptySession(Long userId) {
        OfflineNotifySession session = new OfflineNotifySession();
        String key = buildSessionKey(userId);
        redisTemplate.opsForValue().set(key, session, notifyProps.getActiveDays(), TimeUnit.DAYS);
        return session;
    }

    private String buildSessionKey(Long userId) {
        return StrUtil.join(":", RedisKey.IM_NOTIFY_OFFLINE_SESSION, userId);
    }

    private String buildCidKey(Long userId) {
        return StrUtil.join(":", RedisKey.IM_USER_CID, userId);
    }

    private String buildLocaleKey(Long userId) {
        return StrUtil.join(":", RedisKey.IM_USER_LOCALE, userId, IMTerminalType.APP.code());
    }

    /**
     * 批量获取用户 locale（先读本地缓存，未命中的 userId 再 multiGet Redis）
     */
    private Map<Long, String> getLocale(Set<Long> userIds) {
        Map<Long, String> localeMap = new HashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (Long userId : userIds) {
            String cached = localeLocalCache.getIfPresent(userId);
            if (!Objects.isNull(cached)) {
                localeMap.put(userId, cached);
            } else {
                missIds.add(userId);
            }
        }
        if (!missIds.isEmpty()) {
            List<String> keys = missIds.stream().map(this::buildLocaleKey).collect(Collectors.toList());
            List<Object> locales = redisTemplate.opsForValue().multiGet(keys);
            int idx = 0;
            for (Long userId : missIds) {
                Object locale = locales.get(idx++);
                String val = Objects.isNull(locale) ? "" : locale.toString();
                localeLocalCache.put(userId, val);
                localeMap.put(userId, val);
            }
        }
        return localeMap;
    }

    private String getLocale(Long userId) {
        String locale = localeLocalCache.getIfPresent(userId);
        if (Objects.isNull(locale)) {
            String key = buildLocaleKey(userId);
            Object val = redisTemplate.opsForValue().get(key);
            locale = Objects.isNull(val) ? "" : val.toString();
            localeLocalCache.put(userId, locale);
        }
        return locale;
    }
}
