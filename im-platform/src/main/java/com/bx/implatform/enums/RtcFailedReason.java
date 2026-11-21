package com.bx.implatform.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 音视频通话失败原因，前端需要实现对应语言包
 * @author Blue
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum RtcFailedReason {

    /**
     * 超时未接听
     */
    ACCEPT_TIMEOUT("acceptTimeout"),
    /**
     * 用户忙线
     */
    USER_BUSY("userBusy"),
    /**
     * 用户设备不支持通话
     */
    DEV_NOT_SUPPORT("devNotSupport");

    private final String value;
}
