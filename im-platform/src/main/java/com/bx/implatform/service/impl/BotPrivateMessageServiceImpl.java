package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.bx.imclient.IMClient;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.IMPrivateMessage;
import com.bx.imcommon.model.IMUserInfo;
import com.bx.implatform.config.props.BoximIntegrationProperties;
import com.bx.implatform.entity.PrivateMessage;
import com.bx.implatform.entity.Redpacket;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.service.BotPrivateMessageService;
import com.bx.implatform.service.PrivateMessageService;
import com.bx.implatform.util.BeanUtils;
import com.bx.implatform.util.ConvUtil;
import com.bx.implatform.util.SensitiveFilterUtil;
import com.bx.implatform.vo.PrivateMessageVO;
import com.bx.implatform.vo.ai.LuckyBonusApplyCardVO;
import com.bx.implatform.vo.external.RedpacketCardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class BotPrivateMessageServiceImpl implements BotPrivateMessageService {

    private final IMClient imClient;
    private final SensitiveFilterUtil sensitiveFilterUtil;
    private final BoximIntegrationProperties boximProps;

    @Lazy
    @Autowired
    private PrivateMessageService privateMessageService;

    @Override
    public void sendText(Long botUserId, Long recvUserId, String content) {
        sendTextReturningId(botUserId, recvUserId, content);
    }

    @Override
    public Long sendTextReturningId(Long botUserId, Long recvUserId, String content) {
        return sendTextReturningId(botUserId, recvUserId, content, null);
    }

    @Override
    public Long sendTextReturningId(Long botUserId, Long recvUserId, String content, String localId) {
        if (botUserId == null || recvUserId == null || content == null || content.isBlank()) {
            return null;
        }
        PrivateMessage message = baseMessage(botUserId, recvUserId);
        if (localId != null && !localId.isBlank()) {
            message.setLocalId(localId.trim());
        }
        message.setType(MessageType.TEXT.code());
        message.setContent(sensitiveFilterUtil.filter(content));
        push(message);
        return message.getId();
    }

    @Override
    public Long sendImageReturningId(Long botUserId, Long recvUserId, String contentJson) {
        return sendImageReturningId(botUserId, recvUserId, contentJson, null);
    }

    @Override
    public Long sendImageReturningId(Long botUserId, Long recvUserId, String contentJson, String localId) {
        if (botUserId == null || recvUserId == null || contentJson == null || contentJson.isBlank()) {
            return null;
        }
        PrivateMessage message = baseMessage(botUserId, recvUserId);
        if (localId != null && !localId.isBlank()) {
            message.setLocalId(localId.trim());
        }
        message.setType(MessageType.IMAGE.code());
        message.setContent(contentJson.trim());
        push(message);
        return message.getId();
    }

    @Override
    public void sendLuckyBonusApplyCard(Long botUserId, Long recvUserId, LuckyBonusApplyCardVO card) {
        if (botUserId == null || recvUserId == null || card == null) {
            return;
        }
        if (card.getKind() == null || card.getKind().isBlank()) {
            card.setKind("lucky_bonus_apply");
        }
        card.setBotUserId(botUserId);
        PrivateMessage message = baseMessage(botUserId, recvUserId);
        message.setType(MessageType.LUCKY_BONUS_APPLY.code());
        message.setContent(JSON.toJSONString(card));
        push(message);
    }

    @Override
    public void sendPrivateRedpacket(Long botUserId, Long recvUserId, Redpacket redpacket) {
        if (botUserId == null || recvUserId == null || redpacket == null) {
            return;
        }
        RedpacketCardVO card = new RedpacketCardVO();
        card.setKind("create");
        card.setRedpacketId(redpacket.getId());
        card.setExternalId(redpacket.getExternalId());
        card.setGroupId(redpacket.getGroupId());
        card.setScope("private");
        card.setTargetUserId(recvUserId);
        card.setTitle(redpacket.getTitle());
        card.setAmount(redpacket.getAmount());
        card.setTotalCount(redpacket.getTotalCount());
        card.setClaimedCount(redpacket.getClaimedCount());
        card.setStatus("claiming");
        card.setExpireAt(redpacket.getExpireAt() != null ? redpacket.getExpireAt().getTime() : null);
        if (boximProps.getRedpacketBot() != null) {
            card.setBotAvatar(boximProps.getRedpacketBot().getHeadImage());
        }

        PrivateMessage message = baseMessage(botUserId, recvUserId);
        message.setType(MessageType.REDPACKET.code());
        message.setContent(JSON.toJSONString(card));
        push(message);

        redpacket.setMessageId(message.getId());
    }

    private PrivateMessage baseMessage(Long botUserId, Long recvUserId) {
        PrivateMessage message = new PrivateMessage();
        message.setConvKey(ConvUtil.buildConvKey(botUserId, recvUserId));
        message.setSendId(botUserId);
        message.setRecvId(recvUserId);
        message.setStatus(MessageStatus.PENDING.code());
        message.setSendTime(new Date());
        return message;
    }

    private void push(PrivateMessage message) {
        privateMessageService.saveMessage(message);
        PrivateMessageVO vo = BeanUtils.copyProperties(message, PrivateMessageVO.class);
        IMPrivateMessage<PrivateMessageVO> sendMessage = new IMPrivateMessage<>();
        sendMessage.setSender(new IMUserInfo(message.getSendId(), IMTerminalType.WEB.code()));
        sendMessage.setRecvId(message.getRecvId());
        sendMessage.setSendToSelf(false);
        sendMessage.setSendResult(true);
        sendMessage.setData(vo);
        imClient.sendPrivateMessage(sendMessage);
    }
}
