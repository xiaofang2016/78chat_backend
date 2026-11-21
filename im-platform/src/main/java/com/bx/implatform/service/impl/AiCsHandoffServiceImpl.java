package com.bx.implatform.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.config.props.MinioProperties;
import com.bx.implatform.entity.AiCsHumanMessage;
import com.bx.implatform.entity.AiCsSession;
import com.bx.implatform.entity.PrivateMessage;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.AiCsHumanMessageMapper;
import com.bx.implatform.mapper.AiCsSessionMapper;
import com.bx.implatform.mapper.PrivateMessageMapper;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.service.AiCsHandoffService;
import com.bx.implatform.service.AiCsReplyOrchestrator;
import com.bx.implatform.service.BotPrivateMessageService;
import com.bx.implatform.util.BeanUtils;
import com.bx.implatform.util.ConvUtil;
import com.bx.implatform.util.KnowledgeIngestBotVisibility;
import com.bx.implatform.vo.AiCsSessionVO;
import com.bx.implatform.vo.PrivateMessageVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import com.bx.implatform.contant.Constant;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiCsHandoffServiceImpl implements AiCsHandoffService {

    private static final String REASON_KEYWORD = "keyword";
    private static final String REASON_AUTO = "auto_no_answer";
    private static final String REASON_MANUAL = "manual";
    /** 管理员点击「交还 AI」后写入，用于抑制立即再次自动转人工 */
    private static final String REASON_OPERATOR_RELEASE = "operator_release";

    private final AiCsSessionMapper sessionMapper;
    private final AiCsHumanMessageMapper humanMessageMapper;
    private final PrivateMessageMapper privateMessageMapper;
    private final UserMapper userMapper;
    private final BotPrivateMessageService botPrivateMessageService;
    private final AiCustomerServiceProperties aiCsProps;
    private final RedissonClient redissonClient;
    private final AiCsReplyOrchestrator replyOrchestrator;
    private final LuckyBonusApplyCardEnricher luckyBonusApplyCardEnricher;
    private final MinioProperties minioProps;

    public AiCsHandoffServiceImpl(AiCsSessionMapper sessionMapper,
                                  AiCsHumanMessageMapper humanMessageMapper,
                                  PrivateMessageMapper privateMessageMapper,
                                  UserMapper userMapper,
                                  BotPrivateMessageService botPrivateMessageService,
                                  AiCustomerServiceProperties aiCsProps,
                                  RedissonClient redissonClient,
                                  @Lazy AiCsReplyOrchestrator replyOrchestrator,
                                  LuckyBonusApplyCardEnricher luckyBonusApplyCardEnricher,
                                  MinioProperties minioProps) {
        this.sessionMapper = sessionMapper;
        this.humanMessageMapper = humanMessageMapper;
        this.privateMessageMapper = privateMessageMapper;
        this.userMapper = userMapper;
        this.botPrivateMessageService = botPrivateMessageService;
        this.aiCsProps = aiCsProps;
        this.redissonClient = redissonClient;
        this.replyOrchestrator = replyOrchestrator;
        this.luckyBonusApplyCardEnricher = luckyBonusApplyCardEnricher;
        this.minioProps = minioProps;
    }

    @Override
    public boolean isHandoffEnabled() {
        return aiCsProps.getHandoff() != null && aiCsProps.getHandoff().isEnabled();
    }

    @Override
    public boolean isHumanMode(Long botUserId, Long customerUserId) {
        AiCsSession s = findSession(botUserId, customerUserId);
        return s != null && Objects.equals(s.getMode(), AiCsSession.MODE_HUMAN);
    }

    @Override
    public boolean shouldSkipAiReply(Long botUserId, Long customerUserId) {
        if (!isHandoffEnabled()) {
            return false;
        }
        AiCsSession s = findSession(botUserId, customerUserId);
        return isFormalTakeover(s);
    }

    @Override
    public void reportAdminTyping(Long botUserId, Long customerUserId, Long operatorId) {
        requireAdminOperator(operatorId);
        replyOrchestrator.reportAdminTyping(botUserId, customerUserId, operatorId);
    }

    @Override
    public void reportAdminWatching(Long botUserId, Long customerUserId, Long operatorId) {
        requireAdminOperator(operatorId);
        replyOrchestrator.reportAdminWatching(botUserId, customerUserId, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onUserMessageToBot(Long botUserId, Long customerUserId, Long messageId, String content) {
        if (!isAiCsBot(botUserId)) {
            return;
        }
        AiCsSession session = getOrCreateSession(botUserId, customerUserId);
        session.setLastUserMsgId(messageId);
        session.setLastMsgAt(new Date());
        session.setUpdatedTime(new Date());
        sessionMapper.updateById(session);
    }

    @Override
    public List<AiCsSessionVO> listSessions(Long botUserId, String filter, int page, int size) {
        requireAiCsBot(botUserId);
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);

        LambdaQueryWrapper<AiCsSession> q = Wrappers.lambdaQuery();
        q.eq(AiCsSession::getBotUserId, botUserId);
        if ("human".equalsIgnoreCase(filter)) {
            q.eq(AiCsSession::getMode, AiCsSession.MODE_HUMAN);
        } else if ("pending".equalsIgnoreCase(filter)) {
            q.eq(AiCsSession::getMode, AiCsSession.MODE_HUMAN);
            q.isNull(AiCsSession::getOperatorId);
        }
        q.orderByDesc(AiCsSession::getLastMsgAt);
        Page<AiCsSession> result = sessionMapper.selectPage(new Page<>(p, s), q);
        if (result.getRecords().isEmpty()) {
            return List.of();
        }

        Set<Long> customerIds = result.getRecords().stream()
            .map(AiCsSession::getCustomerUserId).collect(Collectors.toSet());
        Set<Long> operatorIds = result.getRecords().stream()
            .map(AiCsSession::getOperatorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> users = loadUsers(customerIds, operatorIds);

        List<AiCsSessionVO> out = new ArrayList<>();
        for (AiCsSession row : result.getRecords()) {
            out.add(toSessionVo(row, users));
        }
        return out;
    }

    @Override
    public List<PrivateMessageVO> listMessages(Long botUserId, Long customerUserId, Long beforeId, int limit) {
        requireAiCsBot(botUserId);
        int lim = Math.min(Math.max(1, limit), 200);
        String convKey = ConvUtil.buildConvKey(customerUserId, botUserId);
        LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(PrivateMessage::getConvKey, convKey);
        wrapper.ne(PrivateMessage::getStatus, MessageStatus.RECALL.code());
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        wrapper.ge(PrivateMessage::getSendTime, minDate);
        if (beforeId != null && beforeId > 0) {
            wrapper.lt(PrivateMessage::getId, beforeId);
        }
        wrapper.orderByDesc(PrivateMessage::getId);
        wrapper.last("limit " + lim);
        List<PrivateMessage> rows = privateMessageMapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Collections.reverse(rows);
        List<PrivateMessageVO> vos = rows.stream()
            .map(m -> BeanUtils.copyProperties(m, PrivateMessageVO.class))
            .collect(Collectors.toList());
        luckyBonusApplyCardEnricher.enrich(customerUserId, vos);
        return vos;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void takeover(Long botUserId, Long customerUserId, Long operatorId) {
        requireAdminOperator(operatorId);
        requireAiCsBot(botUserId);
        RLock lock = sessionLock(botUserId, customerUserId);
        lock.lock(10, TimeUnit.SECONDS);
        try {
            AiCsSession session = getOrCreateSession(botUserId, customerUserId);
            if (session.getOperatorId() != null
                && !Objects.equals(session.getOperatorId(), operatorId)) {
                throw new GlobalException(409, "该客户已由其他客服接管，无法重复接管");
            }
            session.setMode(AiCsSession.MODE_HUMAN);
            session.setOperatorId(operatorId);
            session.setHandoffReason(
                session.getHandoffReason() != null ? session.getHandoffReason() : REASON_MANUAL);
            session.setUpdatedTime(new Date());
            sessionMapper.updateById(session);
            replyOrchestrator.cancelPendingAiReply(botUserId, customerUserId);
            log.info("[ai-cs-handoff] takeover bot={} customer={} operator={}",
                botUserId, customerUserId, operatorId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(Long botUserId, Long customerUserId, Long operatorId) {
        requireAdminOperator(operatorId);
        requireAiCsBot(botUserId);
        RLock lock = sessionLock(botUserId, customerUserId);
        lock.lock(10, TimeUnit.SECONDS);
        try {
            AiCsSession session = requireSession(botUserId, customerUserId);
            if (session.getOperatorId() != null
                && !Objects.equals(session.getOperatorId(), operatorId)) {
                throw new GlobalException(409, "仅当前接管的客服可以交还 AI");
            }
            Date now = new Date();
            sessionMapper.update(null, Wrappers.<AiCsSession>lambdaUpdate()
                .eq(AiCsSession::getId, session.getId())
                .set(AiCsSession::getMode, AiCsSession.MODE_AI)
                .set(AiCsSession::getOperatorId, null)
                .set(AiCsSession::getHandoffReason, null)
                .set(AiCsSession::getUpdatedTime, now));
            replyOrchestrator.cancelPendingAiReply(botUserId, customerUserId);
            log.info("[ai-cs-handoff] release bot={} customer={} operator={}",
                botUserId, customerUserId, operatorId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualHandoff(Long botUserId, Long customerUserId, Long operatorId) {
        requireAdminOperator(operatorId);
        AiCsSession session = findSession(botUserId, customerUserId);
        if (session == null || !Objects.equals(session.getMode(), AiCsSession.MODE_HUMAN)) {
            transferToHuman(botUserId, customerUserId, REASON_MANUAL, null);
        }
        takeover(botUserId, customerUserId, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long replyAsBot(Long botUserId, Long customerUserId, Long operatorId,
                           String content, Integer type, String localId) {
        requireAdminOperator(operatorId);
        requireAiCsBot(botUserId);

        int msgType = type == null ? MessageType.TEXT.code() : type;
        String normalizedContent;
        if (msgType == MessageType.IMAGE.code()) {
            normalizedContent = normalizeImageContent(content);
        } else if (msgType == MessageType.TEXT.code()) {
            if (content == null || content.isBlank()) {
                throw new GlobalException(400, "内容不能为空");
            }
            normalizedContent = content;
        } else {
            throw new GlobalException(400, "暂不支持该消息类型", "AI_CS_UNSUPPORTED_MESSAGE_TYPE");
        }

        String resolvedLocalId = StrUtil.isBlank(localId) ? null : localId.trim();
        if (resolvedLocalId != null) {
            if (resolvedLocalId.length() > 32) {
                throw new GlobalException(400, "localId 最长 32 字符");
            }
            Long existingId = findIdempotentMessageId(botUserId, resolvedLocalId, msgType, normalizedContent);
            if (existingId != null) {
                return existingId;
            }
        }

        replyOrchestrator.cancelPendingAiReply(botUserId, customerUserId);
        RLock lock = sessionLock(botUserId, customerUserId);
        lock.lock(10, TimeUnit.SECONDS);
        try {
            // 锁内再查一次幂等，避免并发双发
            if (resolvedLocalId != null) {
                Long existingId = findIdempotentMessageId(botUserId, resolvedLocalId, msgType, normalizedContent);
                if (existingId != null) {
                    return existingId;
                }
            }

            AiCsSession session = getOrCreateSession(botUserId, customerUserId);
            boolean formalTakeover = isFormalTakeover(session);
            if (formalTakeover && !Objects.equals(session.getOperatorId(), operatorId)) {
                throw new GlobalException(409, "该客户已由其他客服接管，无法代发", "AI_CS_TAKEOVER_CONFLICT");
            }

            Long msgId;
            if (msgType == MessageType.IMAGE.code()) {
                msgId = botPrivateMessageService.sendImageReturningId(
                    botUserId, customerUserId, normalizedContent, resolvedLocalId);
            } else {
                msgId = botPrivateMessageService.sendTextReturningId(
                    botUserId, customerUserId, normalizedContent, resolvedLocalId);
            }
            if (msgId != null) {
                recordHumanMessage(msgId, botUserId, customerUserId, operatorId);
                session.setLastMsgAt(new Date());
                session.setUpdatedTime(new Date());
                if (formalTakeover) {
                    session.setOperatorId(operatorId);
                }
                sessionMapper.updateById(session);
                log.info("[ai-cs-handoff] replyAsBot bot={} customer={} operator={} type={} msgId={} localId={}",
                    botUserId, customerUserId, operatorId, msgType, msgId, resolvedLocalId);
            }
            return msgId;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 命中已完成幂等记录：相同 type/content 返回原消息 ID；冲突抛 409。
     * 未命中返回 null。
     */
    private Long findIdempotentMessageId(Long botUserId, String localId, int msgType, String normalizedContent) {
        PrivateMessage existing = privateMessageMapper.selectOne(
            Wrappers.<PrivateMessage>lambdaQuery()
                .eq(PrivateMessage::getSendId, botUserId)
                .eq(PrivateMessage::getLocalId, localId)
                .ne(PrivateMessage::getStatus, MessageStatus.RECALL.code())
                .orderByDesc(PrivateMessage::getId)
                .last("limit 1"));
        if (existing == null) {
            return null;
        }
        boolean sameType = Objects.equals(existing.getType(), msgType);
        boolean sameContent = Objects.equals(
            StrUtil.trimToEmpty(existing.getContent()),
            StrUtil.trimToEmpty(normalizedContent));
        if (sameType && sameContent) {
            log.info("[ai-cs-handoff] idempotent hit bot={} localId={} msgId={}",
                botUserId, localId, existing.getId());
            return existing.getId();
        }
        throw new GlobalException(409, "相同 localId 但内容不同", "AI_CS_IDEMPOTENCY_CONFLICT");
    }

    /** 校验并规范化图片 JSON，只保留 originUrl/thumbUrl/width/height */
    private String normalizeImageContent(String content) {
        if (content == null || content.isBlank()) {
            throw new GlobalException(400, "图片内容不能为空", "AI_CS_INVALID_IMAGE_CONTENT");
        }
        final JSONObject raw;
        try {
            raw = JSON.parseObject(content.trim());
        } catch (Exception e) {
            throw new GlobalException(400, "图片内容格式无效", "AI_CS_INVALID_IMAGE_CONTENT");
        }
        if (raw == null) {
            throw new GlobalException(400, "图片内容格式无效", "AI_CS_INVALID_IMAGE_CONTENT");
        }
        String origin = raw.getString("originUrl");
        String thumb = raw.getString("thumbUrl");
        Integer width = raw.getInteger("width");
        Integer height = raw.getInteger("height");
        if (StrUtil.isBlank(origin) || StrUtil.isBlank(thumb)) {
            throw new GlobalException(400, "图片地址无效", "AI_CS_INVALID_IMAGE_CONTENT");
        }
        if (width == null || height == null || width <= 0 || height <= 0 || width > 20000 || height > 20000) {
            throw new GlobalException(400, "图片宽高无效", "AI_CS_INVALID_IMAGE_CONTENT");
        }
        assertManagedMediaUrl(origin);
        assertManagedMediaUrl(thumb);

        JSONObject normalized = new JSONObject(true);
        normalized.put("originUrl", origin.trim());
        normalized.put("thumbUrl", thumb.trim());
        normalized.put("width", width);
        normalized.put("height", height);
        return normalized.toJSONString();
    }

    private void assertManagedMediaUrl(String url) {
        String u = url.trim();
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("file:")
            || lower.startsWith("http://")) {
            throw new GlobalException(400, "图片地址协议不允许", "AI_CS_MEDIA_NOT_ALLOWED");
        }
        if (!lower.startsWith("https://")) {
            throw new GlobalException(400, "图片地址必须为 HTTPS", "AI_CS_MEDIA_NOT_ALLOWED");
        }
        String domain = minioProps != null ? StrUtil.trimToEmpty(minioProps.getDomain()) : "";
        if (domain.isEmpty()) {
            // 未配置 domain 时仅做协议校验，不伪造“已校验归属”
            return;
        }
        String prefix = domain.endsWith("/") ? domain : domain + "/";
        if (!u.startsWith(domain) && !u.startsWith(prefix)) {
            // 兼容 domain 本身已是带路径前缀的 CDN
            throw new GlobalException(403, "图片地址不在受管 CDN 范围内", "AI_CS_MEDIA_NOT_ALLOWED");
        }
    }

    /** 按钮「接管」：人工模式且已绑定接管客服 */
    private boolean isFormalTakeover(AiCsSession session) {
        return session != null
            && Objects.equals(session.getMode(), AiCsSession.MODE_HUMAN)
            && session.getOperatorId() != null;
    }

    @Override
    public Set<Long> humanMessageIds(Long botUserId, Long customerUserId) {
        LambdaQueryWrapper<AiCsHumanMessage> q = Wrappers.lambdaQuery();
        q.eq(AiCsHumanMessage::getBotUserId, botUserId);
        q.eq(AiCsHumanMessage::getCustomerUserId, customerUserId);
        List<AiCsHumanMessage> rows = humanMessageMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            return Set.of();
        }
        return rows.stream().map(AiCsHumanMessage::getMessageId).collect(Collectors.toSet());
    }

    @Override
    public boolean isAiCsBot(Long botUserId) {
        if (botUserId == null || !aiCsProps.isEnabled()) {
            return false;
        }
        Long primary = aiCsProps.getBotUserId();
        if (primary != null && primary > 0 && Objects.equals(primary, botUserId)) {
            return true;
        }
        List<Long> ids = aiCsProps.getBotUserIds();
        return ids != null && ids.stream().anyMatch(id -> id != null && Objects.equals(id, botUserId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferToHuman(Long botUserId, Long customerUserId, String reason, Long triggerMsgId) {
        if (!isHandoffEnabled()) {
            return;
        }
        RLock lock = sessionLock(botUserId, customerUserId);
        lock.lock(10, TimeUnit.SECONDS);
        try {
            AiCsSession session = getOrCreateSession(botUserId, customerUserId);
            boolean alreadyHuman = Objects.equals(session.getMode(), AiCsSession.MODE_HUMAN);
            if (REASON_AUTO.equals(reason) && alreadyHuman) {
                return;
            }
            session.setMode(AiCsSession.MODE_HUMAN);
            session.setOperatorId(null);
            session.setHandoffReason(reason);
            if (triggerMsgId != null) {
                session.setLastUserMsgId(triggerMsgId);
            }
            session.setLastMsgAt(new Date());
            session.setUpdatedTime(new Date());
            sessionMapper.updateById(session);

            if (!alreadyHuman) {
                String notice = aiCsProps.getHandoff().getTransferNotice();
                if (StrUtil.isNotBlank(notice)) {
                    botPrivateMessageService.sendText(botUserId, customerUserId, notice);
                }
            }
            log.info("[ai-cs-handoff] transfer bot={} customer={} reason={}",
                botUserId, customerUserId, reason);
        } finally {
            lock.unlock();
        }
    }

    private void recordHumanMessage(Long messageId, Long botUserId, Long customerUserId, Long operatorId) {
        AiCsHumanMessage row = new AiCsHumanMessage();
        row.setMessageId(messageId);
        row.setBotUserId(botUserId);
        row.setCustomerUserId(customerUserId);
        row.setOperatorId(operatorId);
        row.setCreatedTime(new Date());
        humanMessageMapper.insert(row);
    }

    private AiCsSession getOrCreateSession(Long botUserId, Long customerUserId) {
        AiCsSession existing = findSession(botUserId, customerUserId);
        if (existing != null) {
            return existing;
        }
        AiCsSession row = new AiCsSession();
        row.setBotUserId(botUserId);
        row.setCustomerUserId(customerUserId);
        row.setMode(AiCsSession.MODE_AI);
        Date now = new Date();
        row.setCreatedTime(now);
        row.setUpdatedTime(now);
        row.setLastMsgAt(now);
        sessionMapper.insert(row);
        return row;
    }

    private AiCsSession findSession(Long botUserId, Long customerUserId) {
        return sessionMapper.selectOne(Wrappers.<AiCsSession>lambdaQuery()
            .eq(AiCsSession::getBotUserId, botUserId)
            .eq(AiCsSession::getCustomerUserId, customerUserId)
            .last("limit 1"));
    }

    private AiCsSession requireSession(Long botUserId, Long customerUserId) {
        AiCsSession s = findSession(botUserId, customerUserId);
        if (s == null) {
            throw new GlobalException(404, "会话不存在");
        }
        return s;
    }

    private void requireAiCsBot(Long botUserId) {
        if (!isAiCsBot(botUserId)) {
            throw new GlobalException(400, "非 AI 客服机器人");
        }
    }

    private void requireAdminOperator(Long operatorId) {
        if (!KnowledgeIngestBotVisibility.isIngestAdmin(aiCsProps, operatorId)) {
            throw new GlobalException(403, "无客服工作台权限");
        }
    }

    private static String buildMessagePreview(PrivateMessage m) {
        if (m == null || m.getContent() == null) {
            return null;
        }
        MessageType type = MessageType.fromCode(m.getType());
        if (type == MessageType.IMAGE) {
            return "[图片]";
        }
        if (type == MessageType.VIDEO) {
            return "[视频]";
        }
        if (type == MessageType.AUDIO) {
            return "[语音]";
        }
        if (type == MessageType.FILE) {
            return "[文件]";
        }
        if (type == MessageType.STICKER) {
            return "[表情]";
        }
        if (type == MessageType.REDPACKET) {
            return "[红包]";
        }
        if (type == MessageType.LUCKY_BONUS_APPLY) {
            return "[活动彩金]";
        }
        if (type == MessageType.USER_CARD) {
            return "[名片]";
        }
        if (type == MessageType.GROUP_CARD) {
            return "[群名片]";
        }
        if (type == MessageType.MERGE_FORWARD) {
            return "[合并转发]";
        }
        if (type == MessageType.TIP_TEXT) {
            return "[系统提示]";
        }
        String preview = m.getContent();
        // 结构化 JSON 不要原样截断给客服预览（观感为乱码）
        if (preview != null && preview.trim().startsWith("{")) {
            return "[消息]";
        }
        if (preview.length() > 80) {
            return preview.substring(0, 80) + "…";
        }
        return preview;
    }

    private RLock sessionLock(Long botUserId, Long customerUserId) {
        return redissonClient.getLock("ai-cs:session:" + botUserId + ":" + customerUserId);
    }

    private Map<Long, User> loadUsers(Set<Long> customerIds, Set<Long> operatorIds) {
        Set<Long> all = new HashSet<>(customerIds);
        all.addAll(operatorIds);
        if (all.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectBatchIds(all);
        if (users == null) {
            return Map.of();
        }
        return users.stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    private AiCsSessionVO toSessionVo(AiCsSession row, Map<Long, User> users) {
        AiCsSessionVO vo = new AiCsSessionVO();
        vo.setBotUserId(row.getBotUserId());
        vo.setCustomerUserId(row.getCustomerUserId());
        vo.setMode(row.getMode());
        vo.setOperatorId(row.getOperatorId());
        vo.setHandoffReason(row.getHandoffReason());
        vo.setLastUserMsgId(row.getLastUserMsgId());
        vo.setLastMsgAt(row.getLastMsgAt());
        vo.setPendingHuman(Objects.equals(row.getMode(), AiCsSession.MODE_HUMAN)
            && row.getOperatorId() == null);

        User customer = users.get(row.getCustomerUserId());
        if (customer != null) {
            vo.setCustomerNickName(customer.getNickName());
            vo.setCustomerUserName(customer.getUserName());
        }
        if (row.getOperatorId() != null) {
            User op = users.get(row.getOperatorId());
            if (op != null) {
                vo.setOperatorNickName(op.getNickName());
            }
        }
        if (row.getLastUserMsgId() != null || row.getLastMsgAt() != null) {
            String convKey = ConvUtil.buildConvKey(row.getCustomerUserId(), row.getBotUserId());
            PrivateMessage m = privateMessageMapper.selectOne(Wrappers.<PrivateMessage>lambdaQuery()
                .eq(PrivateMessage::getConvKey, convKey)
                .ne(PrivateMessage::getStatus, MessageStatus.RECALL.code())
                .orderByDesc(PrivateMessage::getId)
                .last("limit 1"));
            if (m != null) {
                vo.setLastMessageType(m.getType());
                vo.setLastMessagePreview(buildMessagePreview(m));
            }
        }
        return vo;
    }
}
