package com.bx.implatform.dto.external;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "确保直播间群主在群内（成员记录缺失/已退出时恢复）")
public class LiveGroupEnsureOwnerDTO {

    @NotNull(message = "userId 不可为空")
    @Schema(description = "群主用户 ID")
    private Long userId;

    @Schema(description = "直播间 DB 中的 joinToken，用于 Redis 缺失时回填")
    private String joinToken;
}
