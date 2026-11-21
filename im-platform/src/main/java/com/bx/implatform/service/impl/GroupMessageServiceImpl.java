package com.bx.implatform.service.impl;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.JakartaServletUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bx.imclient.IMClient;
import com.bx.imcommon.contant.IMConstant;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.IMGroupMessage;
import com.bx.imcommon.model.IMUserInfo;
import com.bx.imcommon.util.CommaTextUtils;
import com.bx.implatform.annotation.RedisLock;
import com.bx.implatform.contant.Constant;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.dto.ChatDeleteDTO;
import com.bx.implatform.dto.GroupMessageDTO;
import com.bx.implatform.dto.GroupMessageHistoryDTO;
import com.bx.implatform.dto.MessageDeleteDTO;
import com.bx.implatform.entity.Group;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.entity.GroupMessage;
import com.bx.implatform.entity.MessageDeletion;
import com.bx.implatform.enums.ChatType;
import com.bx.implatform.enums.DeleteType;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.GroupMessageMapper;
import com.bx.implatform.service.GroupMemberService;
import com.bx.implatform.service.GroupMessageService;
import com.bx.implatform.service.GroupService;
import com.bx.implatform.service.MessageDeletionService;
import com.bx.implatform.service.ZhiquanChatStore;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.session.UserSession;
import com.bx.implatform.util.BeanUtils;
import com.bx.implatform.util.JsonFormatUtil;
import com.bx.implatform.util.SensitiveFilterUtil;
import com.bx.implatform.util.TipContentUtil;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.GroupVO;
import com.bx.implatform.vo.QuoteMessageVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupMessageServiceImpl extends ServiceImpl<GroupMessageMapper, GroupMessage>
    implements GroupMessageService {
    private final GroupService groupService;
    private final GroupMemberService groupMemberService;
    private final MessageDeletionService messageDeletionService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final IMClient imClient;
    private final SensitiveFilterUtil sensitiveFilterUtil;
    private final HttpServletRequest request;
    private final RedissonClient redissonClient;
    private final ZhiquanChatStore zhiquanChatStore;

    @Override
    public GroupMessageVO sendMessage(GroupMessageDTO dto) {
        validMessage(dto);
        UserSession session = SessionContext.getSession();
        boolean shared = zhiquanChatStore.contains(dto.getGroupId());
        Group group = groupService.getById(dto.getGroupId());
        if (group == null) {
            if (!shared) {
                throw new GlobalException("group.groupNotExist");
            }
            GroupVO sharedGroup = zhiquanChatStore.group(dto.getGroupId());
            group = new Group();
            group.setId(dto.getGroupId());
            group.setName(sharedGroup == null ? "群聊" : sharedGroup.getName());
            group.setOwnerId(sharedGroup == null ? null : sharedGroup.getOwnerId());
            group.setIsAllMuted(false);
            group.setIsAllowShareCard(false);
        } else {
            group = groupService.getAndCheckById(dto.getGroupId());
        }
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        boolean isOwner = group.getOwnerId() != null && session.getUserId().equals(group.getOwnerId());
        if (isOwner && (Objects.isNull(member) || Boolean.TRUE.equals(member.getQuit()))) {
            groupService.ensureLiveGroupOwnerMember(group.getId(), session.getUserId(), null);
            member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        }
        if (Boolean.TRUE.equals(group.getIsAllMuted()) && !isOwner && (Objects.isNull(member) || !member.getIsManager())) {
            throw new GlobalException("groupMsg.muteAll");
        }
        // 共享聊天室里，没进群的登录用户也可以发言
        if (Objects.isNull(member) || member.getQuit()) {
            if (!shared) {
                throw new GlobalException("groupMsg.notInGroup");
            }
            member = guestSpeaker(session, dto.getGroupId());
        }
        if (member.getIsMuted()) {
            throw new GlobalException("groupMsg.selfMuted");
        }
        // 名片消息权限校验
        if (dto.getType().equals(MessageType.GROUP_CARD.code())) {
            if (!group.getIsAllowShareCard() && !isOwner && !member.getIsManager()) {
                throw new GlobalException("groupMsg.noShareCard");
            }
        }
        // 群聊成员列表
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(group.getId());
        // 不用发给自己。打开过这个共享房间的人一起收，这样在线的人马上看得到
        userIds = userIds.stream().filter(id -> !session.getUserId().equals(id)).collect(Collectors.toList());
        if (shared) {
            zhiquanChatStore.touchViewer(dto.getGroupId(), session.getUserId());
            for (Long viewerId : zhiquanChatStore.viewers(dto.getGroupId())) {
                if (!session.getUserId().equals(viewerId) && !userIds.contains(viewerId)) {
                    userIds.add(viewerId);
                }
            }
        }
        // 保存消息
        GroupMessage message = BeanUtils.copyProperties(dto, GroupMessage.class);
        message.setSendId(session.getUserId());
        message.setSendTime(new Date());
        message.setSendNickName(member.getShowNickName());
        message.setAtUserIds(CommaTextUtils.asText(dto.getAtUserIds()));
        message.setStatus(MessageStatus.PENDING.code());
        // 过滤内容中的敏感词
        if (MessageType.TEXT.code().equals(dto.getType())) {
            message.setContent(sensitiveFilterUtil.filter(dto.getContent()));
        }
        // 保存消息(走代理触发分布式锁)
        GroupMessageServiceImpl proxy = (GroupMessageServiceImpl)AopContext.currentProxy();
        proxy.saveMessage(message);
        // 群发
        GroupMessageVO vo = BeanUtils.copyProperties(message, GroupMessageVO.class);
        vo.setAtUserIds(dto.getAtUserIds());
        // 填充引用消息
        if (!Objects.isNull(dto.getQuoteMessageId())) {
            GroupMessage quoteMessage = this.getById(dto.getQuoteMessageId());
            QuoteMessageVO quoteMessageVo = BeanUtils.copyProperties(quoteMessage, QuoteMessageVO.class);
            fillQuoteMessage(vo, quoteMessageVo);
        }
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvIds(userIds);
        sendMessage.setData(vo);
        imClient.sendGroupMessage(sendMessage);
        if (shared) {
            zhiquanChatStore.remember(vo);
        }
        String ip = JakartaServletUtil.getClientIP(request);
        log.info("发送群聊消息,ip:{},发送id:{},群聊id:{},内容:{}", ip, session.getUserId(), dto.getGroupId(),
            dto.getContent());
        return vo;
    }

    @Transactional
    @Override
    public GroupMessageVO recallMessage(Long id) {
        UserSession session = SessionContext.getSession();
        GroupMessage recallMessage = this.getById(id);
        if (Objects.isNull(recallMessage)) {
            throw new GlobalException("groupMsg.messageNotExist");
        }
        // 判断是否在群里。复制群里没入群的人只能撤回自己刚发的那条。
        GroupMember member = groupMemberService.findByGroupAndUserId(recallMessage.getGroupId(), session.getUserId());
        boolean guest = false;
        if (Objects.isNull(member) || Boolean.TRUE.equals(member.getQuit())) {
            if (zhiquanChatStore.contains(recallMessage.getGroupId())) {
                member = guestSpeaker(session, recallMessage.getGroupId());
                guest = true;
            } else {
                throw new GlobalException("groupMsg.notInGroup");
            }
        }
        Group group = groupService.getById(recallMessage.getGroupId());
        boolean isSender = recallMessage.getSendId().equals(session.getUserId());
        boolean isOwner = group != null && session.getUserId().equals(group.getOwnerId());
        if (guest) {
            if (!isSender) {
                throw new GlobalException("groupMsg.noRecallPermission");
            }
        } else if (!isSender && !isOwner && !Boolean.TRUE.equals(member.getIsManager())) {
            throw new GlobalException("groupMsg.noRecallPermission");
        }
        long time = System.currentTimeMillis() - recallMessage.getSendTime().getTime();
        if (isSender && time > IMConstant.ALLOW_RECALL_SECOND * 1000) {
            throw new GlobalException("groupMsg.recallTimeout");
        }
        String recallerMark = formatUserMark(member.getUserId(), member.getShowNickName());
        String senderMark = formatUserMark(recallMessage.getSendId(), recallMessage.getSendNickName());
        String recallTip;
        if (session.getUserId().equals(recallMessage.getSendId())) {
            recallTip = TipContentUtil.buildTipContent("tip.recall.self", recallerMark);
        } else {
            recallTip = TipContentUtil.buildTipContent("tip.recall.byOther", recallerMark, senderMark);
        }
        // 修改数据库
        recallMessage.setStatus(MessageStatus.RECALL.code());
        this.updateById(recallMessage);
        // 生成一条撤回消息
        GroupMessage message = new GroupMessage();
        message.setLocalId(IdWorker.getIdStr());
        message.setStatus(MessageStatus.PENDING.code());
        message.setType(MessageType.RECALL.code());
        message.setGroupId(recallMessage.getGroupId());
        message.setSendId(session.getUserId());
        message.setSendNickName(member.getShowNickName());
        HashMap contentMap = new HashMap();
        contentMap.put("id", id);
        contentMap.put("tip", recallTip);
        message.setContent(JSON.toJSONString(contentMap));
        message.setSendTime(new Date());
        // 保存消息(走代理触发分布式锁)
        GroupMessageServiceImpl proxy = (GroupMessageServiceImpl)AopContext.currentProxy();
        proxy.saveMessage(message);
        // 群发
        List<Long> userIds = new ArrayList<>(groupMemberService.findUserIdsByGroupId(message.getGroupId()));
        GroupMessageVO msgInfo = BeanUtils.copyProperties(message, GroupMessageVO.class);
        if (zhiquanChatStore.contains(message.getGroupId())) {
            zhiquanChatStore.markRecalled(message.getGroupId(), id);
            HashMap<String, String> gatewayRecall = new HashMap<>();
            gatewayRecall.put("uid", String.valueOf(session.getUserId()));
            gatewayRecall.put("nickName", member.getShowNickName() == null ? "" : member.getShowNickName());
            gatewayRecall.put("beUid", recallMessage.getSendId() == null ? "" : String.valueOf(recallMessage.getSendId()));
            gatewayRecall.put("beNickName", recallMessage.getSendNickName() == null ? "" : recallMessage.getSendNickName());
            gatewayRecall.put("fpForMessage", recallMessage.getLocalId() == null ? "" : recallMessage.getLocalId());
            zhiquanChatStore.recallOnGateway(recallMessage.getGroupId(), recallMessage.getSendTime(),
                recallMessage.getLocalId(), JSON.toJSONString(gatewayRecall),
                member.getShowNickName(), String.valueOf(session.getUserId()));
            for (Long viewerId : zhiquanChatStore.viewers(message.getGroupId())) {
                if (viewerId != null && !userIds.contains(viewerId)) {
                    userIds.add(viewerId);
                }
            }
        }
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvIds(userIds);
        sendMessage.setData(msgInfo);
        imClient.sendGroupMessage(sendMessage);
        log.info("撤回群聊消息，发送id:{},群聊id:{},内容:{}", session.getUserId(), message.getGroupId(),
            message.getContent());
        return msgInfo;
    }

    @Override
    public List<GroupMessageVO> loadOffineMessage(Long minId) {
        long time = System.currentTimeMillis();
        UserSession session = SessionContext.getSession();
        List<GroupMessage> messages = new ArrayList<>();
        // 查询用户加入的群组
        List<GroupMember> members = groupMemberService.findByUserId(session.getUserId());
        Set<Long> groupIds = members.stream().map(GroupMember::getGroupId).collect(Collectors.toSet());
        for (Group group : groupService.listPublicGroups()) {
            if (groupIds.add(group.getId())) {
                members.add(groupService.publicReaderMember(group.getId()));
            }
        }
        Set<Long> sharedIds = sharedGroupIds(session.getUserId());
        if (!sharedIds.isEmpty()) {
            groupIds.removeAll(sharedIds);
            members.removeIf(member -> member.getGroupId() != null && sharedIds.contains(member.getGroupId()));
        }
        int perGroup = zhiquanChatStore.offlineLimit();
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        if (!groupIds.isEmpty()) {
            for (Long groupId : groupIds) {
                LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
                wrapper.eq(GroupMessage::getGroupId, groupId);
                wrapper.gt(GroupMessage::getId, minId);
                wrapper.gt(GroupMessage::getSendTime, minDate);
                wrapper.orderByDesc(GroupMessage::getId);
                wrapper.last("limit " + perGroup);
                messages.addAll(this.list(wrapper));
            }
        }
        // 查询退群前的消息
        Date minQuitTime = minDate;
        if (minId > 0) {
            // 如果某个群的退群时间大于起始消息的发送时间，那消息是不用推送的，过滤掉
            GroupMessage message = this.getById(minId);
            if (!Objects.isNull(message) && message.getSendTime().compareTo(minDate) > 0) {
                minQuitTime = message.getSendTime();
            }
        }
        List<GroupMessage> quitMessages = Collections.synchronizedList(new ArrayList<>());
        List<GroupMember> quitMembers = groupMemberService.findQuitMembers(session.getUserId(), minQuitTime);
        if (!sharedIds.isEmpty()) {
            quitMembers = quitMembers.stream()
                .filter(member -> !sharedIds.contains(member.getGroupId()))
                .collect(Collectors.toList());
        }
        quitMembers.parallelStream().forEach(quitMember -> {
            LambdaQueryWrapper<GroupMessage> quitWrapper = Wrappers.lambdaQuery();
            quitWrapper.gt(GroupMessage::getId, minId);
            quitWrapper.between(GroupMessage::getSendTime, minDate, quitMember.getQuitTime());
            quitWrapper.eq(GroupMessage::getGroupId, quitMember.getGroupId());
            quitWrapper.orderByDesc(GroupMessage::getId);
            quitWrapper.last("limit " + perGroup);
            List<GroupMessage> groupMessages = this.list(quitWrapper);
            quitMessages.addAll(groupMessages);
        });
        messages.addAll(quitMessages);
        members.addAll(quitMembers);
        // 已经删除的消息
        List<MessageDeletion> deletions =
            messageDeletionService.findByChatType(session.getUserId(), ChatType.GROUP.getCode());
        // 整个会话删掉的消息就不再推送了
        messages = messages.stream().filter(m -> !isDeleteChat(m, deletions)).collect(Collectors.toList());
        // 转成map方便提取
        Map<Long, GroupMember> groupMemberMap = CollStreamUtil.toIdentityMap(members, GroupMember::getGroupId);
        // 通过群聊对消息进行分组
        Map<Long, List<GroupMessage>> messageGroupMap =
            messages.stream().collect(Collectors.groupingBy(GroupMessage::getGroupId));
        // 引用消息
        Map<Long, QuoteMessageVO> quoteMessageMap = batchLoadQuoteMessage(messages);
        List<GroupMessageVO> vos = new LinkedList<>();
        for (Map.Entry<Long, List<GroupMessage>> entry : messageGroupMap.entrySet()) {
            Long groupId = entry.getKey();
            List<GroupMessage> groupMessages = entry.getValue();
            // 填充消息状态
            String key = StrUtil.join(":", RedisKey.IM_GROUP_READED_POSITION, groupId);
            Object o = redisTemplate.opsForHash().get(key, session.getUserId().toString());
            long readedMaxId = Objects.isNull(o) ? -1 : Long.parseLong(o.toString());
            Map<Object, Object> maxIdMap = null;
            for (GroupMessage m : groupMessages) {
                // 排除加群之前的消息
                GroupMember member = groupMemberMap.get(m.getGroupId());
                if (DateUtil.compare(member.getCreatedTime(), m.getSendTime()) > 0) {
                    continue;
                }
                // 组装vo
                GroupMessageVO vo = BeanUtils.copyProperties(m, GroupMessageVO.class);
                // 标记已经删除的消息
                vo.setDeleted(isDeleteMessage(m, deletions));
                // 引用消息
                fillQuoteMessage(vo, quoteMessageMap.get(m.getQuoteMessageId()));
                // 被@用户列表
                List<String> atIds = CommaTextUtils.asList(m.getAtUserIds());
                vo.setAtUserIds(atIds.stream().map(Long::parseLong).collect(Collectors.toList()));
                // 填充状态
                vo.setStatus(readedMaxId >= m.getId() ? MessageStatus.READED.code() : MessageStatus.PENDING.code());
                // 针对回执消息填充已读人数
                if (m.getReceipt() && m.getSendId().equals(session.getUserId())) {
                    if (Objects.isNull(maxIdMap)) {
                        maxIdMap = redisTemplate.opsForHash().entries(key);
                    }
                    int count = getReadedUserIds(maxIdMap, m.getId(), m.getSendId()).size();
                    vo.setReadedCount(count);
                }
                vos.add(vo);
            }
        }
        if (!sharedIds.isEmpty()) {
            for (Long sharedGroupId : sharedIds) {
                zhiquanChatStore.touchViewer(sharedGroupId, session.getUserId());
            }
            vos.addAll(markSharedDeletions(zhiquanChatStore.offlineExcept(groupIds), deletions));
        }
        log.info("拉取离线群聊消息,用户id:{},数量:{},耗时:{},minId:{}", session.getUserId(), vos.size(),
            System.currentTimeMillis() - time, minId);
        // 排序
        return vos.stream().sorted(Comparator.comparing(GroupMessageVO::getId)).collect(Collectors.toList());
    }

    @Override
    public void readedMessage(Long groupId, Long messageId) {
        UserSession session = SessionContext.getSession();
        // 如果前端没有传消息id,取出最后一条消息id
        if (Objects.isNull(messageId)) {
            messageId = findMaxMessageId(groupId);
            if (messageId < 0) {
                return;
            }
        }
        // 推送消息给自己的其他终端,同步清空会话列表中的未读数量
        GroupMessageVO msgInfo = new GroupMessageVO();
        msgInfo.setType(MessageType.READED.code());
        msgInfo.setSendTime(new Date());
        msgInfo.setSendId(session.getUserId());
        msgInfo.setGroupId(groupId);
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setSendToSelf(true);
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        imClient.sendGroupMessage(sendMessage);
        // 已读消息key
        String key = StrUtil.join(":", RedisKey.IM_GROUP_READED_POSITION, groupId);
        // 原来的已读消息位置
        Object val = redisTemplate.opsForHash().get(key, session.getUserId().toString());
        Long maxReadedId = Objects.isNull(val) ? 0 : Long.parseLong(val.toString());
        if (messageId.compareTo(maxReadedId) <= 0) {
            return;
        }
        // 记录已读消息位置
        redisTemplate.opsForHash().put(key, session.getUserId().toString(), messageId);
        // 推送消息回执，刷新已读人数显示
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMessage::getGroupId, groupId);
        wrapper.gt(GroupMessage::getId, maxReadedId);
        wrapper.le(GroupMessage::getId, messageId);
        wrapper.ne(GroupMessage::getStatus, MessageStatus.RECALL.code());
        wrapper.eq(GroupMessage::getReceipt, true);
        wrapper.last("limit 10");
        List<GroupMessage> receiptMessages = this.list(wrapper);
        if (CollectionUtil.isNotEmpty(receiptMessages)) {
            List<Long> userIds = groupMemberService.findUserIdsByGroupId(groupId);
            Map<Object, Object> maxIdMap = redisTemplate.opsForHash().entries(key);
            for (GroupMessage receiptMessage : receiptMessages) {
                int readedCount = getReadedUserIds(maxIdMap, receiptMessage.getId(), receiptMessage.getSendId()).size();
                // 如果所有人都已读，记录回执消息完成标记
                if (readedCount >= userIds.size() - 1) {
                    receiptMessage.setReceiptOk(true);
                    this.updateById(receiptMessage);
                }
                // 推送给回执消息发送方，更新已读人数
                msgInfo = new GroupMessageVO();
                msgInfo.setId(receiptMessage.getId());
                msgInfo.setLocalId(receiptMessage.getLocalId());
                msgInfo.setGroupId(groupId);
                msgInfo.setReadedCount(readedCount);
                msgInfo.setReceiptOk(receiptMessage.getReceiptOk());
                msgInfo.setType(MessageType.RECEIPT.code());
                sendMessage = new IMGroupMessage<>();
                sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
                sendMessage.setRecvIds(List.of(receiptMessage.getSendId()));
                sendMessage.setData(msgInfo);
                sendMessage.setSendToSelf(false);
                sendMessage.setSendResult(false);
                imClient.sendGroupMessage(sendMessage);
            }
        }
    }

    @Override
    public List<Long> findReadedUsers(Long groupId, Long messageId) {
        UserSession session = SessionContext.getSession();
        GroupMessage message = this.getById(messageId);
        if (Objects.isNull(message)) {
            throw new GlobalException("groupMsg.messageNotExist");
        }
        // 是否在群聊里面
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, session.getUserId());
        if (Objects.isNull(member) || member.getQuit()) {
            throw new GlobalException("groupMsg.notInGroup");
        }
        // 已读位置key
        String key = StrUtil.join(":", RedisKey.IM_GROUP_READED_POSITION, groupId);
        // 一次获取所有用户的已读位置
        Map<Object, Object> maxIdMap = redisTemplate.opsForHash().entries(key);
        // 返回已读用户的id集合
        return getReadedUserIds(maxIdMap, message.getId(), message.getSendId());
    }

    @Override
    public void deleteMessage(MessageDeleteDTO dto) {
        UserSession session = SessionContext.getSession();
        messageDeletionService.deleteByMessage(session.getUserId(), ChatType.GROUP.getCode(), dto.getChatId(),
            dto.getMessageIds());
    }

    @Override
    public void deleteChat(ChatDeleteDTO dto) {
        UserSession session = SessionContext.getSession();
        // 获取会话中最后一条消息
        Long maxMessageId = findMaxMessageId(dto.getChatId());
        if (maxMessageId < 0) {
            return;
        }
        // 保存删除记录
        messageDeletionService.deleteByChat(session.getUserId(), ChatType.GROUP.getCode(), dto.getChatId(),
            maxMessageId);
    }

    @Override
    public void publishToShared(GroupMessageVO vo) {
        if (vo == null || vo.getGroupId() == null || !zhiquanChatStore.contains(vo.getGroupId())) {
            return;
        }
        zhiquanChatStore.remember(vo);
        Set<Long> members = new HashSet<>(groupMemberService.findUserIdsByGroupId(vo.getGroupId()));
        List<Long> viewers = new ArrayList<>();
        for (Long viewerId : zhiquanChatStore.viewers(vo.getGroupId())) {
            if (viewerId == null || viewerId.equals(vo.getSendId()) || members.contains(viewerId)) {
                continue;
            }
            viewers.add(viewerId);
        }
        if (viewers.isEmpty()) {
            return;
        }
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(vo.getSendId() == null ? 0L : vo.getSendId(), IMTerminalType.WEB.code()));
        sendMessage.setRecvIds(viewers);
        sendMessage.setData(vo);
        sendMessage.setSendToSelf(false);
        sendMessage.setSendResult(false);
        imClient.sendGroupMessage(sendMessage);
    }

    @Override
    public void recordRedpacketClaim(Long groupId, Long userId, String nick) {
        // 原站聊天不展示谁领了红包。
    }

    /**
     * 保存消息 加分布式锁是为了让数据库自增id和seq_no保持同序
     */
    @Override
    @RedisLock(prefixKey = RedisKey.IM_LOCK_GROUP_MESSAGE_SAVE, key = "#message.groupId")
    public void saveMessage(GroupMessage message) {
        if (StrUtil.isEmpty(message.getLocalId())) {
            message.setLocalId(IdWorker.getIdStr());
        }
        message.setSeqNo(getNextSeqNo(message.getGroupId()));
        save(message);
        // 记录消息最大id
        String key = StrUtil.join(":", RedisKey.IM_GROUP_MESSAGE_MAX_ID, message.getGroupId());
        redisTemplate.opsForValue().set(key, message.getId(), Constant.MAX_OFFLINE_MESSAGE_DAYS, TimeUnit.DAYS);
    }

    @Override
    public List<GroupMessageVO> loadHistoryMessage(GroupMessageHistoryDTO dto) {
        UserSession session = SessionContext.getSession();
        if (zhiquanChatStore.serveTo(session.getUserId()) && zhiquanChatStore.contains(dto.getGroupId())) {
            zhiquanChatStore.touchViewer(dto.getGroupId(), session.getUserId());
            List<MessageDeletion> deletions =
                messageDeletionService.findByChatIdAndType(session.getUserId(), ChatType.GROUP.getCode(), dto.getGroupId());
            return markSharedDeletions(zhiquanChatStore.history(dto), deletions);
        }
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        if (Objects.isNull(member)) {
            member = groupService.publicReaderMember(dto.getGroupId());
        }
        if (Objects.isNull(member)) {
            throw new GlobalException("groupMsg.notInGroup");
        }
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMessage::getGroupId, dto.getGroupId());
        if (CollectionUtil.isNotEmpty(dto.getLocalIds())) {
            wrapper.in(GroupMessage::getLocalId, dto.getLocalIds());
        } else if (CollectionUtil.isNotEmpty(dto.getSeqNos())) {
            wrapper.in(GroupMessage::getSeqNo, dto.getSeqNos());
        } else if (!Objects.isNull(dto.getMaxSeqNo()) && !Objects.isNull(dto.getMinSeqNo())) {
            wrapper.between(GroupMessage::getSeqNo, dto.getMinSeqNo(), dto.getMaxSeqNo());
        }
        // 退群后的消息不再拉取
        if (member.getQuit()) {
            wrapper.le(GroupMessage::getSendTime, member.getQuitTime());
        }
        // 普通群不拉进群前和60天前的消息；公开群保留导入的历史
        Group group = groupService.getById(dto.getGroupId());
        if (group == null || !Boolean.TRUE.equals(group.getIsPublic())) {
            Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
            minDate = minDate.after(member.getCreatedTime()) ? minDate : member.getCreatedTime();
            wrapper.ge(GroupMessage::getSendTime, minDate);
        }
        wrapper.orderByAsc(GroupMessage::getSeqNo);
        wrapper.last("limit 100");
        List<GroupMessage> messages = this.list(wrapper);
        if (CollectionUtil.isEmpty(messages)) {
            return new ArrayList<>();
        }
        List<MessageDeletion> deletions =
            messageDeletionService.findByChatIdAndType(session.getUserId(), ChatType.GROUP.getCode(), dto.getGroupId());
        // 整个会话删掉的消息就不再推送了
        messages = messages.stream().filter(m -> !isDeleteChat(m, deletions)).collect(Collectors.toList());
        // 提取所有引用消息
        Map<Long, QuoteMessageVO> quoteMessageMap = batchLoadQuoteMessage(messages);
        // 填充消息状态
        String key = StrUtil.join(":", RedisKey.IM_GROUP_READED_POSITION, dto.getGroupId());
        Object o = redisTemplate.opsForHash().get(key, session.getUserId().toString());
        long readedMaxId = Objects.isNull(o) ? -1 : Long.parseLong(o.toString());
        Map<Object, Object> maxIdMap = redisTemplate.opsForHash().entries(key);
        List<GroupMessageVO> vos = new ArrayList<>();
        // 转换vo
        for (GroupMessage m : messages) {
            GroupMessageVO vo = BeanUtils.copyProperties(m, GroupMessageVO.class);
            // 标记已经删除的消息
            vo.setDeleted(isDeleteMessage(m, deletions));
            fillQuoteMessage(vo, quoteMessageMap.get(m.getQuoteMessageId()));
            // 被@用户列表
            List<String> atIds = CommaTextUtils.asList(m.getAtUserIds());
            vo.setAtUserIds(atIds.stream().map(Long::parseLong).collect(Collectors.toList()));
            // 填充状态
            if (MessageStatus.PENDING.code().equals(m.getStatus()) && readedMaxId >= m.getId()) {
                vo.setStatus(MessageStatus.READED.code());
            }
            // 回执消息填充已读人数
            if (m.getReceipt() && m.getSendId().equals(session.getUserId())) {
                int count = getReadedUserIds(maxIdMap, m.getId(), m.getSendId()).size();
                vo.setReadedCount(count);
            }
            vos.add(vo);
        }
        return vos;
    }

    @Override
    public List<GroupMessageVO> loadGuestMessages(Long groupId, int limit) {
        groupService.getAndCheckById(groupId);
        int size = Math.min(Math.max(limit, 1), 100);
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMessage::getGroupId, groupId);
        wrapper.eq(GroupMessage::getType, MessageType.TEXT.code());
        wrapper.ne(GroupMessage::getStatus, MessageStatus.RECALL.code());
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        wrapper.ge(GroupMessage::getSendTime, minDate);
        wrapper.orderByDesc(GroupMessage::getId);
        wrapper.last("limit " + size);
        List<GroupMessage> messages = this.list(wrapper);
        if (CollectionUtil.isEmpty(messages)) {
            return new ArrayList<>();
        }
        Collections.reverse(messages);
        return messages.stream().map(m -> {
            GroupMessageVO vo = BeanUtils.copyProperties(m, GroupMessageVO.class);
            vo.setAtUserIds(Collections.emptyList());
            return vo;
        }).collect(Collectors.toList());
    }

    private static final String LIVE_PROMO_NICK = "系统推广";

    @Override
    public GroupMessageVO sendLivePromoMessage(Long groupId, String contentHtml) {
        Group group = groupService.getAndCheckById(groupId);
        Long senderId = group.getOwnerId();
        String payload = JSON.toJSONString(Map.of("_livePromo", true, "html", contentHtml));

        GroupMessage message = new GroupMessage();
        message.setLocalId(IdWorker.getIdStr());
        message.setGroupId(groupId);
        message.setSendId(senderId);
        message.setSendNickName(LIVE_PROMO_NICK);
        message.setContent(payload);
        message.setType(MessageType.TEXT.code());
        message.setStatus(MessageStatus.PENDING.code());
        message.setReceipt(Boolean.FALSE);
        message.setReceiptOk(Boolean.FALSE);
        message.setSendTime(new Date());
        GroupMessageServiceImpl proxy = (GroupMessageServiceImpl) AopContext.currentProxy();
        proxy.saveMessage(message);

        List<Long> recvIds = groupMemberService.findUserIdsByGroupId(groupId);

        GroupMessageVO vo = BeanUtils.copyProperties(message, GroupMessageVO.class);
        vo.setAtUserIds(Collections.emptyList());

        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(senderId, IMTerminalType.WEB.code()));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(vo);
        sendMessage.setSendToSelf(true);
        imClient.sendGroupMessage(sendMessage);
        log.info("推送直播间推广文案 groupId={} messageId={}", groupId, message.getId());
        return vo;
    }

    private static final String LIVE_SYSTEM_NICK = "系统";

    @Override
    public GroupMessageVO sendLivePresenceMessage(Long groupId, String nick, String action, Integer viewerCount) {
        Group group = groupService.getAndCheckById(groupId);
        Long senderId = group.getOwnerId();
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("_livePresence", action);
        payloadMap.put("nick", nick);
        if (viewerCount != null && viewerCount >= 0) {
            payloadMap.put("viewerCount", viewerCount);
        }
        String payload = JSON.toJSONString(payloadMap);

        GroupMessage message = new GroupMessage();
        message.setLocalId(IdWorker.getIdStr());
        message.setGroupId(groupId);
        message.setSendId(senderId);
        message.setSendNickName(LIVE_SYSTEM_NICK);
        message.setContent(payload);
        message.setType(MessageType.TEXT.code());
        message.setStatus(MessageStatus.PENDING.code());
        message.setReceipt(Boolean.FALSE);
        message.setReceiptOk(Boolean.FALSE);
        message.setSendTime(new Date());
        GroupMessageServiceImpl proxy = (GroupMessageServiceImpl) AopContext.currentProxy();
        proxy.saveMessage(message);

        List<Long> recvIds = groupMemberService.findUserIdsByGroupId(groupId);

        GroupMessageVO vo = BeanUtils.copyProperties(message, GroupMessageVO.class);
        vo.setAtUserIds(Collections.emptyList());

        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(senderId, IMTerminalType.WEB.code()));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(vo);
        sendMessage.setSendToSelf(true);
        imClient.sendGroupMessage(sendMessage);
        log.info("推送直播间进出提示 groupId={} action={} nick={} messageId={}", groupId, action, nick, message.getId());
        return vo;
    }

    @Override
    public GroupMessageVO sendLiveGuestMessage(Long groupId, String guestNick, String content) {
        Group group = groupService.getAndCheckById(groupId);
        Long senderId = group.getOwnerId();
        String nick = guestNick.trim();
        if (nick.length() > 16) {
            nick = nick.substring(0, 16);
        }
        String text = content.trim();
        if (text.length() > 200) {
            text = text.substring(0, 200);
        }

        GroupMessage message = new GroupMessage();
        message.setLocalId(IdWorker.getIdStr());
        message.setGroupId(groupId);
        message.setSendId(senderId);
        message.setSendNickName(nick);
        message.setContent(text);
        message.setType(MessageType.TEXT.code());
        message.setStatus(MessageStatus.PENDING.code());
        message.setReceipt(Boolean.FALSE);
        message.setReceiptOk(Boolean.FALSE);
        message.setSendTime(new Date());
        GroupMessageServiceImpl proxy = (GroupMessageServiceImpl) AopContext.currentProxy();
        proxy.saveMessage(message);

        List<Long> recvIds = groupMemberService.findUserIdsByGroupId(groupId);

        GroupMessageVO vo = BeanUtils.copyProperties(message, GroupMessageVO.class);
        vo.setAtUserIds(Collections.emptyList());

        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(senderId, IMTerminalType.WEB.code()));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(vo);
        sendMessage.setSendToSelf(true);
        imClient.sendGroupMessage(sendMessage);
        log.info("推送直播间游客发言 groupId={} nick={} messageId={}", groupId, nick, message.getId());
        return vo;
    }

    private List<Long> getReadedUserIds(Map<Object, Object> maxIdMap, Long messageId, Long sendId) {
        List<Long> userIds = new LinkedList<>();
        maxIdMap.forEach((k, v) -> {
            Long userId = Long.valueOf(k.toString());
            long maxId = Long.parseLong(v.toString());
            // 发送者不计入已读人数
            if (!sendId.equals(userId) && maxId >= messageId) {
                userIds.add(userId);
            }
        });
        return userIds;
    }

    private Map<Long, QuoteMessageVO> batchLoadQuoteMessage(List<GroupMessage> messages) {
        // 提取列表中所有引用消息
        List<Long> ids = messages.stream().map(GroupMessage::getQuoteMessageId)
            .filter(quoteMessageId -> !Objects.isNull(quoteMessageId)).collect(Collectors.toList());
        if (CollectionUtil.isEmpty(ids)) {
            return new HashMap<>();
        }
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.in(GroupMessage::getId, ids);
        List<GroupMessage> quoteMessages = this.list(wrapper);
        // 转为vo
        return quoteMessages.stream()
            .collect(Collectors.toMap(GroupMessage::getId, m -> BeanUtils.copyProperties(m, QuoteMessageVO.class)));
    }

    private void validMessage(GroupMessageDTO dto) {
        // 只允许用户发送普通消息
        if (dto.getType() < MessageType.TEXT.code() || dto.getType() > MessageType.MERGE_FORWARD.code()) {
            throw new GlobalException("groupMsg.typeInvalid");
        }
        // 文字消息-长度校验
        if (MessageType.TEXT.code().equals(dto.getType()) && dto.getContent().length() > Constant.MAX_MESSAGE_LENGTH) {
            throw new GlobalException("groupMsg.lengthExceed", Constant.MAX_MESSAGE_LENGTH);
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

    private GroupMember guestSpeaker(UserSession session, Long groupId) {
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(session.getUserId());
        member.setQuit(false);
        member.setIsMuted(false);
        member.setIsManager(false);
        String nick = StrUtil.blankToDefault(session.getNickName(), session.getUserName());
        member.setUserNickName(StrUtil.blankToDefault(nick, "用户"));
        return member;
    }

    private Set<Long> sharedGroupIds(Long userId) {
        if (!zhiquanChatStore.serveTo(userId)) {
            return Set.of();
        }
        return zhiquanChatStore.groupsExcept(Set.of()).stream().map(GroupVO::getId).collect(Collectors.toSet());
    }

    private List<GroupMessageVO> markSharedDeletions(List<GroupMessageVO> messages, List<MessageDeletion> deletions) {
        if (messages == null || messages.isEmpty() || deletions == null || deletions.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        List<GroupMessageVO> kept = new ArrayList<>();
        for (GroupMessageVO vo : messages) {
            if (sharedChatDeleted(vo, deletions)) {
                continue;
            }
            vo.setDeleted(sharedMessageDeleted(vo, deletions));
            kept.add(vo);
        }
        return kept;
    }

    private boolean sharedChatDeleted(GroupMessageVO message, List<MessageDeletion> deletions) {
        return deletions.stream().anyMatch(deletion -> message.getGroupId().equals(deletion.getChatId())
            && DeleteType.BY_CHAT.getCode().equals(deletion.getDeleteType())
            && deletion.getMessageId() != null
            && deletion.getMessageId() >= message.getId());
    }

    private boolean sharedMessageDeleted(GroupMessageVO message, List<MessageDeletion> deletions) {
        return deletions.stream().anyMatch(deletion -> message.getGroupId().equals(deletion.getChatId())
            && DeleteType.BY_MESSAGE.getCode().equals(deletion.getDeleteType())
            && message.getId().equals(deletion.getMessageId()));
    }

    private Boolean isDeleteMessage(GroupMessage message, List<MessageDeletion> deletions) {
        return deletions.stream().anyMatch(deletion -> {
            if (!message.getGroupId().equals(deletion.getChatId())) {
                return false;
            }
            if (DeleteType.BY_MESSAGE.getCode().equals(deletion.getDeleteType())) {
                return message.getId().equals(deletion.getMessageId());
            }
            return false;
        });
    }

    private Boolean isDeleteChat(GroupMessage message, List<MessageDeletion> deletions) {
        return deletions.stream().anyMatch(deletion -> {
            if (!message.getGroupId().equals(deletion.getChatId())) {
                return false;
            }
            if (DeleteType.BY_CHAT.getCode().equals(deletion.getDeleteType())) {
                return deletion.getMessageId() >= message.getId();
            }
            return false;
        });
    }

    private void fillQuoteMessage(GroupMessageVO message, QuoteMessageVO quoteMessage) {
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

    private String formatUserMark(Long userId, String displayName) {
        // 格式：#{displayName:userId}，例如：#{张三:123}
        return String.format("#{%s:%d}", displayName, userId);
    }

    private Long getNextSeqNo(Long groupId) {
        String key = StrUtil.join(":", RedisKey.IM_GROUP_MESSAGE_MAX_SEQ, groupId);
        Long seqNo = redisTemplate.opsForValue().increment(key);
        if (seqNo > 1L) {
            return seqNo;
        }
        redisTemplate.delete(key);
        RLock lock = redissonClient.getLock(RedisKey.IM_LOCK_GROUP_MESSAGE_MAX_SEQ);
        lock.lock();
        try {
            //Double check
            if (redisTemplate.hasKey(key)) {
                return redisTemplate.opsForValue().increment(key);
            }
            LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(GroupMessage::getGroupId, groupId);
            wrapper.orderByDesc(GroupMessage::getSeqNo);
            wrapper.last("limit 1");
            GroupMessage lastMessage = this.getOne(wrapper);
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

    Long findMaxMessageId(Long groupId) {
        String key = StrUtil.join(":", RedisKey.IM_GROUP_MESSAGE_MAX_ID, groupId);
        Object id = redisTemplate.opsForValue().get(key);
        if (!Objects.isNull(id)) {
            return Long.parseLong(id.toString());
        }
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMessage::getGroupId, groupId);
        wrapper.orderByDesc(GroupMessage::getId);
        wrapper.last("limit 1");
        GroupMessage message = this.getOne(wrapper);
        if (!Objects.isNull(message)) {
            redisTemplate.opsForValue().set(key, message.getId());
            return message.getId();
        }
        return -1L;
    }

    List<GroupMessage> appendLastMessageInConversation(Set<Long> groupIds, List<GroupMessage> messages, Long minId) {
        groupIds = new HashSet<>(groupIds);
        Set<Long> existIds = messages.stream().map(GroupMessage::getGroupId).collect(Collectors.toSet());
        // 移除已经拉到消息的会话
        groupIds.removeAll(existIds);
        if (groupIds.isEmpty()) {
            return messages;
        }
        List<String> keys = groupIds.stream().map(this::buildMaxMessageIdKey).collect(Collectors.toList());
        List<Object> maxMessageIds = redisTemplate.opsForValue().multiGet(keys);
        maxMessageIds =
            maxMessageIds.stream().filter(id -> !Objects.isNull(id) && Long.parseLong(id.toString()) > minId)
                .collect(Collectors.toList());
        if (maxMessageIds.isEmpty()) {
            return messages;
        }
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        wrapper.ge(GroupMessage::getSendTime, minDate);
        wrapper.in(GroupMessage::getId, maxMessageIds);
        List<GroupMessage> lastMessages = this.list(wrapper);
        messages.addAll(lastMessages);
        return messages;
    }

    private String buildMaxMessageIdKey(Long groupId) {
        return StrUtil.join(":", RedisKey.IM_GROUP_MESSAGE_MAX_ID, groupId);
    }

}
