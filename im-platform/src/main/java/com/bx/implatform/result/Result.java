package com.bx.implatform.result;

import lombok.Data;

@Data
public class Result<T> {

    private int code;

    private String message;

    /** 稳定机器可读错误码（如 AI_CS_INVALID_IMAGE_CONTENT），成功时为空 */
    private String errorCode;

    private T data;

}
