package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

@Data
@Schema(description = "私聊消息DTO")
public class PrivateMessageDTO {

   // @NotNull(message = "本地消息id不可为空")
    @Schema(description = "本地消息id,前端通过雪花算法生成")
    private String localId;

    @NotNull(message = "接收用户id不可为空")
    @Schema(description = "接收用户id")
    private Long recvId;

    /** 实际上限由业务层区分：普通私聊 1024 字；AI知识库机器人 UTF-8 ≤50KB */
    @Length(max = 131072, message = "消息内容过长")
    @NotEmpty(message = "发送内容不可为空")
    @Schema(description = "发送内容")
    private String content;

    @NotNull(message = "消息类型不可为空")
    @Schema(description = "消息类型 0:文字 1:图片 2:文件 3:语音 4:视频")
    private Integer type;

    @Schema(description = "引用消息id")
    private Long quoteMessageId;

}
