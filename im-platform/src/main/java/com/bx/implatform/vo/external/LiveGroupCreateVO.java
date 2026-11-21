package com.bx.implatform.vo.external;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "直播间公开群创建结果")
public class LiveGroupCreateVO {

    @Schema(description = "IM 群 ID")
    private Long groupId;

    @Schema(description = "长期加群 token")
    private String joinToken;
}
