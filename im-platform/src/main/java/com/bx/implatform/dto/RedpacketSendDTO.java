package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "群成员发红包")
public class RedpacketSendDTO {

    @NotNull(message = "群不能为空")
    @Schema(description = "目标群 id")
    private Long groupId;

    @NotNull(message = "金额不能为空")
    @DecimalMin(value = "0.01", message = "单个金额至少 0.01")
    @DecimalMax(value = "200", message = "单个金额最多 200")
    @Schema(description = "每个红包金额（元）")
    private BigDecimal amount;

    @NotNull(message = "个数不能为空")
    @Min(value = 1, message = "至少 1 个")
    @Max(value = 100, message = "最多 100 个")
    @Schema(description = "红包个数")
    private Integer totalCount;

    @Size(max = 32, message = "祝福语最多 32 字")
    @Schema(description = "祝福语")
    private String title;
}
