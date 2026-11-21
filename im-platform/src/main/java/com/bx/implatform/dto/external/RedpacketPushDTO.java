package com.bx.implatform.dto.external;

import com.alibaba.fastjson.JSONObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 接口①：第三方 → box-im 推送红包派发请求体
 */
@Data
@Schema(description = "红包派发请求 (接口①)")
public class RedpacketPushDTO {

    @NotEmpty(message = "externalId 不可为空")
    @Size(max = 64, message = "externalId 长度需 ≤ 64")
    @Schema(description = "第三方业务侧唯一键，幂等控制依据")
    private String externalId;

    @Schema(description = "目标群 id；不传则使用 box-im 配置的默认晒图群")
    private Long groupId;

    @NotNull(message = "amount 不可为空")
    @DecimalMin(value = "0.01", message = "amount 必须 > 0")
    @Schema(description = "单个红包金额（元），保留 2 位小数，> 0")
    private BigDecimal amount;

    @NotNull(message = "totalCount 不可为空")
    @Min(value = 1, message = "totalCount 必须 ≥ 1")
    @Schema(description = "名额数")
    private Integer totalCount;

    @Size(max = 32, message = "title 长度需 ≤ 32")
    @Schema(description = "卡片祝福语")
    private String title;

    @Min(value = 1, message = "expireMinutes 至少 1")
    @Max(value = 1440, message = "expireMinutes 最多 1440")
    @Schema(description = "过期分钟数，默认 10")
    private Integer expireMinutes;

    @Schema(description = "any / valid_show_poster / daily_quota / third_party，默认 any")
    private String eligibilityRule;

    @Schema(description = "业务透传 JSON")
    private JSONObject extra;
}
