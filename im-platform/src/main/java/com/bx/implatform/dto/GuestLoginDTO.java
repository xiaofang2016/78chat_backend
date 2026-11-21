package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "未登录客服访客登录")
public class GuestLoginDTO {

    @Max(value = 3, message = "登录终端类型取值范围:0,3")
    @Min(value = 0, message = "登录终端类型取值范围:0,3")
    @NotNull(message = "登录终端类型不可为空")
    @Schema(description = "登录终端 0:web 1:app 2:pc 3:内嵌客服")
    private Integer terminal;

    @NotEmpty(message = "商户号不可为空")
    @Schema(description = "aagames 商户号，只能是已开通商户")
    private String merchantNo;

    @NotEmpty(message = "设备标识不可为空")
    @Pattern(regexp = "^[A-Za-z0-9_-]{16,64}$", message = "设备标识无效")
    @Schema(description = "客户端持久化的设备标识，同一设备复用同一个访客")
    private String deviceId;
}
