package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiCsAdminReplyDTO {

    @NotNull(message = "botUserId 不能为空")
    private Long botUserId;

    @NotNull(message = "customerUserId 不能为空")
    private Long customerUserId;

    @Schema(description = "消息类型：0 文本（默认），1 图片；缺省按 0")
    private Integer type;

    @Size(max = 32, message = "localId 最长 32 字符")
    @Schema(description = "客户端幂等键；新前端应传；旧前端可不传（服务端生成，不承诺跨请求幂等）")
    private String localId;

    @NotBlank(message = "内容不能为空")
    @Schema(description = "文本正文；图片时为 JSON：originUrl/thumbUrl/width/height")
    private String content;
}
