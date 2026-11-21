package com.bx.implatform.controller;

import com.bx.implatform.dto.SendMailCodeDTO;
import com.bx.implatform.dto.SendSmsCodeDTO;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.result.Result;
import com.bx.implatform.vo.CaptchaImageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 验证码相关接口。
 *
 * v1.1 用户体系打通后，本地登录注册全部停用，验证码不再产生业务价值，
 * 此处保留 mapping 仅为给老前端返回清晰的 410，便于排错。
 */
@Tag(name = "[已停用] 验证码相关接口")
@RestController
@RequestMapping("/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private static final String DISABLED_MSG = "验证码服务已停用（本地登录注册已迁移到 aagames）";

    @Operation(summary = "[已停用] 生成图形验证码")
    @PostMapping("/img/code")
    public Result<CaptchaImageVO> generateImageCode() {
        throw new GlobalException(410, DISABLED_MSG);
    }

    @Operation(summary = "[已停用] 发送短信验证码")
    @PostMapping("/sms/code")
    public Result sendSmsCode(@RequestBody SendSmsCodeDTO dto) {
        throw new GlobalException(410, DISABLED_MSG);
    }

    @Operation(summary = "[已停用] 发送邮箱验证码")
    @PostMapping("/mail/code")
    public Result sendMailCode(@RequestBody SendMailCodeDTO dto) {
        throw new GlobalException(410, DISABLED_MSG);
    }

    @Operation(summary = "[已停用] 校验短信验证码")
    @GetMapping("/sms/vertify")
    public Result<Boolean> vertifySmsCode(@RequestParam String id, @RequestParam String code) {
        throw new GlobalException(410, DISABLED_MSG);
    }

    @Operation(summary = "[已停用] 校验邮件验证码")
    @GetMapping("/mail/vertify")
    public Result<Boolean> vertifyMailCode(@RequestParam String id, @RequestParam String code) {
        throw new GlobalException(410, DISABLED_MSG);
    }

    @Operation(summary = "[已停用] 校验图形验证码")
    @GetMapping("/img/vertify")
    public Result<Boolean> vertifyImageCode(@RequestParam String id, @RequestParam String code) {
        throw new GlobalException(410, DISABLED_MSG);
    }

}
