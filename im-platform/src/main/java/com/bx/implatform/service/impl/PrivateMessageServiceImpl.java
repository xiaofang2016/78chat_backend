package com.bx.implatform.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.JakartaServletUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bx.imclient.IMClient;
import com.bx.imcommon.contant.IMConstant;
import com.bx.imcommon.model.IMPrivateMessage;
import com.bx.imcommon.model.IMUserInfo;
import com.bx.implatform.annotation.RedisLock;
import com.bx.implatform.contant.Constant;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.dto.ChatDeleteDTO;
import com.bx.implatform.dto.MessageDeleteDTO;
import com.bx.implatform.dto.PrivateMessageDTO;
import com.bx.implatform.dto.PrivateMessageHistoryDTO;
import com.bx.implatform.entity.MessageDeletion;
import com.bx.implatform.entity.PrivateMessage;
import com.bx.implatform.enums.ChatType;
import com.bx.implatform.enums.DeleteType;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.PrivateMessageMapper;
import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.config.props.BoximIntegrationProperties;
import com.bx.implatform.service.AiCsHandoffService;
import com.bx.implatform.service.AiCsReplyOrchestrator;
import com.bx.implatform.service.AiKnowledgeBaseService;
import com.bx.implatform.service.FriendService;
import com.bx.implatform.service.MessageDeletionService;
import com.bx.implatform.service.PrivateMessageService;
import com.bx.implatform.service.TalkLinkService;
import com.bx.implatform.service.UserBlacklistService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.session.UserSession;
import com.bx.implatform.util.BeanUtils;
import com.bx.implatform.util.ConvUtil;
import com.bx.implatform.util.JsonFormatUtil;
import com.bx.implatform.util.SensitiveFilterUtil;
import com.bx.implatform.util.TipContentUtil;
import com.bx.implatform.vo.PrivateMessageVO;
import com.bx.implatform.vo.QuoteMessageVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.util.ThreadPoolExecutorFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrivateMessageServiceImpl extends ServiceImpl<PrivateMessageMapper, PrivateMessage>
    implements PrivateMessageService {

    private final FriendService friendService;
    private final UserBlacklistService userBlacklistService;
    private final MessageDeletionService messageDeletionService;
    private final IMClient imClient;
    private final SensitiveFilterUtil sensitiveFilterUtil;
    private final HttpServletRequest request;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final AiCustomerServiceProperties aiCsProps;
    private final AiKnowledgeBaseService knowledgeBaseService;
    private final BoximIntegrationProperties boximProps;
    private final AiCsHandoffService aiCsHandoffService;
    private final LuckyBonusApplyCardEnricher luckyBonusApplyCardEnricher;
    private final AiCsReplyOrchestrator aiCsReplyOrchestrator;
    private final TalkLinkService talkLinkService;

    private static final DateTimeFormatter KB_TITLE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 自注入代理：异步线程里没有 AopContext，所以用 self 触发 saveMessage 的分布式锁切面 */
    @Lazy
    @Autowired
    private PrivateMessageService self;

    private final ScheduledThreadPoolExecutor aiExecutor = ThreadPoolExecutorFactory.getThreadPoolExecutor();

    @Override
    public PrivateMessageVO sendMessage(PrivateMessageDTO dto) {
        validMessage(dto);
        UserSession session = SessionContext.getSession();
        if (Objects.equals(session.getUserId(), dto.getRecvId())) {
            throw new GlobalException(403, "privateMsg.cannotSendToSelf");
        }
        boolean toKbIngestBot = isKnowledgeIngestBot(dto.getRecvId());
        if (toKbIngestBot && !canIngestKnowledge(session.getUserId())) {
            throw new GlobalException(403, "无权使用「AI知识库」录入功能，请联系管理员将你的账号加入白名单。");
        }
        // 系统/AI 机器人（含 ai-cs.bot-user-id(s) 与 boxim.redpacket-bot.user-id）免好友校验
        boolean exemptFriendCheck = isExemptFromFriendCheck(dto.getRecvId());
        boolean toAiBot = isAiBot(dto.getRecvId());
        if (!exemptFriendCheck) {
            boolean isFriend = friendService.isFriend(session.getUserId(), dto.getRecvId());
            if (!isFriend && !talkLinkService.canSendWithoutFriend(session.getUserId(), dto.getRecvId())) {
                throw new GlobalException(403, "privateMsg.notFriend");
            }
            if (userBlacklistService.isInBlacklist(dto.getRecvId(), session.getUserId())) {
                throw new GlobalException(403, "privateMsg.blocked");
            }
        }
        // 保存消息
        PrivateMessage message = BeanUtils.copyProperties(dto, PrivateMessage.class);
        message.setConvKey(ConvUtil.buildConvKey(session.getUserId(), dto.getRecvId()));
        message.setSendId(session.getUserId());
        message.setStatus(MessageStatus.PENDING.code());
        message.setSendTime(new Date());
        // 过滤内容中的敏感词
        if (MessageType.TEXT.code().equals(dto.getType())) {
            message.setContent(sensitiveFilterUtil.filter(dto.getContent()));
        }
        // 保存消息(走代理触发分布式锁)
        PrivateMessageServiceImpl proxy = (PrivateMessageServiceImpl)AopContext.currentProxy();
        proxy.saveMessage(message);
        // 推送消息
        PrivateMessageVO vo = BeanUtils.copyProperties(message, PrivateMessageVO.class);
        // 填充引用消息
        if (!Objects.isNull(dto.getQuoteMessageId())) {
            PrivateMessage quoteMessage = this.getById(dto.getQuoteMessageId());
            QuoteMessageVO quoteMessageVo = BeanUtils.copyProperties(quoteMessage, QuoteMessageVO.class);
            fillQuoteMessage(vo, quoteMessageVo);
        }
        IMPrivateMessage<PrivateMessageVO> sendMessage = new IMPrivateMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvId(vo.getRecvId());
        sendMessage.setSendToSelf(true);
        sendMessage.setData(vo);
        sendMessage.setSendResult(true);
        imClient.sendPrivateMessage(sendMessage);
        String ip = JakartaServletUtil.getClientIP(request);
        log.info("发送私聊消息,ip:{},发送id:{},接收id:{},内容:{}", ip, session.getUserId(), dto.getRecvId(),
            dto.getContent());

        // 「AI知识库」录入机器人：异步入库（不走 GPT 客服）
        if (toKbIngestBot && MessageType.TEXT.code().equals(dto.getType())) {
            Long userId = session.getUserId();
            Long botUserId = dto.getRecvId();
            String userMsg = message.getContent();
            aiExecutor.execute(() -> handleKnowledgeIngest(botUserId, userId, userMsg));
        } else if (toAiBot && MessageType.TEXT.code().equals(dto.getType())) {
            Long userId = session.getUserId();
            Long botUserId = dto.getRecvId();
            String userMsg = message.getContent();
            Long messageId = message.getId();
            aiCsHandoffService.onUserMessageToBot(botUserId, userId, messageId, userMsg);
            if (aiCsHandoffService.shouldSkipAiReply(botUserId, userId)) {
                log.info("[ai-cs] skip auto-reply, human takeover userId={} botId={}", userId, botUserId);
                return vo;
            }
            log.info("[ai-cs] queue reply userId={} botId={} msgId={} text={}",
                userId, botUserId, messageId, userMsg);
            aiExecutor.execute(() -> aiCsReplyOrchestrator.handleCustomerMessage(
                botUserId, userId, userId, messageId, userMsg));
        }
        return vo;
    }

    @Transactional
    @Override
    public PrivateMessageVO recallMessage(Long id) {
        UserSession session = SessionContext.getSession();
        PrivateMessage recallMessage = this.getById(id);
        if (Objects.isNull(recallMessage)) {
            throw new GlobalException("privateMsg.messageNotExist");
        }
        if (!recallMessage.getSendId().equals(session.getUserId())) {
            throw new GlobalException("privateMsg.notSender");
        }
        if (System.currentTimeMillis() - recallMessage.getSendTime()
            .getTime() > IMConstant.ALLOW_RECALL_SECOND * 1000) {
            throw new GlobalException("privateMsg.recallTimeout");
        }
        // 修改消息状态
        String mark = formatUserMark(session.getUserId(), session.getNickName());
        // 记录撤回提示语到扩展字段(用于前端展示)
        String recallTip = TipContentUtil.buildTipContent("tip.recall.self", mark);
        recallMessage.setStatus(MessageStatus.RECALL.code());
        this.updateById(recallMessage);
        // 生成一条撤回消息
        PrivateMessage message = new PrivateMessage();
        message.setLocalId(IdWorker.getIdStr());
        message.setConvKey(recallMessage.getConvKey());
        message.setSendId(session.getUserId());
        message.setStatus(MessageStatus.PENDING.code());
        message.setSendTime(new Date());
        message.setRecvId(recallMessage.getRecvId());
        message.setType(MessageType.RECALL.code());
        HashMap contentMap = new HashMap();
        contentMap.put("id", id);
        contentMap.put("tip", recallTip);
        message.setContent(JSON.toJSONString(contentMap));
        // 走代理触发分布式锁
        PrivateMessageServiceImpl proxy = (PrivateMessageServiceImpl)AopContext.currentProxy();
        proxy.saveMessage(message);
        // 推送消息
        PrivateMessageVO vo = BeanUtils.copyProperties(message, PrivateMessageVO.class);
        IMPrivateMessage<PrivateMessageVO> sendMessage = new IMPrivateMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvId(vo.getRecvId());
        sendMessage.setData(vo);
        imClient.sendPrivateMessage(sendMessage);
        log.info("撤回私聊消息，发送id:{},接收id:{}，内容:{}", message.getSendId(), message.getRecvId(),
            message.getContent());
        return vo;
    }

    @Override
    public List<PrivateMessageVO> loadOfflineMessage(Long minId) {
        long time = System.currentTimeMillis();
        UserSession session = SessionContext.getSession();
        // 获取当前用户的消息
        LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
        // 只能拉取最近1个月的消息
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        wrapper.gt(PrivateMessage::getId, minId);
        wrapper.ge(PrivateMessage::getSendTime, minDate);
        wrapper.and(wp -> wp.eq(PrivateMessage::getSendId, session.getUserId()).or()
            .eq(PrivateMessage::getRecvId, session.getUserId()));
        wrapper.orderByDesc(PrivateMessage::getId);
        wrapper.last("limit "+Constant.MAX_OFFLINE_MESSAGE_SIZE);
        List<PrivateMessage> messages = this.list(wrapper);
        // 保证每个会话至少会拉到一条消息
        if (messages.size() >= Constant.MAX_OFFLINE_MESSAGE_SIZE) {
            messages = appendLastMessageInConversation(messages, minId);
        }
        List<MessageDeletion> deletions =
            messageDeletionService.findByChatType(session.getUserId(), ChatType.PRIVATE.getCode());
        // 整个会话删除的消息不在重复推送
        messages = messages.stream().filter(m -> !isDeleteChat(m, deletions)).collect(Collectors.toList());
        // 更新消息为送达状态
        List<Long> messageIds = messages.stream().filter(m -> m.getRecvId().equals(session.getUserId()))
            .filter(m -> m.getStatus().equals(MessageStatus.PENDING.code())).map(PrivateMessage::getId)
            .collect(Collectors.toList());
        if (!messageIds.isEmpty()) {
            LambdaUpdateWrapper<PrivateMessage> updateWrapper = Wrappers.lambdaUpdate();
            updateWrapper.in(PrivateMessage::getId, messageIds);
            updateWrapper.set(PrivateMessage::getStatus, MessageStatus.DELIVERED.code());
            update(updateWrapper);
        }
        // 提取所有引用消息
        Map<Long, QuoteMessageVO> quoteMessageMap = batchLoadQuoteMessage(messages);
        // 转换vo
        List<PrivateMessageVO> vos = messages.stream().map(m -> {
            PrivateMessageVO vo = BeanUtils.copyProperties(m, PrivateMessageVO.class);
            fillQuoteMessage(vo, quoteMessageMap.get(m.getQuoteMessageId()));
            vo.setDeleted(isDeleteMessage(m, deletions));
            return vo;
        }).toList();
        log.info("拉取离线私聊消息,用户id:{},数量:{},耗时:{},minId:{}", session.getUserId(), vos.size(),
            System.currentTimeMillis() - time, minId);
        List<PrivateMessageVO> sorted = vos.stream().sorted(Comparator.comparing(PrivateMessageVO::getId))
            .collect(Collectors.toList());
        luckyBonusApplyCardEnricher.enrich(session.getUserId(), sorted);
        return sorted;
    }

    @Override
    public void readedMessage(Long friendId, Long messageId) {
        UserSession session = SessionContext.getSession();
        // 如果前端没有传消息id,取出最后一条消息id
        String convKey = ConvUtil.buildConvKey(session.getUserId(), friendId);
        if (Objects.isNull(messageId)) {
            messageId = findMaxMessageId(convKey);
            if (messageId < 0) {
                return;
            }
        }
        // 推送消息给自己，清空会话列表上的已读数量
        PrivateMessageVO msgInfo = new PrivateMessageVO();
        msgInfo.setType(MessageType.READED.code());
        msgInfo.setSendId(session.getUserId());
        msgInfo.setRecvId(friendId);
        IMPrivateMessage<PrivateMessageVO> sendMessage = new IMPrivateMessage<>();
        sendMessage.setData(msgInfo);
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setSendToSelf(true);
        sendMessage.setSendResult(false);
        imClient.sendPrivateMessage(sendMessage);
        // 推送回执消息给对方，更新已读状态
        msgInfo = new PrivateMessageVO();
        msgInfo.setType(MessageType.RECEIPT.code());
        msgInfo.setSendId(session.getUserId());
        msgInfo.setRecvId(friendId);
        HashMap contentMap = new HashMap();
        contentMap.put("id", messageId);
        msgInfo.setContent(JSON.toJSONString(contentMap));
        sendMessage = new IMPrivateMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvId(friendId);
        sendMessage.setSendToSelf(false);
        sendMessage.setSendResult(false);
        sendMessage.setData(msgInfo);
        imClient.sendPrivateMessage(sendMessage);
        // 会话最大的消息id
        Long lastMaxReadedId = getMaxReadedId(friendId, session.getUserId());
        if (lastMaxReadedId < messageId) {
            // 修改消息状态为已读
            LambdaUpdateWrapper<PrivateMessage> updateWrapper = Wrappers.lambdaUpdate();
            updateWrapper.eq(PrivateMessage::getSendId, friendId);
            updateWrapper.eq(PrivateMessage::getRecvId, session.getUserId());
            updateWrapper.gt(PrivateMessage::getId, lastMaxReadedId);
            updateWrapper.ne(PrivateMessage::getStatus, MessageStatus.RECALL.code());
            updateWrapper.set(PrivateMessage::getStatus, MessageStatus.READED.code());
            this.update(updateWrapper);
            // 记录新的最大已读消息id
            String key = StrUtil.join(":", RedisKey.IM_PRIVATE_READED_POSITION, friendId, session.getUserId());
            redisTemplate.opsForValue().set(key, messageId, Constant.MAX_OFFLINE_MESSAGE_DAYS, TimeUnit.DAYS);
        }
        log.info("消息已读，接收方id:{},发送方id:{}", session.getUserId(), friendId);
    }

    @Override
    public Long getMaxReadedId(Long sendId, Long recvId) {
        String key = StrUtil.join(":", RedisKey.IM_PRIVATE_READED_POSITION, sendId, recvId);
        Object id = redisTemplate.opsForValue().get(key);
        if (!Objects.isNull(id)) {
            return Long.parseLong(id.toString());
        }
        LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(PrivateMessage::getSendId, sendId);
        wrapper.eq(PrivateMessage::getRecvId, recvId);
        wrapper.eq(PrivateMessage::getStatus, MessageStatus.READED.code());
        wrapper.orderByDesc(PrivateMessage::getId);
        wrapper.select(PrivateMessage::getId).last("limit 1");
        PrivateMessage message = this.getOne(wrapper);
        if (!Objects.isNull(message)) {
            return message.getId();
        }
        return -1L;
    }

    @Override
    public void deleteMessage(MessageDeleteDTO dto) {
        UserSession session = SessionContext.getSession();
        messageDeletionService.deleteByMessage(session.getUserId(), ChatType.PRIVATE.getCode(), dto.getChatId(),
            dto.getMessageIds());
    }

    @Override
    public void deleteChat(ChatDeleteDTO dto) {
        Long userId = SessionContext.getSession().getUserId();
        // 查询会话最大消息id
        String convKey = ConvUtil.buildConvKey(userId, dto.getChatId());
        Long maxMessageId = this.findMaxMessageId(convKey);
        if (maxMessageId < 0) {
            return;
        }
        messageDeletionService.deleteByChat(userId, ChatType.PRIVATE.getCode(), dto.getChatId(), maxMessageId);
    }

    /**
     * 保存消息 加分布式锁是为了让数据库自增id和seq_no保持同序
     */
    @Override
    @RedisLock(prefixKey = RedisKey.IM_LOCK_PRIVATE_MESSAGE_SAVE, key = "#message.convKey")
    public void saveMessage(PrivateMessage message) {
        if (StrUtil.isEmpty(message.getLocalId())) {
            message.setLocalId(IdWorker.getIdStr());
        }
        message.setSeqNo(getNextSeqNo(message.getConvKey()));
        save(message);
        // 记录消息最大id
        String key = StrUtil.join(":", RedisKey.IM_PRIVATE_MESSAGE_MAX_ID, message.getConvKey());
        redisTemplate.opsForValue().set(key, message.getId(), Constant.MAX_OFFLINE_MESSAGE_DAYS, TimeUnit.DAYS);
    }

    @Override
    public List<PrivateMessageVO> loadHistoryMessage(PrivateMessageHistoryDTO dto) {
        UserSession session = SessionContext.getSession();
        String convKey = ConvUtil.buildConvKey(session.getUserId(), dto.getFriendId());
        LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(PrivateMessage::getConvKey, convKey);
        if (CollectionUtil.isNotEmpty(dto.getLocalIds())) {
            wrapper.in(PrivateMessage::getLocalId, dto.getLocalIds());
        } else if (CollectionUtil.isNotEmpty(dto.getSeqNos())) {
            wrapper.in(PrivateMessage::getSeqNo, dto.getSeqNos());
        } else if (!Objects.isNull(dto.getMaxSeqNo()) && !Objects.isNull(dto.getMinSeqNo())) {
            wrapper.between(PrivateMessage::getSeqNo, dto.getMinSeqNo(), dto.getMaxSeqNo());
        }
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        wrapper.ge(PrivateMessage::getSendTime, minDate);
        wrapper.orderByAsc(PrivateMessage::getSeqNo);
        wrapper.last("limit 100");
        List<PrivateMessage> messages = this.list(wrapper);
        if (CollectionUtil.isEmpty(messages)) {
            return new ArrayList<>();
        }
        // 已经删除的消息
        List<MessageDeletion> deletions =
            messageDeletionService.findByChatIdAndType(session.getUserId(), ChatType.PRIVATE.getCode(),
                dto.getFriendId());
        // 整个会话删掉的消息就不再推送了
        messages = messages.stream().filter(m -> !isDeleteChat(m, deletions)).collect(Collectors.toList());
        // 提取所有引用消息
        Map<Long, QuoteMessageVO> quoteMessageMap = batchLoadQuoteMessage(messages);
        // 转换vo
        List<PrivateMessageVO> vos = messages.stream().map(m -> {
            PrivateMessageVO vo = BeanUtils.copyProperties(m, PrivateMessageVO.class);
            fillQuoteMessage(vo, quoteMessageMap.get(m.getQuoteMessageId()));
            vo.setDeleted(isDeleteMessage(m, deletions));
            return vo;
        }).collect(Collectors.toList());
        luckyBonusApplyCardEnricher.enrich(session.getUserId(), vos);
        return vos;
    }

    private Map<Long, QuoteMessageVO> batchLoadQuoteMessage(List<PrivateMessage> messages) {
        // 提取列表中所有引用消息
        List<Long> ids = messages.stream().map(PrivateMessage::getQuoteMessageId)
            .filter(quoteMessageId -> !Objects.isNull(quoteMessageId)).collect(Collectors.toList());
        if (CollectionUtil.isEmpty(ids)) {
            return new HashMap<>();
        }
        LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.in(PrivateMessage::getId, ids);
        List<PrivateMessage> quoteMessages = this.list(wrapper);
        // 转为vo
        return quoteMessages.stream()
            .collect(Collectors.toMap(PrivateMessage::getId, m -> BeanUtils.copyProperties(m, QuoteMessageVO.class)));
    }

    private void validMessage(PrivateMessageDTO dto) {
        // 只允许用户发送普通消息
        if (dto.getType() < MessageType.TEXT.code() || dto.getType() > MessageType.MERGE_FORWARD.code()) {
            throw new GlobalException("privateMsg.typeInvalid");
        }
        // 文字消息-长度校验（AI知识库机器人单独放宽为 UTF-8 50KB）
        if (MessageType.TEXT.code().equals(dto.getType())) {
            if (isKnowledgeIngestBot(dto.getRecvId())) {
                int bytes = Constant.utf8ByteLength(dto.getContent());
                if (bytes > Constant.MAX_KNOWLEDGE_INGEST_BOT_UTF8_BYTES) {
                    throw new GlobalException(400, "privateMsg.knowledgeIngestUtf8TooLarge",
                        Constant.MAX_KNOWLEDGE_INGEST_BOT_UTF8_BYTES, bytes);
                }
            } else if (dto.getContent().length() > Constant.MAX_MESSAGE_LENGTH) {
                throw new GlobalException("groupMsg.lengthExceed", Constant.MAX_MESSAGE_LENGTH);
            }
        }
        try {
            // 非文字消息-保证数据格式是json,防止前端报错（Jackson 语法校验，避免 Fastjson @type RCE）
            if (!MessageType.TEXT.code().equals(dto.getType())) {
                JsonFormatUtil.requireValidJson(dto.getContent());
            }
        } catch (Exception e) {
            throw new GlobalException("groupMsg.formatError");
        }
    }

    /**
     * 「AI知识库」录入机器人（与 GPT 客服机器人互斥配置：勿把同一 userId 配进 bot-user-ids）。
     */
    private boolean isKnowledgeIngestBot(Long recvId) {
        AiCustomerServiceProperties.KnowledgeIngestBot kb = aiCsProps.getKnowledgeIngestBot();
        if (kb == null || !kb.isEnabled() || recvId == null) {
            return false;
        }
        Long uid = kb.getUserId();
        return uid != null && uid > 0 && sameLongValue(uid, recvId);
    }

    /** YAML 中数字常被绑定为 Integer，与 Long 混用 equals 会失败 */
    private static boolean sameLongValue(Number a, Number b) {
        if (a == null || b == null) {
            return false;
        }
        return a.longValue() == b.longValue();
    }

    private boolean canIngestKnowledge(Long senderId) {
        AiCustomerServiceProperties.KnowledgeIngestBot kb = aiCsProps.getKnowledgeIngestBot();
        if (kb == null || senderId == null) {
            return false;
        }
        List<Long> admins = kb.getAdminUserIds();
        if (admins == null || admins.isEmpty()) {
            return false;
        }
        long sid = senderId.longValue();
        return admins.stream().anyMatch(a -> a != null && a.longValue() == sid);
    }

    private void handleKnowledgeIngest(Long botUserId, Long userId, String text) {
        try {
            String[] tb = deriveKbTitleAndBody(userId, text);
            String title = tb[0];
            String body = tb[1];
            if (body == null || body.isBlank()) {
                sendAiBotText(botUserId, userId, "❌ 正文为空。自定义标题：第一行写短标题（≤80 字），换行后写正文。");
                return;
            }
            int n = knowledgeBaseService.ingestDocument(title, body, true);
            sendAiBotText(botUserId, userId,
                String.format("✅ 已录入知识库。\n来源标题：%s\n向量片段数：%d", title, n));
        } catch (GlobalException e) {
            sendAiBotText(botUserId, userId,
                "❌ 录入失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        } catch (Exception e) {
            log.warn("[ai-kb-ingest] failed userId={} err={}", userId, e.getMessage(), e);
            sendAiBotText(botUserId, userId, "❌ 录入失败，请稍后重试或查看服务端日志。");
        }
    }

    static String[] deriveKbTitleAndBody(long userId, String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            return new String[]{autoKbTitle(userId), ""};
        }
        int nl = t.indexOf('\n');
        if (nl > 0 && nl <= 120) {
            String first = t.substring(0, nl).trim();
            String rest = t.substring(nl + 1).trim();
            if (!rest.isEmpty() && first.length() <= 80) {
                String title = stripKbTitlePrefix(first);
                if (title.isEmpty()) {
                    title = autoKbTitle(userId);
                }
                return new String[]{title, rest};
            }
        }
        return new String[]{autoKbTitle(userId), t};
    }

    static String autoKbTitle(long userId) {
        return "KB-" + userId + "-" + LocalDateTime.now().format(KB_TITLE_TS);
    }

    static String stripKbTitlePrefix(String line) {
        String s = line;
        String[] prefs = {"标题:", "标题：", "题目:", "题目：", "Title:", "title:"};
        for (String p : prefs) {
            if (s.startsWith(p)) {
                s = s.substring(p.length()).trim();
            }
        }
        if (s.startsWith("#")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    /**
     * 是否为"AI 客服机器人"接收方（仅这些会触发 AI 自动回复）。
     * 命中条件：ai-cs 启用 且 recvId ∈ {bot-user-id} ∪ bot-user-ids。
     */
    private boolean isAiBot(Long recvId) {
        if (recvId == null || !aiCsProps.isEnabled()) {
            return false;
        }
        if (isKnowledgeIngestBot(recvId)) {
            return false;
        }
        Long primary = aiCsProps.getBotUserId();
        if (primary != null && primary > 0 && sameLongValue(primary, recvId)) {
            return true;
        }
        List<Long> ids = aiCsProps.getBotUserIds();
        if (ids == null) {
            return false;
        }
        return ids.stream().anyMatch(id -> id != null && sameLongValue(id, recvId));
    }

    /**
     * 是否为系统级机器人接收方，需免好友校验。
     * 包含：① 全部 AI 客服机器人；② 红包机器人。
     */
    private boolean isExemptFromFriendCheck(Long recvId) {
        if (recvId == null) {
            return false;
        }
        if (isKnowledgeIngestBot(recvId)) {
            return true;
        }
        if (isAiBot(recvId)) {
            return true;
        }
        BoximIntegrationProperties.RedpacketBot rp = boximProps == null ? null : boximProps.getRedpacketBot();
        return rp != null && rp.getUserId() != null && rp.getUserId() > 0
            && Objects.equals(rp.getUserId(), recvId);
    }

    /**
     * 由指定的 AI 机器人向用户发回一条文本消息。
     *
     * @param botUserId  发送方机器人 user_id（小红/小绿等）
     * @param recvUserId 接收方真实用户 id
     * @param content    回复内容
     */
    private void sendAiBotText(Long botUserId, Long recvUserId, String content) {
        if (botUserId == null || botUserId <= 0) {
            return;
        }
        PrivateMessage message = new PrivateMessage();
        message.setConvKey(ConvUtil.buildConvKey(botUserId, recvUserId));
        message.setSendId(botUserId);
        message.setRecvId(recvUserId);
        message.setType(MessageType.TEXT.code());
        message.setStatus(MessageStatus.PENDING.code());
        message.setSendTime(new Date());
        message.setContent(sensitiveFilterUtil.filter(content));
        // 异步线程里 AopContext 不可用，改用 self 触发分布式锁切面
        self.saveMessage(message);

        PrivateMessageVO vo = BeanUtils.copyProperties(message, PrivateMessageVO.class);
        IMPrivateMessage<PrivateMessageVO> sendMessage = new IMPrivateMessage<>();
        sendMessage.setSender(new IMUserInfo(botUserId, IMTerminalType.WEB.code()));
        sendMessage.setRecvId(recvUserId);
        sendMessage.setSendToSelf(false);
        sendMessage.setSendResult(true);
        sendMessage.setData(vo);
        imClient.sendPrivateMessage(sendMessage);
    }

    private Boolean isDeleteMessage(PrivateMessage message, List<MessageDeletion> deletions) {
        return deletions.stream().anyMatch(deletion -> {
            if (!message.getSendId().equals(deletion.getChatId()) && !message.getRecvId()
                .equals(deletion.getChatId())) {
                return false;
            }
            if (DeleteType.BY_MESSAGE.getCode().equals(deletion.getDeleteType())) {
                return deletion.getMessageId().equals(message.getId());
            }
            return false;
        });
    }

    private Boolean isDeleteChat(PrivateMessage message, List<MessageDeletion> deletions) {
        return deletions.stream().anyMatch(deletion -> {
            if (!message.getSendId().equals(deletion.getChatId()) && !message.getRecvId()
                .equals(deletion.getChatId())) {
                return false;
            }
            if (DeleteType.BY_CHAT.getCode().equals(deletion.getDeleteType())) {
                return deletion.getMessageId() >= message.getId();
            }
            return false;
        });
    }

    private String formatUserMark(Long userId, String displayName) {
        // 格式：#{displayName:userId}，例如：#{张三:123}
        return String.format("#{%s:%d}", displayName, userId);
    }

    private void fillQuoteMessage(PrivateMessageVO message, QuoteMessageVO quoteMessage) {
        if (Objects.isNull(quoteMessage)) {
            return;
        }
        message.setQuoteMessage(quoteMessage);
        // 防止显示已撤回的内容
        if (quoteMessage.getStatus().equals(MessageStatus.RECALL.code())) {
            message.getQuoteMessage().setType(MessageType.TIP_TEXT.code());
            message.getQuoteMessage().setStatus(MessageStatus.RECALL.code());
            message.getQuoteMessage().setContent(TipContentUtil.buildTipContent("tip.recall.quote"));
        }
    }

    private Long getNextSeqNo(String convKey) {
        String key = StrUtil.join(":", RedisKey.IM_PRIVATE_MESSAGE_MAX_SEQ, convKey);
        Long seqNo = redisTemplate.opsForValue().increment(key);
        if (seqNo > 1L) {
            return seqNo;
        }
        redisTemplate.delete(key);
        RLock lock = redissonClient.getLock(RedisKey.IM_LOCK_PRIVATE_MESSAGE_MAX_SEQ);
        lock.lock();
        try {
            //Double check
            if (redisTemplate.hasKey(key)) {
                return redisTemplate.opsForValue().increment(key);
            }
            LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(PrivateMessage::getConvKey, convKey);
            wrapper.orderByDesc(PrivateMessage::getSeqNo);
            wrapper.last("limit 1");
            PrivateMessage lastMessage = this.getOne(wrapper);
            seqNo = 1L;
            if (!Objects.isNull(lastMessage) && !Objects.isNull(lastMessage.getSeqNo())) {
                seqNo += lastMessage.getSeqNo();
            }
            redisTemplate.opsForValue().set(key, seqNo);
            return seqNo;
        } finally {
            lock.unlock();
        }
    }

    Long findMaxMessageId(String convKey) {
        String key = StrUtil.join(":", RedisKey.IM_PRIVATE_MESSAGE_MAX_ID, convKey);
        Object id = redisTemplate.opsForValue().get(key);
        if (!Objects.isNull(id)) {
            return Long.parseLong(id.toString());
        }
        LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(PrivateMessage::getConvKey, convKey);
        wrapper.orderByDesc(PrivateMessage::getId);
        wrapper.last("limit 1");
        PrivateMessage message = this.getOne(wrapper);
        if (!Objects.isNull(message)) {
            redisTemplate.opsForValue().set(key, message.getId());
            return message.getId();
        }
        return -1L;
    }

    List<PrivateMessage> appendLastMessageInConversation(List<PrivateMessage> messages, Long minId) {
        UserSession session = SessionContext.getSession();
        List<Long> fIds = friendService.findFriendIds();
        Set<Long> existIds = messages.stream().map(m -> getFriendId(session, m)).collect(Collectors.toSet());
        // 移除已经拉到消息的会话
        fIds.removeAll(existIds);
        if (fIds.isEmpty()) {
            return messages;
        }
        List<String> keys =
            fIds.stream().map(id -> buildMaxMessageIdKey(session.getUserId(), id)).collect(Collectors.toList());
        List<Object> maxMessageIds = redisTemplate.opsForValue().multiGet(keys);
        maxMessageIds =
            maxMessageIds.stream().filter(id -> !Objects.isNull(id) && Long.parseLong(id.toString()) > minId)
                .collect(Collectors.toList());
        if (maxMessageIds.isEmpty()) {
            return messages;
        }
        LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        wrapper.ge(PrivateMessage::getSendTime, minDate);
        wrapper.in(PrivateMessage::getId, maxMessageIds);
        List<PrivateMessage> lastMessages = this.list(wrapper);
        messages.addAll(lastMessages);
        return messages;
    }

    private Long getFriendId(UserSession session, PrivateMessage message) {
        return session.getUserId().equals(message.getSendId()) ? message.getRecvId() : message.getSendId();
    }

    private String buildMaxMessageIdKey(Long userId1, Long userId2) {
        return StrUtil.join(":", RedisKey.IM_PRIVATE_MESSAGE_MAX_ID, ConvUtil.buildConvKey(userId1, userId2));
    }


}
