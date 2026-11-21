package com.bx.implatform.service;

import com.bx.implatform.session.WebrtcPrivateSession;

public interface RtcPrivateNotifyService {

    /**
     * 发起呼叫离线通知
     * @param rtcSession 通话会话信息
     */
    void setup(WebrtcPrivateSession rtcSession);

    /**
     * 取消呼叫离线通知
     *
     * @param rtcSession 通话会话信息
     * @param messageKey 国际化消息 key，如 notify.rtcPrivateCallCancelled
     */
    void stop(WebrtcPrivateSession rtcSession, String messageKey);
}
