package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "说球帝公开群会话")
public class SportChatSessionVO {

    @Schema(description = "群id")
    private Long groupId;

    @Schema(description = "当前用户id")
    private Long userId;

    @Schema(description = "当前用户昵称")
    private String nickName;

    @Schema(description = "最近文字消息")
    private List<GroupMessageVO> messages;
}
