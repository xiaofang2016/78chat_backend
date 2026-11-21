package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "钱包流水一条")
public class WalletRecordItemVO {

    private String id;

    @Schema(description = "bill / redpacket / withdraw")
    private String kind;

    private String title;

    private BigDecimal amount;

    @Schema(description = "毫秒时间戳")
    private Long createdAt;
}
