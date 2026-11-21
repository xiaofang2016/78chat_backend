package com.bx.implatform.dto.external;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "同步主播陌生人私聊资格（live-api 审核通过/撤销时调用）")
public class LiveAnchorStrangerPmDTO {

    @NotNull
    @Schema(description = "是否允许陌生人发私聊")
    private Boolean allowed;
}
