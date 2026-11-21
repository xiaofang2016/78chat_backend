package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.service.LuckyBetSuffixService;
import com.bx.implatform.vo.ai.LuckyBonusApplyCardVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 AI 工具结果构建活动领取卡片（与 AI 正文分开发送）。
 */
public final class ActivityApplyCardBuilder {

    private ActivityApplyCardBuilder() {
    }

    public static LuckyBonusApplyCardVO fromLuckyBetToolResult(JSONObject toolResult, Long botUserId) {
        List<LuckyBonusApplyCardVO> cards = fromLuckyBetToolResults(toolResult, botUserId);
        return cards.isEmpty() ? null : cards.get(0);
    }

    /** 每条 applicable=1 的注单各一张领取卡片。 */
    public static List<LuckyBonusApplyCardVO> fromLuckyBetToolResults(JSONObject toolResult, Long botUserId) {
        List<LuckyBonusApplyCardVO> cards = new ArrayList<>();
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return cards;
        }
        JSONObject data = toolResult.getJSONObject("data");
        if (data == null) {
            return cards;
        }
        Integer code = data.getInteger("code");
        if (code != null && code != 0) {
            return cards;
        }
        Set<String> seenTxn = new LinkedHashSet<>();
        for (JSONObject match : LuckyBetSuffixServiceImpl.listApplicable(data)) {
            LuckyBonusApplyCardVO card = fromLuckyBetMatch(match, botUserId);
            if (card == null) {
                continue;
            }
            String txn = card.getTransactionId();
            if (txn != null && !txn.isBlank()) {
                if (!seenTxn.add(txn)) {
                    continue;
                }
            }
            cards.add(card);
        }
        return cards;
    }

    public static LuckyBonusApplyCardVO fromLuckyBetMatch(JSONObject match, Long botUserId) {
        if (match == null || !Integer.valueOf(1).equals(match.getInteger("applicable"))) {
            return null;
        }
        if (Boolean.TRUE.equals(match.getBoolean("already_claimed"))) {
            return null;
        }
        BigDecimal bonus = match.getBigDecimal("bonus");
        if (bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
        card.setKind("lucky_bonus_apply");
        card.setBonus(bonus.setScale(2, RoundingMode.HALF_UP));
        card.setTransactionId(match.getString("transaction_id"));
        card.setActivityId(LuckyBetSuffixService.DEFAULT_ACTIVITY_ID);
        card.setBotUserId(botUserId);
        String suffix = match.getString("suffix");
        BigDecimal bet = match.getBigDecimal("bet");
        card.setSuffix(suffix);
        card.setBet(bet);
        card.setButtonText("申请加码金");
        String betStr = bet == null ? "0.00" : bet.setScale(2, RoundingMode.HALF_UP).toPlainString();
        card.setText(String.format("注单尾号 %s，投注 ¥%s", suffix == null ? "" : suffix, betStr));
        return card;
    }

    public static LuckyBonusApplyCardVO fromDailyGoodLuckGoldToolResult(JSONObject toolResult, Long botUserId) {
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return null;
        }
        JSONObject data = toolResult.getJSONObject("data");
        if (data == null) {
            return null;
        }
        Integer code = data.getInteger("code");
        if (code != null && code != 0) {
            return null;
        }
        return fromDailyGoodLuckGoldStatus(data, botUserId);
    }

    public static LuckyBonusApplyCardVO fromDailyGoodLuckGoldStatus(JSONObject status, Long botUserId) {
        if (status == null) {
            return null;
        }
        if (!Boolean.TRUE.equals(status.getBoolean("current_claimable"))) {
            return null;
        }
        int level = status.getIntValue("current_level");
        BigDecimal bonus = status.getBigDecimal("current_bonus");
        if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (Boolean.TRUE.equals(status.getBoolean("already_claimed"))) {
            return null;
        }
        String statDate = status.getString("stat_date");
        if (statDate == null || statDate.isBlank()) {
            return null;
        }

        LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
        card.setKind("daily_good_luck_gold");
        card.setBonus(bonus.setScale(2, RoundingMode.HALF_UP));
        card.setStatDate(statDate);
        card.setCurrentLevel(level);
        card.setBotUserId(botUserId);
        Long activityId = status.getLong("activity_id");
        if (activityId != null && activityId > 0) {
            card.setActivityId(activityId);
        }
        String tradeNo = status.getString("claim_trade_no");
        card.setTransactionId(tradeNo != null && !tradeNo.isBlank() ? tradeNo : ("daily-gold-" + statDate + "-L" + level));
        card.setButtonText("领取天天好运金");
        BigDecimal adjusted = status.getBigDecimal("adjusted_loss");
        card.setText(String.format("昨日电子/捕鱼/棋牌/德州有效负盈利 ¥%s，已达第 %d 关",
            adjusted == null ? "0.00" : adjusted.setScale(2, RoundingMode.HALF_UP).toPlainString(), level));
        return card;
    }

    public static List<LuckyBonusApplyCardVO> fromInviteFriendsQuotaToolResults(JSONObject toolResult, Long botUserId) {
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return List.of();
        }
        JSONObject data = toolResult.getJSONObject("data");
        return fromInviteFriendsQuotaResults(data, botUserId);
    }

    /** 每条可领档位各一张「领取邀请彩礼」卡片。 */
    public static List<LuckyBonusApplyCardVO> fromInviteFriendsQuotaResults(JSONObject quotaData, Long botUserId) {
        List<LuckyBonusApplyCardVO> cards = new ArrayList<>();
        if (quotaData == null) {
            return cards;
        }
        JSONObject invite = quotaData.getJSONObject("invite_friends");
        if (invite == null) {
            return cards;
        }
        JSONArray claimable = invite.getJSONArray("claimable_tiers");
        if (claimable == null || claimable.isEmpty()) {
            return cards;
        }
        if (Boolean.FALSE.equals(quotaData.getBoolean("canClaim"))) {
            return cards;
        }
        Long activityId = invite.getLong("activity_id");
        int valid = invite.getIntValue("valid_promotion_count");
        Set<String> seenTxn = new LinkedHashSet<>();
        Set<Integer> claimedLevels = claimedInviteTierLevels(invite);

        for (int i = 0; i < claimable.size(); i++) {
            JSONObject tier = claimable.getJSONObject(i);
            if (tier == null) {
                continue;
            }
            int level = tier.getIntValue("level");
            if (claimedLevels.contains(level)) {
                continue;
            }
            BigDecimal bonus = tier.getBigDecimal("bonus");
            if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String tradeNo = tier.getString("claim_trade_no");
            if (tradeNo == null || tradeNo.isBlank()) {
                tradeNo = "invite-bonus-" + (activityId != null ? activityId : 0) + "-L" + level;
            }
            if (!seenTxn.add(tradeNo)) {
                continue;
            }

            LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
            card.setKind("invite_friends_bonus");
            card.setBonus(bonus.setScale(2, RoundingMode.HALF_UP));
            card.setCurrentLevel(level);
            card.setBotUserId(botUserId);
            if (activityId != null && activityId > 0) {
                card.setActivityId(activityId);
            }
            card.setTransactionId(tradeNo);
            card.setButtonText("领取邀请彩礼");
            card.setText(String.format("有效推广 %d 人，第 %d 档可领彩礼 ¥%s",
                valid, level, card.getBonus().toPlainString()));
            cards.add(card);
        }
        return cards;
    }

    public static List<LuckyBonusApplyCardVO> fromCommissionBonusToolResults(JSONObject toolResult, Long botUserId) {
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return List.of();
        }
        JSONObject data = toolResult.getJSONObject("data");
        return fromCommissionBonusResults(data, botUserId);
    }

    /** 每条可领佣金加码档各一张卡片。 */
    public static List<LuckyBonusApplyCardVO> fromCommissionBonusResults(JSONObject status, Long botUserId) {
        List<LuckyBonusApplyCardVO> cards = new ArrayList<>();
        if (status == null) {
            return cards;
        }
        Integer code = status.getInteger("code");
        if (code != null && code != 0) {
            return cards;
        }
        if (!Boolean.TRUE.equals(status.getBoolean("can_claim"))) {
            return cards;
        }
        JSONArray claimable = status.getJSONArray("claimable_tiers");
        if (claimable == null || claimable.isEmpty()) {
            return cards;
        }
        Long activityId = status.getLong("activity_id");
        double claimedCommission = status.getDoubleValue("claimed_commission_total");
        Set<String> seenTxn = new LinkedHashSet<>();

        for (int i = 0; i < claimable.size(); i++) {
            JSONObject tier = claimable.getJSONObject(i);
            if (tier == null) {
                continue;
            }
            int level = tier.getIntValue("level");
            BigDecimal bonus = tier.getBigDecimal("bonus");
            if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String tradeNo = tier.getString("claim_trade_no");
            if (tradeNo == null || tradeNo.isBlank()) {
                tradeNo = "commission-bonus-" + (activityId != null ? activityId : 0) + "-L" + level;
            }
            if (!seenTxn.add(tradeNo)) {
                continue;
            }

            LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
            card.setKind("commission_bonus_999");
            card.setBonus(bonus.setScale(2, RoundingMode.HALF_UP));
            card.setCurrentLevel(level);
            card.setBotUserId(botUserId);
            if (activityId != null && activityId > 0) {
                card.setActivityId(activityId);
            }
            card.setTransactionId(tradeNo);
            card.setButtonText("领取佣金加码");
            BigDecimal threshold = tier.getBigDecimal("commission_threshold");
            String claimedStr = BigDecimal.valueOf(claimedCommission).setScale(2, RoundingMode.HALF_UP).toPlainString();
            if (threshold != null) {
                card.setText(String.format("累计已领佣金 ¥%s（门槛 ¥%s），第 %d 档加码 ¥%s（3倍流水）",
                    claimedStr,
                    threshold.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    level,
                    card.getBonus().toPlainString()));
            } else {
                card.setText(String.format("累计已领佣金 ¥%s，第 %d 档加码 ¥%s（3倍流水）",
                    claimedStr, level, card.getBonus().toPlainString()));
            }
            cards.add(card);
        }
        return cards;
    }

    public static List<LuckyBonusApplyCardVO> fromFriendDepositBonusToolResults(JSONObject toolResult, Long botUserId) {
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return List.of();
        }
        JSONObject data = toolResult.getJSONObject("data");
        return fromFriendDepositBonusResults(data, botUserId);
    }

    /** 每条可领好友存款赏金档各一张卡片。 */
    public static List<LuckyBonusApplyCardVO> fromFriendDepositBonusResults(JSONObject status, Long botUserId) {
        List<LuckyBonusApplyCardVO> cards = new ArrayList<>();
        if (status == null) {
            return cards;
        }
        Integer code = status.getInteger("code");
        if (code != null && code != 0) {
            return cards;
        }
        if (!Boolean.TRUE.equals(status.getBoolean("can_claim"))) {
            return cards;
        }
        JSONArray claimable = status.getJSONArray("claimable_tiers");
        if (claimable == null || claimable.isEmpty()) {
            return cards;
        }
        Long activityId = status.getLong("activity_id");
        String weekStart = status.getString("week_start");
        double weeklyDeposit = status.getDoubleValue("weekly_friend_deposit_total");
        Set<String> seenTxn = new LinkedHashSet<>();

        for (int i = 0; i < claimable.size(); i++) {
            JSONObject tier = claimable.getJSONObject(i);
            if (tier == null) {
                continue;
            }
            int level = tier.getIntValue("level");
            BigDecimal bonus = tier.getBigDecimal("bonus");
            if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String tradeNo = tier.getString("claim_trade_no");
            if (tradeNo == null || tradeNo.isBlank()) {
                String ws = tier.getString("week_start");
                if (ws == null || ws.isBlank()) {
                    ws = weekStart != null ? weekStart : "";
                }
                tradeNo = "friend-deposit-bonus-" + (activityId != null ? activityId : 0) + "-" + ws + "-L" + level;
            }
            if (!seenTxn.add(tradeNo)) {
                continue;
            }

            LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
            card.setKind("friend_deposit_bonus_4215");
            card.setBonus(bonus.setScale(2, RoundingMode.HALF_UP));
            card.setCurrentLevel(level);
            card.setBotUserId(botUserId);
            if (activityId != null && activityId > 0) {
                card.setActivityId(activityId);
            }
            card.setTransactionId(tradeNo);
            card.setButtonText("领取好友存款赏金");
            BigDecimal threshold = tier.getBigDecimal("deposit_threshold");
            String depositStr = BigDecimal.valueOf(weeklyDeposit).setScale(2, RoundingMode.HALF_UP).toPlainString();
            String ws = tier.getString("week_start");
            if (ws == null || ws.isBlank()) {
                ws = weekStart;
            }
            if (threshold != null) {
                card.setText(String.format("当周好友累计存款 ¥%s（门槛 ¥%s），第 %d 档赏金 ¥%s（3倍流水）",
                    depositStr,
                    threshold.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    level,
                    card.getBonus().toPlainString()));
            } else {
                card.setText(String.format("当周好友累计存款 ¥%s，第 %d 档赏金 ¥%s（3倍流水）",
                    depositStr, level, card.getBonus().toPlainString()));
            }
            cards.add(card);
        }
        return cards;
    }

    public static List<LuckyBonusApplyCardVO> fromWeeklySportsBonusToolResults(JSONObject toolResult, Long botUserId) {
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return List.of();
        }
        JSONObject data = toolResult.getJSONObject("data");
        LuckyBonusApplyCardVO card = fromWeeklySportsBonusResults(data, botUserId);
        return card == null ? List.of() : List.of(card);
    }

    /** 上周体育有效投注最高档，次周一 12:00 起 3 天内可领一张卡片。 */
    public static LuckyBonusApplyCardVO fromWeeklySportsBonusResults(JSONObject status, Long botUserId) {
        if (status == null) {
            return null;
        }
        JSONObject claim = status.getJSONObject("claim");
        if (claim == null || !Boolean.TRUE.equals(claim.getBoolean("can_claim"))) {
            return null;
        }
        int level = claim.getIntValue("level");
        BigDecimal bonus = claim.getBigDecimal("bonus");
        if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (Boolean.TRUE.equals(claim.getBoolean("already_claimed"))) {
            return null;
        }

        String weekStart = claim.getString("week_start");
        String tradeNo = claim.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            Long activityId = status.getLong("activity_id");
            tradeNo = "weekly-sports-" + (activityId != null ? activityId : 0) + "-" + weekStart;
        }

        LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
        card.setKind("weekly_sports_bonus");
        card.setBonus(bonus.setScale(2, RoundingMode.HALF_UP));
        card.setCurrentLevel(level);
        card.setBotUserId(botUserId);
        Long activityId = status.getLong("activity_id");
        if (activityId != null && activityId > 0) {
            card.setActivityId(activityId);
        }
        card.setTransactionId(tradeNo);
        card.setButtonText("领取体育周周领");
        double weeklyBet = claim.getDoubleValue("weekly_valid_bet");
        String betStr = BigDecimal.valueOf(weeklyBet).setScale(2, RoundingMode.HALF_UP).toPlainString();
        card.setText(String.format("上周体育有效投注 ¥%s，第 %d 档彩金 ¥%s（3倍体育流水）",
            betStr, level, card.getBonus().toPlainString()));
        return card;
    }

    public static List<LuckyBonusApplyCardVO> fromDailySportsBonusToolResults(JSONObject toolResult, Long botUserId) {
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return List.of();
        }
        JSONObject data = toolResult.getJSONObject("data");
        LuckyBonusApplyCardVO card = fromDailySportsBonusResults(data, botUserId);
        return card == null ? List.of() : List.of(card);
    }

    /** 昨日体育有效投注最高档，次日 00:00 至 23:59 可领一张卡片。 */
    public static LuckyBonusApplyCardVO fromDailySportsBonusResults(JSONObject status, Long botUserId) {
        if (status == null) {
            return null;
        }
        JSONObject claim = status.getJSONObject("claim");
        if (claim == null || !Boolean.TRUE.equals(claim.getBoolean("can_claim"))) {
            return null;
        }
        int level = claim.getIntValue("level");
        BigDecimal bonus = claim.getBigDecimal("bonus");
        if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (Boolean.TRUE.equals(claim.getBoolean("already_claimed"))) {
            return null;
        }

        String statDate = claim.getString("stat_date");
        String tradeNo = claim.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            Long activityId = status.getLong("activity_id");
            tradeNo = "daily-sports-" + (activityId != null ? activityId : 0) + "-" + statDate;
        }

        LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
        card.setKind("daily_sports_bonus");
        card.setBonus(bonus.setScale(2, RoundingMode.HALF_UP));
        card.setCurrentLevel(level);
        card.setBotUserId(botUserId);
        Long activityId = status.getLong("activity_id");
        if (activityId != null && activityId > 0) {
            card.setActivityId(activityId);
        }
        card.setTransactionId(tradeNo);
        card.setButtonText("领取日投注闯关");
        double dailyBet = claim.getDoubleValue("daily_valid_bet");
        String betStr = BigDecimal.valueOf(dailyBet).setScale(2, RoundingMode.HALF_UP).toPlainString();
        card.setText(String.format("昨日体育有效投注 ¥%s，第 %d 档彩金 ¥%s（3倍体育流水）",
            betStr, level, card.getBonus().toPlainString()));
        return card;
    }

    /** 当前连红达档且本周未领：先提交申请，通过后点领取红包。 */
    public static LuckyBonusApplyCardVO fromSportWinStreakResults(JSONObject status, Long botUserId) {
        return fromSportWinStreakResults(status, botUserId, 0L);
    }

    /** @param fallbackActivityId box-im 配置的 sport-win-streak-id，API 未返回 activity_id 时使用 */
    public static LuckyBonusApplyCardVO fromSportWinStreakResults(
        JSONObject status,
        Long botUserId,
        long fallbackActivityId
    ) {
        if (status == null) {
            return null;
        }
        JSONObject claim = status.getJSONObject("claim");
        if (claim == null) {
            return null;
        }
        JSONObject streak = status.getJSONObject("streak");
        boolean alreadyClaimed = Boolean.TRUE.equals(claim.getBoolean("already_claimed_this_week"));
        if (alreadyClaimed) {
            return null;
        }

        boolean canApply = Boolean.TRUE.equals(claim.getBoolean("can_apply"));
        boolean canClaimRedpacket = Boolean.TRUE.equals(claim.getBoolean("can_claim_redpacket"));
        boolean applied = Boolean.TRUE.equals(claim.getBoolean("applied"));

        int level = claim.getIntValue("level");
        if (level <= 0 && streak != null) {
            level = streak.getIntValue("current_level");
        }
        BigDecimal bonus = claim.getBigDecimal("bonus");
        if ((bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) && streak != null) {
            bonus = streak.getBigDecimal("current_bonus");
        }

        if (!canApply && !canClaimRedpacket) {
            JSONObject review = claim.getJSONObject("review");
            boolean eligible = review == null
                || !review.containsKey("eligible")
                || Boolean.TRUE.equals(review.getBoolean("eligible"));
            if (eligible && level > 0 && bonus != null && bonus.compareTo(BigDecimal.ZERO) > 0) {
                if (applied) {
                    canClaimRedpacket = true;
                } else {
                    canApply = true;
                }
            }
        }

        if (!canApply && !canClaimRedpacket) {
            if (!Boolean.TRUE.equals(claim.getBoolean("can_claim"))) {
                return null;
            }
            canClaimRedpacket = true;
        }

        if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        String weekStart = claim.getString("week_start");
        Long activityId = status.getLong("activity_id");
        if (activityId == null || activityId <= 0) {
            activityId = fallbackActivityId > 0 ? fallbackActivityId : null;
        }
        String tradeNo = claim.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            tradeNo = "sport-streak-" + (activityId != null ? activityId : 0) + "-" + weekStart;
        }

        LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
        card.setKind("sport_win_streak");
        card.setBonus(bonus.setScale(2, RoundingMode.HALF_UP));
        card.setCurrentLevel(level);
        card.setBotUserId(botUserId);
        if (activityId != null && activityId > 0) {
            card.setActivityId(activityId);
        }
        card.setTransactionId(tradeNo);
        card.setReviewPassed(canClaimRedpacket);
        card.setApplied(applied && !canClaimRedpacket);
        card.setButtonText(canClaimRedpacket ? "领取红包" : "提交申请");
        int currentStreak = streak == null ? 0 : streak.getIntValue("current_streak");
        if (canClaimRedpacket) {
            card.setText(String.format("资格审核已通过：当前 %d 连红，第 %d 档彩金 ¥%s（3倍体育流水），请点击领取红包",
                currentStreak, level, card.getBonus().toPlainString()));
        } else {
            card.setText(String.format("当前 %d 连红，第 %d 档彩金 ¥%s（3倍体育流水）；提交后系统自动核查，通过即可领取红包",
                currentStreak, level, card.getBonus().toPlainString()));
        }
        return card;
    }

    public static List<LuckyBonusApplyCardVO> fromSportWinStreakToolResults(JSONObject toolResult, Long botUserId) {
        return fromSportWinStreakToolResults(toolResult, botUserId, 0L);
    }

    public static List<LuckyBonusApplyCardVO> fromSportWinStreakToolResults(
        JSONObject toolResult,
        Long botUserId,
        long fallbackActivityId
    ) {
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return List.of();
        }
        JSONObject data = toolResult.getJSONObject("data");
        LuckyBonusApplyCardVO card = fromSportWinStreakResults(data, botUserId, fallbackActivityId);
        return card == null ? List.of() : List.of(card);
    }

    /** 从活动总览工具结果中，为所有可领活动各生成领取卡片。 */
    public static List<LuckyBonusApplyCardVO> fromAvailableActivitiesToolResults(JSONObject toolResult, Long botUserId) {
        return fromAvailableActivitiesToolResults(toolResult, botUserId, 0L);
    }

    public static List<LuckyBonusApplyCardVO> fromAvailableActivitiesToolResults(
        JSONObject toolResult,
        Long botUserId,
        long sportWinStreakActivityId
    ) {
        List<LuckyBonusApplyCardVO> cards = new ArrayList<>();
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return cards;
        }
        JSONObject data = toolResult.getJSONObject("data");
        if (data == null) {
            return cards;
        }
        Object statusesRaw = data.get("statuses");
        if (!(statusesRaw instanceof JSONObject)) {
            return cards;
        }
        JSONObject statuses = (JSONObject) statusesRaw;
        cards.addAll(fromFriendDepositBonusResults(statuses.getJSONObject("friend_deposit_bonus_4215"), botUserId));
        cards.addAll(fromCommissionBonusResults(statuses.getJSONObject("commission_bonus_999"), botUserId));
        LuckyBonusApplyCardVO weeklySports = fromWeeklySportsBonusResults(statuses.getJSONObject("weekly_sports_bonus"), botUserId);
        if (weeklySports != null) {
            cards.add(weeklySports);
        }
        LuckyBonusApplyCardVO dailySports = fromDailySportsBonusResults(statuses.getJSONObject("daily_sports_bonus"), botUserId);
        if (dailySports != null) {
            cards.add(dailySports);
        }
        LuckyBonusApplyCardVO sportWinStreak = fromSportWinStreakResults(
            statuses.getJSONObject("sport_win_streak"), botUserId, sportWinStreakActivityId);
        if (sportWinStreak != null) {
            cards.add(sportWinStreak);
        }
        cards.addAll(fromInviteFriendsQuotaResults(statuses.getJSONObject("invite_friends_bonus"), botUserId));
        LuckyBonusApplyCardVO daily = fromDailyGoodLuckGoldStatus(statuses.getJSONObject("daily_good_luck_gold"), botUserId);
        if (daily != null) {
            cards.add(daily);
        }
        JSONObject luckyWrap = new JSONObject();
        luckyWrap.put("ok", true);
        luckyWrap.put("data", statuses.getJSONObject("lucky_bet_suffix"));
        cards.addAll(fromLuckyBetToolResults(luckyWrap, botUserId));
        return cards;
    }

    /** 活动总览展示卡（无领取按钮，仅列出全部可参与活动）。 */
    public static LuckyBonusApplyCardVO fromAvailableActivitiesOverviewCard(String overviewText, Long botUserId) {
        if (overviewText == null || overviewText.isBlank()) {
            return null;
        }
        LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
        card.setKind("available_activities");
        card.setText(overviewText.trim());
        card.setButtonText("");
        card.setShowButton(false);
        card.setBotUserId(botUserId);
        return card;
    }

    /** 每条可领存款订单各一张送彩票卡片（当期 21:30 截止，每笔存款仅可领一次）。 */
    public static List<LuckyBonusApplyCardVO> fromLotteryGiftToolResults(JSONObject toolResult, Long botUserId) {
        if (toolResult == null || !Boolean.TRUE.equals(toolResult.getBoolean("ok"))) {
            return List.of();
        }
        JSONObject data = toolResult.getJSONObject("data");
        return fromLotteryGiftResults(data, botUserId);
    }

    public static List<LuckyBonusApplyCardVO> fromLotteryGiftResults(JSONObject status, Long botUserId) {
        List<LuckyBonusApplyCardVO> cards = new ArrayList<>();
        if (status == null) {
            return cards;
        }
        if (!Boolean.TRUE.equals(status.getBoolean("can_claim"))) {
            return cards;
        }
        JSONArray claimable = status.getJSONArray("claimable_items");
        if (claimable == null || claimable.isEmpty()) {
            return cards;
        }
        Long activityId = status.getLong("activity_id");
        String periodEnd = status.getString("period_end");
        Set<String> seenTxn = new LinkedHashSet<>();

        for (int i = 0; i < claimable.size(); i++) {
            JSONObject item = claimable.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String orderTradeNo = item.getString("order_trade_no");
            if (orderTradeNo == null || orderTradeNo.isBlank()) {
                continue;
            }
            int ticketCount = item.getIntValue("ticket_count");
            if (ticketCount <= 0) {
                continue;
            }
            BigDecimal giftAmount = lotteryGiftAmount(ticketCount);
            if (!seenTxn.add(orderTradeNo)) {
                continue;
            }

            LuckyBonusApplyCardVO card = new LuckyBonusApplyCardVO();
            card.setKind("lottery_gift_132467");
            card.setBonus(giftAmount.setScale(2, RoundingMode.HALF_UP));
            card.setBotUserId(botUserId);
            if (activityId != null && activityId > 0) {
                card.setActivityId(activityId);
            }
            card.setTransactionId(orderTradeNo);
            card.setButtonText("领取彩票并选号");
            BigDecimal depositAmount = item.getBigDecimal("deposit_amount");
            String depositStr = depositAmount == null ? "0.00"
                : depositAmount.setScale(2, RoundingMode.HALF_UP).toPlainString();
            String endHint = periodEnd != null && !periodEnd.isBlank() ? periodEnd : "21:30";
            card.setText(String.format("存款 ¥%s → %d 张票（额度 ¥%s），当期 %s 截止",
                depositStr, ticketCount, card.getBonus().toPlainString(), endHint));
            cards.add(card);
        }
        return cards;
    }

    /** 送彩票：每张 2 元，展示额度必须为 2 的整数倍。 */
    private static BigDecimal lotteryGiftAmount(int ticketCount) {
        if (ticketCount <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(ticketCount * 2L).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** 从 invite_friends.tiers 汇总已领档位，防止 claimable_tiers 与领取记录不一致时仍发按钮。 */
    public static Set<Integer> claimedInviteTierLevels(JSONObject invite) {
        Set<Integer> claimed = new HashSet<>();
        if (invite == null) {
            return claimed;
        }
        JSONArray tiers = invite.getJSONArray("tiers");
        if (tiers == null) {
            return claimed;
        }
        for (int i = 0; i < tiers.size(); i++) {
            JSONObject tier = tiers.getJSONObject(i);
            if (tier != null && Boolean.TRUE.equals(tier.getBoolean("claimed"))) {
                claimed.add(tier.getIntValue("level"));
            }
        }
        return claimed;
    }
}
