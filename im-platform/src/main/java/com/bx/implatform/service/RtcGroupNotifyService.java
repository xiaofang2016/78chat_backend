package com.bx.implatform.service;

import com.bx.implatform.entity.Group;
import com.bx.implatform.session.WebrtcUserInfo;

import java.util.List;

public interface RtcGroupNotifyService {

    /**
     * 发起通话呼叫
     *
     * @param group   群聊信息
     * @param inviter 发起邀请人信息
     * @param recvIds 被邀请人id
     */
    void setUp(Group group, WebrtcUserInfo inviter, List<Long> recvIds);

    /**
     * 停止通话呼叫
     *
     * @param groupId    群聊id
     * @param recvIds    被邀请人id
     * @param messageKey 国际化消息 key，如 notify.rtcGroupCallCancelled
     */
    void stop(Long groupId, List<Long> recvIds, String messageKey);

    /**
     * 停止通话呼叫
     *
     * @param groupId    群聊id
     * @param recvId     被邀请人id
     * @param messageKey 国际化消息 key，如 notify.rtcGroupAcceptedElsewhere
     */
    void stop(Long groupId, Long recvId, String messageKey);

}
