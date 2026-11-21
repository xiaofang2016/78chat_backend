package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.bx.imclient.IMClient;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.IMGroupMessage;
import com.bx.imcommon.model.IMUserInfo;
import com.bx.implatform.config.props.BoximIntegrationProperties;
import com.bx.implatform.entity.Group;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.entity.GroupMessage;
import com.bx.implatform.entity.Redpacket;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.service.GroupMemberService;
import com.bx.implatform.service.GroupMessageService;
import com.bx.implatform.service.RedpacketBotService;
import com.bx.implatform.service.UserService;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.external.RedpacketCardVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 红包机器人服务实现：用 bot user_id 在群里发 REDPACKET 类型消息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedpacketBotServiceImpl implements RedpacketBotService {

    private final BoximIntegrationProperties props;
    private final UserService userService;
    private final GroupMemberService groupMemberService;
    private final GroupMessageService groupMessageService;
    private final IMClient imClient;

    @Override
    @Transactional
    public void ensureBotInGroup(Group group) {
        Long botUserId = requireBotUserId();
        GroupMember existing = groupMemberService.findByGroupAndUserId(group.getId(), botUserId);
        if (existing != null && Boolean.FALSE.equals(existing.getQuit())) {
            return;
        }
        // 加载 bot 的 j_users 同步信息（im_user 中应已存在）
        User bot = userService.getById(botUserId);
        String nickName = (bot != null && bot.getNickName() != null && !bot.getNickName().isEmpty())
            ? bot.getNickName() : props.getRedpacketBot().getNickName();
        String headImage = (bot != null && bot.getHeadImage() != null && !bot.getHeadImage().isEmpty())
            ? bot.getHeadImage() : props.getRedpacketBot().getHeadImage();

        GroupMember member = (existing == null) ? new GroupMember() : existing;
        member.setGroupId(group.getId());
        member.setUserId(botUserId);
        member.setUserNickName(nickName);
        member.setHeadImage(headImage);
        member.setRemarkNickName("");
        member.setRemarkGroupName("");
        member.setCompanyName("");
        member.setIsManager(Boolean.FALSE);
        member.setIsMuted(Boolean.FALSE);
        member.setIsDnd(Boolean.FALSE);
        member.setIsTopMessage(Boolean.FALSE);
        member.setIsTop(Boolean.FALSE);
        member.setQuit(Boolean.FALSE);
        if (member.getCreatedTime() == null) {
            member.setCreatedTime(new Date());
        }
        member.setQuitTime(null);
        groupMemberService.saveOrUpdate(member);
        log.info("[redpacket-bot] ensure bot in group: groupId={}, botUserId={}", group.getId(), botUserId);
    }

    @Override
    public GroupMessageVO dispatchRedpacket(Group group, Redpacket redpacket) {
        return dispatchRedpacket(group, redpacket, requireBotUserId(),
            props.getRedpacketBot().getNickName(), IMTerminalType.WEB.code());
    }

    @Override
    public GroupMessageVO dispatchRedpacket(Group group, Redpacket redpacket, Long senderId, String senderNick,
                                            Integer terminal) {
        Long botUserId = requireBotUserId();
        if (botUserId.equals(senderId)) {
            ensureBotInGroup(group);
        }

        RedpacketCardVO card = buildCard(redpacket, group.getId(), "create");
        String content = JSON.toJSONString(card);

        GroupMessage message = new GroupMessage();
        message.setLocalId(IdWorker.getIdStr());
        message.setGroupId(group.getId());
        message.setSendId(senderId);
        message.setSendNickName(senderNick);
        message.setContent(content);
        message.setType(MessageType.REDPACKET.code());
        message.setStatus(MessageStatus.PENDING.code());
        message.setReceipt(Boolean.FALSE);
        message.setReceiptOk(Boolean.FALSE);
        message.setSendTime(new Date());
        groupMessageService.saveMessage(message);

        List<Long> recvIds = groupMemberService.findUserIdsByGroupId(group.getId()).stream()
            .filter(id -> !senderId.equals(id))
            .collect(Collectors.toList());

        GroupMessageVO vo = new GroupMessageVO();
        vo.setId(message.getId());
        vo.setLocalId(message.getLocalId());
        vo.setSeqNo(message.getSeqNo());
        vo.setGroupId(message.getGroupId());
        vo.setSendId(message.getSendId());
        vo.setSendNickName(message.getSendNickName());
        vo.setContent(message.getContent());
        vo.setType(message.getType());
        vo.setReceipt(message.getReceipt());
        vo.setReceiptOk(message.getReceiptOk());
        vo.setStatus(message.getStatus());
        vo.setSendTime(message.getSendTime());

        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        int term = terminal == null ? IMTerminalType.WEB.code() : terminal;
        sendMessage.setSender(new IMUserInfo(senderId, term));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(vo);
        sendMessage.setSendToSelf(false);
        imClient.sendGroupMessage(sendMessage);
        log.info("[redpacket] dispatched card: groupId={}, senderId={}, redpacketId={}, messageId={}, recvCount={}",
            group.getId(), senderId, redpacket.getId(), message.getId(), recvIds.size());
        return vo;
    }

    @Override
    public GroupMessageVO republishCardStatus(Group group, Redpacket redpacket) {
        Long botUserId = requireBotUserId();
        ensureBotInGroup(group);

        RedpacketCardVO card = buildCard(redpacket, group.getId(), "status_update");
        String content = JSON.toJSONString(card);

        GroupMessage message = new GroupMessage();
        message.setLocalId(IdWorker.getIdStr());
        message.setGroupId(group.getId());
        message.setSendId(botUserId);
        message.setSendNickName(props.getRedpacketBot().getNickName());
        message.setContent(content);
        message.setType(MessageType.REDPACKET.code());
        message.setStatus(MessageStatus.PENDING.code());
        message.setReceipt(Boolean.FALSE);
        message.setReceiptOk(Boolean.FALSE);
        message.setSendTime(new Date());
        groupMessageService.saveMessage(message);

        List<Long> recvIds = groupMemberService.findUserIdsByGroupId(group.getId()).stream()
            .filter(id -> !botUserId.equals(id))
            .collect(Collectors.toList());

        GroupMessageVO vo = new GroupMessageVO();
        vo.setId(message.getId());
        vo.setLocalId(message.getLocalId());
        vo.setSeqNo(message.getSeqNo());
        vo.setGroupId(message.getGroupId());
        vo.setSendId(message.getSendId());
        vo.setSendNickName(message.getSendNickName());
        vo.setContent(message.getContent());
        vo.setType(message.getType());
        vo.setReceipt(message.getReceipt());
        vo.setReceiptOk(message.getReceiptOk());
        vo.setStatus(message.getStatus());
        vo.setSendTime(message.getSendTime());

        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(botUserId, IMTerminalType.WEB.code()));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(vo);
        sendMessage.setSendToSelf(false);
        imClient.sendGroupMessage(sendMessage);
        log.info("[redpacket-bot] republished status: groupId={}, redpacketId={}, status={}, claimedCount={}",
            group.getId(), redpacket.getId(), card.getStatus(), card.getClaimedCount());
        return vo;
    }

    private RedpacketCardVO buildCard(Redpacket redpacket, Long groupId, String kind) {
        RedpacketCardVO card = new RedpacketCardVO();
        card.setKind(kind);
        card.setRedpacketId(redpacket.getId());
        card.setExternalId(redpacket.getExternalId());
        card.setGroupId(groupId);
        card.setTitle(redpacket.getTitle());
        card.setAmount(redpacket.getAmount());
        card.setTotalCount(redpacket.getTotalCount());
        card.setStatus(toCardStatus(redpacket.getStatus()));
        card.setClaimedCount(redpacket.getClaimedCount());
        card.setExpireAt(redpacket.getExpireAt() != null ? redpacket.getExpireAt().getTime() : null);
        card.setBotAvatar(props.getRedpacketBot().getHeadImage());
        if (redpacket.getExtra() != null && !redpacket.getExtra().isBlank()) {
            try {
                JSONObject extra = JSON.parseObject(redpacket.getExtra());
                if (extra != null && extra.getString("senderName") != null && !extra.getString("senderName").isBlank()) {
                    card.setSenderName(extra.getString("senderName"));
                }
            } catch (Exception ignored) {
                // 透传字段坏了也不影响卡片本身
            }
        }
        return card;
    }

    private Long requireBotUserId() {
        Long id = props.getRedpacketBot() != null ? props.getRedpacketBot().getUserId() : null;
        if (id == null || id <= 0) {
            throw new GlobalException(500, "boxim.redpacket-bot.user-id 未配置");
        }
        return id;
    }

    private static String toCardStatus(Integer status) {
        if (status == null) return "claiming";
        switch (status) {
            case 1: return "finished";
            case 2: return "expired";
            case 3: return "rolled_back";
            default: return "claiming";
        }
    }
}
