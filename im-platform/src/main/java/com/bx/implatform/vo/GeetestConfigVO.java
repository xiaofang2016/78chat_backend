package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "极验公开配置（仅 captchaId，不含密钥）")
public class GeetestConfigVO {

    @Schema(description = "客户端是否可用注册+极验; false 表示注册暂不可用, 不是免验")
    private Boolean enabled = false;

    @Schema(description = "极验公开 captchaId")
    private String captchaId;
}
