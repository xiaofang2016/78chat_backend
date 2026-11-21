package com.bx.implatform.dto.external;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "直播间公开群加群（live-api 代观众加入）")
public class LiveGroupJoinDTO {

    @NotNull(message = "userId 不可为空")
    @Schema(description = "观众用户 ID")
    private Long userId;

    @NotNull(message = "groupId 不可为空")
    @Schema(description = "群 ID")
    private Long groupId;

    @NotBlank(message = "joinToken 不可为空")
    @Schema(description = "直播间长期加群 token")
    private String joinToken;
}
