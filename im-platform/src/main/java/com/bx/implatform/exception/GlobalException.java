package com.bx.implatform.exception;

import com.bx.implatform.enums.ResultCode;
import lombok.Data;

import java.io.Serializable;

@Data
public class GlobalException extends RuntimeException implements Serializable {
    private static final long serialVersionUID = 8134030011662574394L;
    private Integer code;
    /**
     * 消息内容，可为国际化 key 或直接文案
     * 在 GlobalExceptionHandler 中会尝试通过 MessageSource 解析
     */
    private String message;
    /**
     * 稳定机器可读错误码（可选）
     */
    private String errorCode;
    /**
     * 国际化消息的占位符参数
     */
    private Object[] args;

    public GlobalException(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public GlobalException(Integer code, String message, String errorCode) {
        this.code = code;
        this.message = message;
        this.errorCode = errorCode;
    }

    public GlobalException(Integer code, String messageKey, Object... args) {
        this.code = code;
        this.message = messageKey;
        this.args = args;
    }

    public GlobalException(ResultCode resultCode, String message) {
        this.code = resultCode.getCode();
        this.message = message;
    }

    public GlobalException(ResultCode resultCode, String messageKey, Object... args) {
        this.code = resultCode.getCode();
        this.message = messageKey;
        this.args = args;
    }

    public GlobalException(ResultCode resultCode) {
        this.code = resultCode.getCode();
        this.message = resultCode.getMsgKey();
    }

    public GlobalException(String message) {
        this.code = ResultCode.PROGRAM_ERROR.getCode();
        this.message = message;
    }

    /**
     * 使用国际化 key 抛出异常
     */
    public GlobalException(String messageKey, Object... args) {
        this.code = ResultCode.PROGRAM_ERROR.getCode();
        this.message = messageKey;
        this.args = args;
    }

}
