package com.bx.implatform.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 响应码枚举
 *
 * @author Blue
 * @date 2020/10/19
 **/
@Getter
@AllArgsConstructor
public enum ResultCode {
    /**
     * 成功
     */
    SUCCESS(200, "result.success"),
    /**
     * 未登录
     */
    NO_LOGIN(400, "result.noLogin"),
    /**
     * token无效或已过期
     */
    INVALID_TOKEN(401, "result.invalidToken"),
    /**
     * 系统繁忙，请稍后再试
     */
    PROGRAM_ERROR(500, "result.programError"),
    /**
     * 密码不正确
     */
    PASSWOR_ERROR(10001, "result.passwordError"),
    /**
     * 该用户名已注册
     */
    USERNAME_ALREADY_REGISTER(10003, "result.usernameAlreadyRegister"),
    /**
     * 请不要输入非法内容
     */
    XSS_PARAM_ERROR(10004, "result.xssParamError");


    private final int code;
    /**
     * 国际化消息 key，通过 MessageSource 解析为实际文案
     */
    private final String msgKey;

}