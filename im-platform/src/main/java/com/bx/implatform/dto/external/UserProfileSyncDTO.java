package com.bx.implatform.dto.external;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * aagames → box-im 用户资料同步（仅 IM 侧已有能力：手机号 + 实名）。
 */
@Data
@Schema(description = "用户资料同步请求")
public class UserProfileSyncDTO {

    @NotNull(message = "userId 不可为空")
    @Schema(description = "aagames j_users.id，与 im_user.id 一致")
    private Long userId;

    @Size(max = 64, message = "realName 长度需 ≤ 64")
    @Schema(description = "真实姓名")
    private String realName;

    @Size(max = 32, message = "mobile 长度需 ≤ 32")
    @Schema(description = "手机号")
    private String mobile;

    @Size(max = 8, message = "phoneAreaCode 长度需 ≤ 8")
    @Schema(description = "手机区号")
    private String phoneAreaCode;
}
