package com.bx.implatform.dto.external;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "直播间进出提示")
public class LiveGroupPresenceSendDTO {

    @NotBlank(message = "昵称不可为空")
    @Schema(description = "观众展示昵称")
    private String nick;

    @NotBlank(message = "动作不可为空")
    @Pattern(regexp = "enter|leave", message = "action 仅支持 enter 或 leave")
    @Schema(description = "enter=进入 leave=离开")
    private String action;

    @Schema(description = "进出后的当前观看人数")
    private Integer viewerCount;
}
