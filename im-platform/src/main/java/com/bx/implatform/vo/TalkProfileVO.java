package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "公开聊天链接用户资料")
public class TalkProfileVO {

    @Schema(description = "用户 id")
    private Long userId;

    @Schema(description = "链接标识")
    private String talkSlug;

    @Schema(description = "昵称")
    private String nickName;

    @Schema(description = "头像缩略图")
    private String headImageThumb;

    @Schema(description = "个性签名")
    private String signature;

    @Schema(description = "当前登录用户是否可向该用户发消息")
    private Boolean canMessage;

    @Schema(description = "是否已开启匿名/公开聊天")
    private Boolean talkEnabled;

    @Schema(description = "是否已是好友（仅登录时有意义）")
    private Boolean isFriend;

    @Schema(description = "是否为自己")
    private Boolean self;
}
