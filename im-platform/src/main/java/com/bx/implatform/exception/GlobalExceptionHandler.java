package com.bx.implatform.exception;

import cn.hutool.extra.servlet.JakartaServletUtil;
import cn.hutool.json.JSONException;
import com.bx.implatform.enums.ResultCode;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.util.MessageUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.UndeclaredThrowableException;
import java.time.format.DateTimeParseException;
import java.util.List;

@ControllerAdvice
@ResponseBody
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 解析异常消息：若为国际化 key 则解析，否则原样返回
     */
    private String resolveMessage(GlobalException ex) {
        Object[] args = ex.getArgs();
        return MessageUtils.getMessage(ex.getMessage(), args);
    }

    @ExceptionHandler(value = Exception.class)
    public Result handleException(HttpServletRequest request, Exception e) {
        String ip = JakartaServletUtil.getClientIP(request);
        if (e instanceof GlobalException ex) {
            // token过期是正常情况,不打印
            if (!ex.getCode().equals(ResultCode.INVALID_TOKEN.getCode())) {
                log.error("全局异常捕获,ip:{},错误日志:{}", ip, ex.getMessage());
            }
            return ResultUtils.error(ex.getCode(), resolveMessage(ex), ex.getErrorCode());
        } else if (e instanceof UndeclaredThrowableException ute) {
            GlobalException ex = (GlobalException) ute.getCause();
            log.error("全局异常捕获,ip:{},错误日志:{}", ip, ex.getMessage(), e);
            return ResultUtils.error(ex.getCode(), resolveMessage(ex), ex.getErrorCode());
        } else {
            log.error("全局异常捕获,ip:{},错误日志:{}", ip, e.getMessage(), e);
            return ResultUtils.error(ResultCode.PROGRAM_ERROR);
        }
    }

    /**
     * 数据解析错误
     **/
    @ExceptionHandler(value = HttpMessageNotReadableException.class)
    public Result handleMessageNotReadableException(HttpServletRequest request, HttpMessageNotReadableException e) {
        String ip = JakartaServletUtil.getClientIP(request);
        log.error("全局异常捕获,ip:{},错误日志:{}", ip, e.getMessage());
        Throwable t = e.getCause();
        if (t instanceof JSONException) {
            t = t.getCause();
            if (t instanceof DateTimeParseException) {
                return ResultUtils.error(ResultCode.PROGRAM_ERROR, MessageUtils.getMessage("validation.dateFormatError"));
            }
            return ResultUtils.error(ResultCode.PROGRAM_ERROR, MessageUtils.getMessage("validation.dataFormatError"));
        }
        return ResultUtils.error(ResultCode.PROGRAM_ERROR);
    }

    /**
     * 处理请求参数格式错误 @RequestBody上validate失败后抛出的异常
     */
    @ExceptionHandler(value = {MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.OK)
    public Result handleValidationExceptionHandler(MethodArgumentNotValidException exception) {
        BindingResult bindResult = exception.getBindingResult();
        String msg;
        if (bindResult.hasErrors()) {
            msg = bindResult.getAllErrors().get(0).getDefaultMessage();
            if (msg.contains("NumberFormatException")) {
                msg = MessageUtils.getMessage("validation.paramTypeError");
            }
        } else {
            msg = MessageUtils.getMessage("validation.retryLater");
        }
        String errorCode = null;
        if (msg != null && msg.contains("请先完成人机验证")) {
            errorCode = "GEETEST_REQUIRED";
        }
        return ResultUtils.error(ResultCode.PROGRAM_ERROR.getCode(), msg, errorCode);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result handleBindException(BindException e) {
        List<ObjectError> errors = e.getAllErrors();
        ObjectError error = errors.get(0);
        String errorMsg = error.getDefaultMessage();
        return ResultUtils.error(ResultCode.PROGRAM_ERROR, errorMsg);
    }

}
