package com.bx.implatform.vo.external;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 接口①：box-im → 第三方 红包派发响应
 */
@Data
@Schema(description = "红包派发响应 (接口①)")
public class RedpacketPushVO {

    @Schema(description = "box-im 内部红包 id")
    private String redpacketId;

    @Schema(description = "群里那条卡片消息 id")
    private String messageId;

    @Schema(description = "实际派发到的群 id")
    private String groupId;

    @Schema(description = "过期时间戳（毫秒）")
    private Long expireAt;

    @Schema(description = "创建时间戳（毫秒）")
    private Long createdAt;
}
