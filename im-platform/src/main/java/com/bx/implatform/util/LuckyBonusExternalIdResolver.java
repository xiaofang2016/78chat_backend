package com.bx.implatform.util;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.service.LuckyBetSuffixService;
import com.bx.implatform.vo.ai.LuckyBonusApplyCardVO;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据活动领取卡片字段推导红包 externalId（与 LuckyBonusServiceImpl 申请逻辑一致）。
 */
public final class LuckyBonusExternalIdResolver {

    private static final Pattern FRIEND_DEPOSIT_TXN = Pattern
        .compile("^friend-deposit-bonus-\\d+-(\\d{4}-\\d{2}-\\d{2})-L(\\d+)$");
    private static final Pattern WEEKLY_SPORTS_TXN = Pattern
        .compile("^weekly-sports-\\d+-(\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern DAILY_SPORTS_TXN = Pattern
        .compile("^daily-sports-\\d+-(\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern SPORT_STREAK_TXN = Pattern
        .compile("^sport-streak-\\d+-(\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern DAILY_GOLD_TXN = Pattern
        .compile("^daily-gold-(\\d{4})-(\\d{2})-(\\d{2})-L(\\d+)$");

    private LuckyBonusExternalIdResolver() {
    }

    public static String resolve(LuckyBonusApplyCardVO card, Long userId) {
        if (card == null || userId == null) {
            return null;
        }
        return resolve(card.getKind(), card.getTransactionId(), card.getActivityId(),
            card.getStatDate(), card.getCurrentLevel(), userId);
    }

    public static String resolve(JSONObject card, Long userId) {
        if (card == null || userId == null) {
            return null;
        }
        return resolve(card.getString("kind"), card.getString("transactionId"),
            card.getLong("activityId"), card.getString("statDate"),
            card.getInteger("currentLevel"), userId);
    }

    private static String resolve(String kind, String transactionId, Long activityId,
                                  String statDate, Integer currentLevel, Long userId) {
        if (kind == null || kind.isBlank()) {
            kind = "lucky_bonus_apply";
        }
        String txn = transactionId == null ? "" : transactionId.trim();
        long actId = activityId != null && activityId > 0 ? activityId : 0L;

        return switch (kind) {
            case "lucky_bonus_apply" -> {
                if (txn.isEmpty()) {
                    yield null;
                }
                long id = actId > 0 ? actId : LuckyBetSuffixService.DEFAULT_ACTIVITY_ID;
                yield "lucky-" + id + "-" + txn + "-" + userId;
            }
            case "daily_good_luck_gold" -> {
                String date = statDate == null ? "" : statDate.trim();
                Integer level = currentLevel;
                if ((date.isEmpty() || level == null || level <= 0) && txn.startsWith("daily-gold-")) {
                    Matcher m = DAILY_GOLD_TXN.matcher(txn);
                    if (m.matches()) {
                        date = m.group(1) + "-" + m.group(2) + "-" + m.group(3);
                        level = Integer.parseInt(m.group(4));
                    }
                }
                if (date.isEmpty() || level == null || level <= 0) {
                    yield null;
                }
                long id = actId > 0 ? actId : 0L;
                if (id <= 0) {
                    yield null;
                }
                yield "daily-gold-" + id + "-" + date + "-lvl" + level + "-" + userId;
            }
            case "commission_bonus_999" -> {
                Integer level = currentLevel;
                if ((level == null || level <= 0) && txn.contains("-L")) {
                    level = parseLevelSuffix(txn);
                }
                if (level == null || level <= 0 || actId <= 0) {
                    yield null;
                }
                yield "commission-" + actId + "-L" + level + "-" + userId;
            }
            case "friend_deposit_bonus_4215" -> {
                String weekStart = "";
                Integer level = currentLevel;
                Matcher m = FRIEND_DEPOSIT_TXN.matcher(txn);
                if (m.matches()) {
                    weekStart = m.group(1);
                    if (level == null || level <= 0) {
                        level = Integer.parseInt(m.group(2));
                    }
                } else if (level == null || level <= 0) {
                    level = parseLevelSuffix(txn);
                }
                if (weekStart.isEmpty() || level == null || level <= 0 || actId <= 0) {
                    yield null;
                }
                yield "friend-deposit-" + actId + "-" + weekStart + "-L" + level + "-" + userId;
            }
            case "weekly_sports_bonus" -> {
                String weekStart = "";
                Matcher wm = WEEKLY_SPORTS_TXN.matcher(txn);
                if (wm.matches()) {
                    weekStart = wm.group(1);
                }
                if (weekStart.isEmpty() || actId <= 0) {
                    yield null;
                }
                yield "weekly-sports-" + actId + "-" + weekStart + "-" + userId;
            }
            case "daily_sports_bonus" -> {
                String date = statDate == null ? "" : statDate.trim();
                Matcher dm = DAILY_SPORTS_TXN.matcher(txn);
                if (dm.matches()) {
                    date = dm.group(1);
                }
                if (date.isEmpty() || actId <= 0) {
                    yield null;
                }
                yield "daily-sports-" + actId + "-" + date + "-" + userId;
            }
            case "sport_win_streak" -> {
                String weekStart = "";
                Matcher sm = SPORT_STREAK_TXN.matcher(txn);
                if (sm.matches()) {
                    weekStart = sm.group(1);
                }
                if (weekStart.isEmpty() || actId <= 0) {
                    yield null;
                }
                yield "sport-streak-" + actId + "-" + weekStart + "-" + userId;
            }
            case "invite_friends_bonus" -> {
                Integer level = currentLevel;
                if ((level == null || level <= 0) && txn.contains("-L")) {
                    level = parseLevelSuffix(txn);
                }
                if (level == null || level <= 0 || actId <= 0) {
                    yield null;
                }
                yield "invite-" + actId + "-L" + level + "-" + userId;
            }
            default -> null;
        };
    }

    private static Integer parseLevelSuffix(String txn) {
        if (StrUtil.isBlank(txn) || !txn.contains("-L")) {
            return null;
        }
        int idx = txn.lastIndexOf("-L");
        try {
            return Integer.parseInt(txn.substring(idx + 2));
        } catch (Exception e) {
            return null;
        }
    }
}
