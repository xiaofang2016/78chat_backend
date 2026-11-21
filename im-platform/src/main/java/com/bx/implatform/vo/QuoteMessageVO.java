package com.bx.implatform.vo;

import com.bx.imcommon.serializer.DateToLongSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * @author Blue
 * @version 1.0
 * @date 2025-02-12
 */
@Data
@Schema(description = "引用消息VO")
public class QuoteMessageVO {

    @Schema(description = " 消息id")
    private Long id;

    @Schema(description = " 本地消息id")
    private String localId;

    @Schema(description = " 发送者id")
    private Long sendId;

    @Schema(description = " 发送内容")
    private String content;

    @Schema(description = "消息内容类型 MessageType")
    private Integer type;

    @Schema(description = " 状态")
    private Integer status;

    @Schema(description = "发送时间")
    @JsonSerialize(using = DateToLongSerializer.class)
    private Date sendTime;
}
