package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * aagames_api 接口⑥（/internal/auth/validate）返回的用户基本信息。
 */
@Data
@Schema(description = "aagames 用户基本信息")
public class AagamesUserVO {

    @Schema(description = "j_users.id，等于 box-im im_user.id")
    private Long userId;

    @Schema(description = "登录名")
    private String userName;

    @Schema(description = "昵称")
    private String nickName;

    @Schema(description = "头像 URL，可为空")
    private String userAvatar;

    @Schema(description = "商户号")
    private String merchantNo;

    @Schema(description = "状态：active / frozen / wallet_frozen / unknown")
    private String status;

    @Schema(description = "aagames access_token 自身过期时间戳（毫秒），可能为 null")
    private Long tokenExpireAt;
}
