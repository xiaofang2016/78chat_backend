package com.bx.implatform.service;

import com.bx.implatform.dto.LuckyBonusApplyDTO;
import com.bx.implatform.entity.Redpacket;
import com.bx.implatform.vo.ai.LuckyBonusApplyCardVO;

/**
 * AI 机器人向用户发送私聊消息（文本 / 交互卡片 / 红包）。
 */
public interface BotPrivateMessageService {

    void sendText(Long botUserId, Long recvUserId, String content);

    /** 发送文本并返回持久化后的消息 id */
    Long sendTextReturningId(Long botUserId, Long recvUserId, String content);

    /** 发送文本（可指定 localId）并返回消息 id */
    Long sendTextReturningId(Long botUserId, Long recvUserId, String content, String localId);

    /**
     * 发送图片并返回消息 id。
     * @param contentJson 与客户端一致的图片 JSON：originUrl / thumbUrl / width / height
     */
    Long sendImageReturningId(Long botUserId, Long recvUserId, String contentJson);

    /** 发送图片（可指定 localId）并返回消息 id */
    Long sendImageReturningId(Long botUserId, Long recvUserId, String contentJson, String localId);

    void sendLuckyBonusApplyCard(Long botUserId, Long recvUserId, LuckyBonusApplyCardVO card);

    void sendPrivateRedpacket(Long botUserId, Long recvUserId, Redpacket redpacket);
}
