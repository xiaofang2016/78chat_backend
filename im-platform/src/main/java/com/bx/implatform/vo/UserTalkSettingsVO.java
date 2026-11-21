package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户公开聊天链接设置")
public class UserTalkSettingsVO {

    @Schema(description = "是否开启")
    private Boolean talkEnabled;

    @Schema(description = "链接标识")
    private String talkSlug;

    @Schema(description = "完整链接（前端展示用）")
    private String talkUrl;
}
