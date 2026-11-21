package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * aagames /internal/sso/exchange 返回：账号同步 + IM 专用 token（非游戏 access_token）。
 */
@Data
@Schema(description = "aagames SSO 兑换结果")
public class AagamesSsoExchangeVO {

    @Schema(description = "IM 调 aagames 外部接口（红包等）用的 token")
    private String imAccessToken;

    @Schema(description = "同步到 im_user 的账号信息")
    private AagamesUserVO user;
}
