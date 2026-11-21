package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "外部登录 DTO（携带 aagames access_token 换 box-im JWT）")
public class ExternalLoginDTO {

    @Max(value = 3, message = "登录终端类型取值范围:0,3")
    @Min(value = 0, message = "登录终端类型取值范围:0,3")
    @NotNull(message = "登录终端类型不可为空")
    @Schema(description = "登录终端 0:web 1:app 2:pc 3:内嵌客服")
    private Integer terminal;

    @NotEmpty(message = "accessToken 不可为空")
    @Schema(description = "aagames_api 颁发的 access_token")
    private String accessToken;

    @NotEmpty(message = "商户号不可为空")
    @Schema(description = "aagames 商户号，只能是已开通商户")
    private String merchantNo;
}
