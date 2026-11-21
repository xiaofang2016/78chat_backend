package com.bx.implatform.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserType {
    /**
     * 正常用户
     */
    NORMAL(1),
    /**
     * 公开账户
     */
    OPEN_ACCOUNT(2),
    /**
     * 未登录客服访客。不对应 aagames 账号。
     */
    GUEST(4),
    /**
     * 说球帝手机号账号。聊天身份以说球帝为准，不对应吉祥或 UED 的用户 id。
     */
    SPORT(5);

    private final Integer value;

}
