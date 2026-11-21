package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "活动领取申请结果")
public class LuckyBonusApplyResultVO {

    @Schema(description = "活动种类")
    private String kind;

    @Schema(description = "送彩票：TC-Gaming 游戏入口 URL")
    private String gameUrl;

    @Schema(description = "送彩票：奖票张数")
    private Integer ticketCount;

    @Schema(description = "送彩票：额度（元）")
    private BigDecimal giftAmount;

    @Schema(description = "送彩票：存款单号")
    private String orderTradeNo;

    @Schema(description = "送彩票：期次 key（截止日 Y-m-d，21:30）")
    private String periodKey;

    @Schema(description = "送彩票：是否为重复领取（幂等返回，不再发提示消息）")
    private Boolean alreadyClaimed;

    @Schema(description = "是否已成功申请（已生成红包）")
    private Boolean applied;

    @Schema(description = "体育连红：资格审核已通过，待用户点领取红包")
    private Boolean reviewPassed;
}
