package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "职圈钱包摘要，金额来自 aagames 主钱包")
public class WalletSummaryVO {

    @Schema(description = "当前账号商户号")
    private String merchantNo;

    @Schema(description = "主钱包可用余额")
    private BigDecimal money;

    @Schema(description = "累计提款")
    private BigDecimal drawMoney;

    @Schema(description = "累计优惠/红包")
    private BigDecimal benefitMoney;
}
