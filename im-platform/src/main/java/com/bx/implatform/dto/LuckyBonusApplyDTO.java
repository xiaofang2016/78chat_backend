package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "活动领取申请（幸运加码 / 天天好运金）")
public class LuckyBonusApplyDTO {

    @Schema(description = "lucky_bonus_apply | daily_good_luck_gold | invite_friends_bonus | commission_bonus_999 | friend_deposit_bonus_4215 | weekly_sports_bonus | daily_sports_bonus | sport_win_streak | lottery_gift_132467")
    private String kind = "lucky_bonus_apply";

    @Schema(description = "sport_win_streak：review=提交申请过审 | claim=领取红包")
    private String phase;

    @Schema(description = "幸运加码：注单 transaction_id")
    private String transactionId;

    @Schema(description = "天天好运金：统计日 Y-m-d")
    private String statDate;

    @Schema(description = "天天好运金：达标关卡")
    private Integer level;

    @Schema(description = "活动 id")
    private Long activityId;

    @NotNull
    @Schema(description = "当前私聊 AI 机器人 userId")
    private Long botUserId;
}
