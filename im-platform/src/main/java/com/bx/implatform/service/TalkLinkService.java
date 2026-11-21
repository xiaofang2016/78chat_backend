package com.bx.implatform.service;

import com.bx.implatform.dto.UserTalkSettingsDTO;
import com.bx.implatform.entity.User;
import com.bx.implatform.vo.TalkProfileVO;
import com.bx.implatform.vo.UserTalkSettingsVO;

public interface TalkLinkService {

    /**
     * 根据链接标识解析已开启公开聊天的用户（发消息校验用）
     */
    User findEnabledUserBySlug(String slug);

    /**
     * 根据链接标识解析用户（展示公开资料页，不要求已开启 talk）
     */
    User findUserByTalkSlug(String slug);

    /**
     * 获取公开资料（viewerId 可为 null，表示未登录访客）
     */
    TalkProfileVO resolveProfile(String slug, Long viewerId);

    UserTalkSettingsVO getSettings(Long userId);

    void updateSettings(Long userId, UserTalkSettingsDTO dto);

    /**
     * 非好友场景下是否允许发送私聊：对方开启 talk / 已有会话往来
     */
    boolean canSendWithoutFriend(Long senderId, Long recvId);
}
