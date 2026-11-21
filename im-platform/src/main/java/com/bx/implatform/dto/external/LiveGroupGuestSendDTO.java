package com.bx.implatform.dto.external;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "直播间游客发言")
public class LiveGroupGuestSendDTO {

    @NotBlank(message = "昵称不可为空")
    @Size(max = 16)
    @Schema(description = "游客展示昵称")
    private String guestNick;

    @NotBlank(message = "内容不可为空")
    @Size(max = 200)
    @Schema(description = "消息正文")
    private String content;
}
