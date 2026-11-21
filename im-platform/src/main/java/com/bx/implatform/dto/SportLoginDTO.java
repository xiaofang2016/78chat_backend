package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
@Schema(description = "说球帝令牌登录")
public class SportLoginDTO {

    @NotEmpty(message = "{sport.loginRequired}")
    @Schema(description = "说球帝登录令牌")
    private String token;

    @Max(value = 3, message = "登录终端类型取值范围:0,3")
    @Min(value = 0, message = "登录终端类型取值范围:0,3")
    @Schema(description = "登录终端 0:web 1:app 2:pc 3:内嵌客服，不传默认 0")
    private Integer terminal;
}
