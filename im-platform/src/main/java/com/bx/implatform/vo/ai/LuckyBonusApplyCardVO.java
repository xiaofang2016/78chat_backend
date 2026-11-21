package com.bx.implatform.vo.ai;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 私聊活动领取交互卡片（MessageType.LUCKY_BONUS_APPLY 的 content JSON）。
 * kind: lucky_bonus_apply | daily_good_luck_gold
 */
@Data
public class LuckyBonusApplyCardVO {

    private String kind = "lucky_bonus_apply";

    /** 说明文案（卡片上方提示） */
    private String text;

    private String buttonText = "申请加码金";

    private BigDecimal bonus;

    private String transactionId;

    private Long activityId;

    /** 发起会话的 AI 机器人 id（申请时回传） */
    private Long botUserId;

    /** 是否展示领取按钮；false 时仅作说明（或由 orchestrator 拆成纯文本消息） */
    private Boolean showButton = true;

    /** 是否已申请（已生成红包，待用户开红包；不可再次点申请） */
    private Boolean applied = false;

    /** 是否已领取（红包已开且入账成功） */
    private Boolean claimed = false;

    /** 仅展示领取按钮（说明文案已由 orchestrator 单独发文本消息） */
    private Boolean buttonOnly = false;

    private String suffix;

    private BigDecimal bet;

    /** 天天好运金：统计日 Y-m-d */
    private String statDate;

    /** 天天好运金：当前达标关卡 */
    private Integer currentLevel;

    /** 体育连红：资格审核已通过，按钮切换为「领取红包」 */
    private Boolean reviewPassed;
}
