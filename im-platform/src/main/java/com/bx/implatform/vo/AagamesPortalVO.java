package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "IM → 7PG H5 门户（携带 IM/游戏 token）")
public class AagamesPortalVO {

    @Schema(description = "aagames token（IM 专用或游戏 access_token），H5 以 ?at= 写入 Cookie")
    private String accessToken;

    @Schema(description = "可直接打开的 7PG H5 URL（已带 at 参数）")
    private String h5LoginUrl;
}
