package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "更新公开聊天链接设置")
public class UserTalkSettingsDTO {

    @Schema(description = "是否开启")
    private Boolean talkEnabled;

    @Schema(description = "链接标识，3-32 位字母数字下划线；开启时必填")
    private String talkSlug;
}
