package com.bx.implatform.service;

import com.bx.implatform.entity.Group;
import com.bx.implatform.entity.Redpacket;
import com.bx.implatform.vo.GroupMessageVO;

/**
 * 红包机器人 —— 以 BoximIntegrationProperties.RedpacketBot.userId 身份在群里发卡片消息。
 */
public interface RedpacketBotService {

    /**
     * 确保机器人是 group 的成员。若已退出 / 不存在，会自动 (重新)加入并设置昵称/头像。
     *
     * @return 加入或已在群里
     */
    void ensureBotInGroup(Group group);

    /**
     * 在群里发一条红包卡片消息（MessageType.REDPACKET），写库 + 走 IMClient 推送。
     *
     * @param group     目标群
     * @param redpacket 已落库的红包记录（本方法只读其字段，不会修改）
     * @return 发送出去的群消息 VO（含 messageId / sendTime 等）
     */
    GroupMessageVO dispatchRedpacket(Group group, Redpacket redpacket);

    /**
     * 以指定成员身份发红包卡片。普通人、群主、管理员走这条。
     */
    GroupMessageVO dispatchRedpacket(Group group, Redpacket redpacket, Long senderId, String senderNick, Integer terminal);

    /**
     * 重发一条 kind=status_update 的红包卡片消息，前端以 redpacketId 作为合并键，刷新已存在卡片的状态。
     * 用于：领完 / 过期 / 回滚 等场景的即时通知。
     */
    GroupMessageVO republishCardStatus(Group group, Redpacket redpacket);
}
