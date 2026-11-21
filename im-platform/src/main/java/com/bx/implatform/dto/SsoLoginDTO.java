package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
@Schema(description = "SSO 登录 DTO（aagames 已登录用户通过一次性 code 换 IM JWT）")
public class SsoLoginDTO {

    @NotEmpty(message = "ssoCode 不可为空")
    @Schema(description = "aagames 签发的 SSO 一次性 code")
    private String ssoCode;

    @NotEmpty(message = "商户号不可为空")
    @Schema(description = "aagames 商户号，只能是已开通商户")
    private String merchantNo;

    @Schema(description = "终端类型，同 login.terminal")
    private Integer terminal = 0;
}
