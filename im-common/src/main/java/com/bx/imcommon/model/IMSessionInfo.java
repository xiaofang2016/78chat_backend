package com.bx.imcommon.model;

import lombok.Data;

@Data
public class IMSessionInfo {
    /**
     * 用户id
     */
    private Long userId;

    /**
     * 终端类型
     */
    private Integer terminal;

    /**
     * 会话标记。旧令牌没有这个字段，不能继续使用。
     */
    private Integer sessionEpoch;

}
