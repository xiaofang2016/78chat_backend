package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.config.props.BoximIntegrationProperties;
import com.bx.implatform.dto.LuckyBonusApplyDTO;
import com.bx.implatform.entity.Redpacket;
import com.bx.implatform.entity.User;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.RedpacketMapper;
import com.bx.implatform.service.BotPrivateMessageService;
import com.bx.implatform.service.CommissionBonus999Service;
import com.bx.implatform.service.DailyGoodLuckGoldService;
import com.bx.implatform.service.FriendDepositBonus4215Service;
import com.bx.implatform.service.InviteFriendsBonusService;
import com.bx.implatform.service.LuckyBetSuffixService;
import com.bx.implatform.service.LuckyBonusService;
import com.bx.implatform.service.LotteryGift132467Service;
import com.bx.implatform.service.SportWeeklyBonusService;
import com.bx.implatform.service.SportDailyBonusService;
import com.bx.implatform.service.SportWinStreakService;
import com.bx.implatform.service.UserService;
import com.bx.implatform.vo.LuckyBonusApplyResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class LuckyBonusServiceImpl implements LuckyBonusService {

    private static final String LUCKY_SUCCESS_MSG = "恭喜您成功申请了电子活动加码金。";
    private static final String DAILY_GOLD_SUCCESS_MSG = "恭喜您，天天好运金已为您准备好，请领取红包。";
    private static final String INVITE_SUCCESS_MSG = "恭喜您，邀请好友彩礼已为您准备好，请领取红包。";
    private static final String COMMISSION_BONUS_SUCCESS_MSG = "恭喜您，佣金加码彩金已为您准备好，请领取红包。";
    private static final String FRIEND_DEPOSIT_BONUS_SUCCESS_MSG = "恭喜您，好友存款赏金已为您准备好，请领取红包。";
    private static final String WEEKLY_SPORTS_BONUS_SUCCESS_MSG = "恭喜您，体育周周领彩金已为您准备好，请领取红包。";
    private static final String DAILY_SPORTS_BONUS_SUCCESS_MSG = "恭喜您，日投注闯关彩金已为您准备好，请领取红包。";
    private static final String SPORT_WIN_STREAK_REVIEW_MSG = "体育连红资格审核已通过，请点击下方「领取红包」领取彩金。";
    private static final String SPORT_WIN_STREAK_SUCCESS_MSG = "恭喜您，体育连红挑战彩金已为您准备好，请领取红包。";
    private static final String LOTTERY_GIFT_SUCCESS_MSG = "彩票额度已发放，请在下方选号下注（当期 21:30 截止）。";

    private final LuckyBetSuffixService luckyBetSuffixService;
    private final DailyGoodLuckGoldService dailyGoodLuckGoldService;
    private final InviteFriendsBonusService inviteFriendsBonusService;
    private final CommissionBonus999Service commissionBonus999Service;
    private final FriendDepositBonus4215Service friendDepositBonus4215Service;
    private final SportWeeklyBonusService sportWeeklyBonusService;
    private final SportDailyBonusService sportDailyBonusService;
    private final SportWinStreakService sportWinStreakService;
    private final LotteryGift132467Service lotteryGift132467Service;
    private final BotPrivateMessageService botPrivateMessageService;
    private final RedpacketMapper redpacketMapper;
    private final UserService userService;
    private final BoximIntegrationProperties boximProps;
    private final AagamesBusinessClient aagamesBusinessClient;

    @Override
    @Transactional
    public LuckyBonusApplyResultVO apply(Long userId, LuckyBonusApplyDTO dto) {
        if (userId == null || dto == null) {
            throw new GlobalException(400, "参数无效");
        }
        Long botUserId = dto.getBotUserId();
        if (botUserId == null || botUserId <= 0) {
            throw new GlobalException(400, "botUserId 无效");
        }
        long dailyGoldActivityId = boximProps.getActivities() == null ? 0L : boximProps.getActivities().getDailyGoodLuckGoldId();
        long inviteActivityId = boximProps.getActivities() == null ? 0L : boximProps.getActivities().getInviteFriendsBonusId();
        long commissionActivityId = boximProps.getActivities() == null ? 0L : boximProps.getActivities().getCommissionBonus999Id();
        long friendDepositActivityId = boximProps.getActivities() == null ? 0L
            : boximProps.getActivities().getFriendDepositBonus4215Id();
        long lotteryGiftActivityId = boximProps.getActivities() == null ? 0L
            : boximProps.getActivities().getLotteryGift132467Id();
        long weeklySportsActivityId = boximProps.getActivities() == null ? 0L
            : boximProps.getActivities().getWeeklySportsBonusId();
        long dailySportsActivityId = boximProps.getActivities() == null ? 0L
            : boximProps.getActivities().getDailySportsBonusId();
        long sportWinStreakActivityId = boximProps.getActivities() == null ? 0L
            : boximProps.getActivities().getSportWinStreakId();
        String kind = dto.getKind() == null ? "" : dto.getKind().trim();
        String txn = dto.getTransactionId() == null ? "" : dto.getTransactionId().trim();

        boolean looksLikeLotteryGift =
            "lottery_gift_132467".equals(kind)
                || (lotteryGiftActivityId > 0 && dto.getActivityId() != null
                    && dto.getActivityId() == lotteryGiftActivityId)
                || txn.startsWith("lottery-gift-");

        boolean looksLikeFriendDeposit =
            "friend_deposit_bonus_4215".equals(kind)
                || (friendDepositActivityId > 0 && dto.getActivityId() != null
                    && dto.getActivityId() == friendDepositActivityId)
                || txn.startsWith("friend-deposit-bonus-");

        boolean looksLikeWeeklySports =
            "weekly_sports_bonus".equals(kind)
                || (weeklySportsActivityId > 0 && dto.getActivityId() != null
                    && dto.getActivityId() == weeklySportsActivityId)
                || txn.startsWith("weekly-sports-");

        boolean looksLikeDailySports =
            "daily_sports_bonus".equals(kind)
                || (dailySportsActivityId > 0 && dto.getActivityId() != null
                    && dto.getActivityId() == dailySportsActivityId)
                || txn.startsWith("daily-sports-");

        boolean looksLikeSportWinStreak =
            "sport_win_streak".equals(kind)
                || (sportWinStreakActivityId > 0 && dto.getActivityId() != null
                    && dto.getActivityId() == sportWinStreakActivityId)
                || txn.startsWith("sport-streak-");

        boolean looksLikeCommission =
            "commission_bonus_999".equals(kind)
                || (commissionActivityId > 0 && dto.getActivityId() != null && dto.getActivityId() == commissionActivityId)
                || txn.startsWith("commission-bonus-");

        boolean looksLikeInvite =
            "invite_friends_bonus".equals(kind)
                || (inviteActivityId > 0 && dto.getActivityId() != null && dto.getActivityId() == inviteActivityId)
                || txn.startsWith("invite-bonus-");

        boolean looksLikeDailyGold =
            "daily_good_luck_gold".equals(kind)
                || (dto.getStatDate() != null && !dto.getStatDate().trim().isEmpty())
                || (dailyGoldActivityId > 0 && dto.getActivityId() != null && dto.getActivityId() == dailyGoldActivityId)
                || txn.startsWith("daily-gold-");

        if (looksLikeLotteryGift) {
            throw new GlobalException(40903, "幸运排列五送彩票活动已下架，请前往活动优惠页或联系客服");
        } else if (looksLikeSportWinStreak) {
            return applySportWinStreak(userId, dto, botUserId);
        } else if (looksLikeDailySports) {
            applyDailySportsBonus(userId, dto, botUserId);
        } else if (looksLikeWeeklySports) {
            applyWeeklySportsBonus(userId, dto, botUserId);
        } else if (looksLikeFriendDeposit) {
            applyFriendDepositBonus4215(userId, dto, botUserId);
        } else if (looksLikeCommission) {
            applyCommissionBonus999(userId, dto, botUserId);
        } else if (looksLikeInvite) {
            applyInviteFriendsBonus(userId, dto, botUserId);
        } else if (looksLikeDailyGold) {
            applyDailyGoodLuckGold(userId, dto, botUserId);
        } else {
            applyLuckyBetSuffix(userId, dto, botUserId);
        }
        return successApplyResult();
    }

    private LuckyBonusApplyResultVO applyLotteryGift132467(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        String orderTradeNo = dto.getTransactionId();
        if (orderTradeNo == null || orderTradeNo.isBlank()) {
            throw new GlobalException(400, "存款单号无效");
        }
        orderTradeNo = orderTradeNo.trim();

        JSONObject status = lotteryGift132467Service.fetchStatus(userId);
        JSONArray claimable = status != null ? status.getJSONArray("claimable_items") : null;
        boolean matched = false;
        if (claimable != null) {
            for (int i = 0; i < claimable.size(); i++) {
                JSONObject item = claimable.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String orderNo = item.getString("order_trade_no");
                String claimNo = item.getString("claim_trade_no");
                if (orderTradeNo.equals(orderNo) || orderTradeNo.equals(claimNo)) {
                    if (orderNo != null && !orderNo.isBlank()) {
                        orderTradeNo = orderNo;
                    }
                    matched = true;
                    break;
                }
            }
        }
        // 未在 claimable_items 中仍尝试 claim（aagames 幂等返回 already_claimed + game_url，用于重复打开选号）
        if (!matched && orderTradeNo.startsWith("lottery-gift-")) {
            throw new GlobalException(40903, "该笔存款暂不可领取送彩票");
        }

        JSONObject data = lotteryGift132467Service.claim(userId, orderTradeNo);
        if (data == null) {
            throw new GlobalException(500, "领取失败，请稍后重试");
        }

        String gameUrl = data.getString("game_url");
        if (gameUrl == null || gameUrl.isBlank()) {
            throw new GlobalException(500, "游戏入口获取失败，请稍后重试");
        }

        int ticketCount = data.getIntValue("ticket_count");
        BigDecimal giftAmount = lotteryGiftAmountFromTickets(ticketCount, data.getBigDecimal("gift_amount"));
        String periodKey = data.getString("period_key");
        String resolvedOrder = data.getString("order_trade_no");
        if (resolvedOrder == null || resolvedOrder.isBlank()) {
            resolvedOrder = orderTradeNo;
        }
        boolean alreadyClaimed = isLotteryGiftAlreadyClaimed(data);
        // 仅「当期可领列表中的首次领取」才发 bot 提示；重复打开选号 / 已投注后 reopen 不再骚扰
        boolean freshClaim = matched && !alreadyClaimed;

        if (freshClaim) {
            String msg = String.format("已为您发放 %d 张彩票（额度 ¥%s），请在选号界面完成下注。",
                ticketCount,
                giftAmount == null ? "0.00" : giftAmount.setScale(2, RoundingMode.HALF_UP).toPlainString());
            botPrivateMessageService.sendText(botUserId, userId, msg);
        } else {
            log.info("[lottery-gift-132467] reopen without notify userId={} order={} matched={} alreadyClaimed={}",
                userId, resolvedOrder, matched, alreadyClaimed);
        }
        log.info("[lottery-gift-132467] applied userId={} order={} tickets={} amount={} alreadyClaimed={} freshClaim={}",
            userId, resolvedOrder, ticketCount, giftAmount, alreadyClaimed, freshClaim);

        LuckyBonusApplyResultVO result = new LuckyBonusApplyResultVO();
        result.setKind("lottery_gift_132467");
        result.setGameUrl(gameUrl);
        result.setTicketCount(ticketCount);
        result.setGiftAmount(giftAmount);
        result.setOrderTradeNo(resolvedOrder);
        result.setPeriodKey(periodKey);
        result.setAlreadyClaimed(alreadyClaimed);
        return result;
    }

    private static LuckyBonusApplyResultVO successApplyResult() {
        LuckyBonusApplyResultVO vo = new LuckyBonusApplyResultVO();
        vo.setApplied(true);
        return vo;
    }

    private void applyLuckyBetSuffix(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        String txn = dto.getTransactionId() == null ? "" : dto.getTransactionId().trim();
        if (txn.isEmpty()) {
            throw new GlobalException(400, "transactionId 不能为空");
        }
        long activityId = LuckyBetSuffixService.DEFAULT_ACTIVITY_ID;

        JSONObject eligibility = luckyBetSuffixService.fetchEligibility(userId, activityId, txn);
        Integer errCode = eligibility.getInteger("code");
        if (errCode != null && errCode != 0) {
            String msg = eligibility.getString("msg");
            throw new GlobalException(40903, msg == null ? "暂无领取资格" : msg);
        }

        JSONObject match = LuckyBetSuffixServiceImpl.pickBestApplicable(eligibility);
        if (match == null || !Integer.valueOf(1).equals(match.getInteger("applicable"))) {
            throw new GlobalException(40903, "该注单暂不符合加码领取条件");
        }
        String matchTxn = match.getString("transaction_id");
        if (matchTxn != null && !matchTxn.isEmpty() && !txn.equals(matchTxn)) {
            throw new GlobalException(40903, "注单信息不一致，请重新查询");
        }

        BigDecimal bonus = match.getBigDecimal("bonus");
        if (bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(40903, "加码金额无效");
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        String externalId = "lucky-" + activityId + "-" + txn + "-" + userId;
        ensureNotApplied(externalId);

        JSONObject extra = new JSONObject();
        extra.put("scope", "private");
        extra.put("targetUserId", userId);
        extra.put("activityId", activityId);
        extra.put("transactionId", txn);

        Redpacket rp = buildRedpacket(externalId, botUserId, userId, bonus,
            "电子活动加码金", "lucky_bet_suffix", extra);
        finishApply(botUserId, userId, LUCKY_SUCCESS_MSG, rp, "lucky-bonus", txn, bonus);
    }

    private void applyDailyGoodLuckGold(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        String statDate = dto.getStatDate() == null ? "" : dto.getStatDate().trim();
        Integer level = dto.getLevel();
        // 兼容旧端：如果未传 statDate/level，但传了 transactionId=claim_trade_no（daily-gold-YYYY-MM-DD-Lx），则从中解析
        if ((statDate.isEmpty() || level == null || level <= 0) && dto.getTransactionId() != null) {
            String txn = dto.getTransactionId().trim();
            // daily-gold-2026-06-01-L2
            if (txn.startsWith("daily-gold-")) {
                String[] parts = txn.split("-");
                if (parts.length >= 6) {
                    String yyyy = parts[2], mm = parts[3], dd = parts[4];
                    String lvlPart = parts[5]; // L2
                    if (statDate.isEmpty()) {
                        statDate = yyyy + "-" + mm + "-" + dd;
                    }
                    if ((level == null || level <= 0) && lvlPart != null && lvlPart.startsWith("L")) {
                        try {
                            level = Integer.parseInt(lvlPart.substring(1));
                            dto.setLevel(level);
                        } catch (Exception ignore) {
                            // keep invalid
                        }
                    }
                    dto.setStatDate(statDate);
                }
            }
        }

        if (statDate.isEmpty() || level == null || level <= 0) {
            throw new GlobalException(400, "统计日或关卡无效");
        }

        long activityId = resolveDailyActivityId(dto);
        if (activityId <= 0) {
            throw new GlobalException(500, "天天好运金活动未配置，请联系客服");
        }

        JSONObject status = dailyGoodLuckGoldService.fetchStatus(userId, statDate);
        Integer errCode = status.getInteger("code");
        if (errCode != null && errCode != 0) {
            String msg = status.getString("msg");
            throw new GlobalException(40903, msg == null ? "暂无领取资格" : msg);
        }

        if (!statDate.equals(status.getString("stat_date"))) {
            throw new GlobalException(40903, "统计日不一致，请重新查询");
        }
        if (!level.equals(status.getInteger("current_level"))) {
            throw new GlobalException(40903, "关卡已变化，请重新查询");
        }
        if (!Boolean.TRUE.equals(status.getBoolean("current_claimable"))) {
            throw new GlobalException(40903, "当前暂不符合领取条件或已领取");
        }

        BigDecimal bonus = status.getBigDecimal("current_bonus");
        if (bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(40903, "彩金金额无效");
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        String tradeNo = status.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            tradeNo = "daily-gold-" + statDate + "-L" + level;
        }

        String externalId = "daily-gold-" + activityId + "-" + statDate + "-lvl" + level + "-" + userId;
        ensureNotApplied(externalId);

        JSONObject extra = new JSONObject();
        extra.put("scope", "private");
        extra.put("targetUserId", userId);
        extra.put("activityId", activityId);
        extra.put("transactionId", tradeNo);
        extra.put("statDate", statDate);
        extra.put("level", level);

        Redpacket rp = buildRedpacket(externalId, botUserId, userId, bonus,
            "天天好运金", "daily_good_luck_gold", extra);
        finishApply(botUserId, userId, DAILY_GOLD_SUCCESS_MSG, rp, "daily-good-luck-gold", tradeNo, bonus);
    }

    private void applyCommissionBonus999(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        Integer level = dto.getLevel();
        if (level == null || level <= 0) {
            String txn = dto.getTransactionId() == null ? "" : dto.getTransactionId().trim();
            if (txn.startsWith("commission-bonus-") && txn.contains("-L")) {
                int idx = txn.lastIndexOf("-L");
                try {
                    level = Integer.parseInt(txn.substring(idx + 2));
                } catch (Exception ignore) {
                    level = null;
                }
            }
        }
        if (level == null || level <= 0) {
            throw new GlobalException(400, "领取档位无效");
        }

        long activityId = resolveCommissionActivityId(dto);
        if (activityId <= 0) {
            throw new GlobalException(500, "佣金加码活动未配置，请联系客服");
        }

        JSONObject status = commissionBonus999Service.fetchStatus(userId);
        if (status == null || !Boolean.TRUE.equals(status.getBoolean("can_claim"))) {
            throw new GlobalException(40903, "当前暂无可领取的佣金加码");
        }

        JSONArray claimable = status.getJSONArray("claimable_tiers");
        JSONObject matched = null;
        if (claimable != null) {
            for (int i = 0; i < claimable.size(); i++) {
                JSONObject t = claimable.getJSONObject(i);
                if (t != null && level.equals(t.getInteger("level"))) {
                    matched = t;
                    break;
                }
            }
        }
        if (matched == null) {
            throw new GlobalException(40903, "该档位暂不可领取");
        }

        BigDecimal bonus = matched.getBigDecimal("bonus");
        if (bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(40903, "加码金额无效");
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        String tradeNo = matched.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            tradeNo = "commission-bonus-" + activityId + "-L" + level;
        }

        String externalId = "commission-" + activityId + "-L" + level + "-" + userId;
        ensureNotApplied(externalId);

        JSONObject extra = new JSONObject();
        extra.put("scope", "private");
        extra.put("targetUserId", userId);
        extra.put("activityId", activityId);
        extra.put("transactionId", tradeNo);
        extra.put("level", level);
        extra.put("flowMultiplier", status.getInteger("flow_multiplier"));

        Redpacket rp = buildRedpacket(externalId, botUserId, userId, bonus,
            "佣金加码999奖上奖", "commission_bonus_999", extra);
        finishApply(botUserId, userId, COMMISSION_BONUS_SUCCESS_MSG, rp, "commission-bonus-999", tradeNo, bonus);
    }

    private void applyFriendDepositBonus4215(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        Integer level = dto.getLevel();
        String weekStart = "";
        String txn = dto.getTransactionId() == null ? "" : dto.getTransactionId().trim();
        if (txn.startsWith("friend-deposit-bonus-")) {
            // friend-deposit-bonus-{activityId}-{weekStart}-L{level}
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^friend-deposit-bonus-\\d+-(\\d{4}-\\d{2}-\\d{2})-L(\\d+)$")
                .matcher(txn);
            if (m.find()) {
                weekStart = m.group(1);
                if (level == null || level <= 0) {
                    try {
                        level = Integer.parseInt(m.group(2));
                    } catch (Exception ignore) {
                        level = null;
                    }
                }
            } else if (txn.contains("-L")) {
                int idx = txn.lastIndexOf("-L");
                try {
                    level = Integer.parseInt(txn.substring(idx + 2));
                } catch (Exception ignore) {
                    level = null;
                }
            }
        }
        if (level == null || level <= 0) {
            throw new GlobalException(400, "领取档位无效");
        }

        long activityId = resolveFriendDepositActivityId(dto);
        if (activityId <= 0) {
            throw new GlobalException(500, "好友存款赏金活动未配置，请联系客服");
        }

        JSONObject status = friendDepositBonus4215Service.fetchStatus(userId);
        if (status == null || !Boolean.TRUE.equals(status.getBoolean("can_claim"))) {
            throw new GlobalException(40903, "当前暂无可领取的好友存款赏金");
        }
        if (weekStart.isEmpty()) {
            String ws = status.getString("week_start");
            if (ws != null) {
                weekStart = ws;
            }
        }

        JSONArray claimable = status.getJSONArray("claimable_tiers");
        JSONObject matched = null;
        if (claimable != null) {
            for (int i = 0; i < claimable.size(); i++) {
                JSONObject t = claimable.getJSONObject(i);
                if (t != null && level.equals(t.getInteger("level"))) {
                    matched = t;
                    break;
                }
            }
        }
        if (matched == null) {
            throw new GlobalException(40903, "该档位暂不可领取");
        }

        BigDecimal bonus = matched.getBigDecimal("bonus");
        if (bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(40903, "赏金金额无效");
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        String tradeNo = matched.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            if (weekStart.isEmpty()) {
                weekStart = matched.getString("week_start");
            }
            tradeNo = "friend-deposit-bonus-" + activityId + "-" + weekStart + "-L" + level;
        }
        if (weekStart.isEmpty()) {
            weekStart = matched.getString("week_start");
        }

        String externalId = "friend-deposit-" + activityId + "-" + weekStart + "-L" + level + "-" + userId;
        ensureNotApplied(externalId);

        JSONObject extra = new JSONObject();
        extra.put("scope", "private");
        extra.put("targetUserId", userId);
        extra.put("activityId", activityId);
        extra.put("transactionId", tradeNo);
        extra.put("level", level);
        extra.put("weekStart", weekStart);
        extra.put("flowMultiplier", status.getInteger("flow_multiplier"));

        Redpacket rp = buildRedpacket(externalId, botUserId, userId, bonus,
            "好友存款赏金4215", "friend_deposit_bonus_4215", extra);
        finishApply(botUserId, userId, FRIEND_DEPOSIT_BONUS_SUCCESS_MSG, rp, "friend-deposit-bonus-4215", tradeNo, bonus);
    }

    private void applyWeeklySportsBonus(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        String weekStart = "";
        String txn = dto.getTransactionId() == null ? "" : dto.getTransactionId().trim();
        if (txn.startsWith("weekly-sports-")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^weekly-sports-\\d+-(\\d{4}-\\d{2}-\\d{2})$")
                .matcher(txn);
            if (m.find()) {
                weekStart = m.group(1);
            }
        }

        long activityId = resolveWeeklySportsActivityId(dto);
        if (activityId <= 0) {
            throw new GlobalException(500, "体育周周领活动未配置，请联系客服");
        }

        JSONObject status = weekStart.isEmpty()
            ? sportWeeklyBonusService.fetchStatus(userId)
            : sportWeeklyBonusService.fetchStatus(userId, weekStart);
        JSONObject claim = status == null ? null : status.getJSONObject("claim");
        if (claim == null || !Boolean.TRUE.equals(claim.getBoolean("can_claim"))) {
            throw new GlobalException(40903, "当前暂无可领取的体育周周领彩金");
        }

        int level = claim.getIntValue("level");
        BigDecimal bonus = claim.getBigDecimal("bonus");
        if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(40903, "彩金金额无效");
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        if (weekStart.isEmpty()) {
            weekStart = claim.getString("week_start");
        }
        String tradeNo = claim.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            tradeNo = "weekly-sports-" + activityId + "-" + weekStart;
        }

        String externalId = "weekly-sports-" + activityId + "-" + weekStart + "-" + userId;
        ensureNotApplied(externalId);

        JSONObject extra = new JSONObject();
        extra.put("scope", "private");
        extra.put("targetUserId", userId);
        extra.put("activityId", activityId);
        extra.put("transactionId", tradeNo);
        extra.put("level", level);
        extra.put("weekStart", weekStart);
        extra.put("flowMultiplier", status.getInteger("flow_multiplier"));

        Redpacket rp = buildRedpacket(externalId, botUserId, userId, bonus,
            "体育周周领", "weekly_sports_bonus", extra);
        finishApply(botUserId, userId, WEEKLY_SPORTS_BONUS_SUCCESS_MSG, rp, "weekly-sports-bonus", tradeNo, bonus);
    }

    private void applyDailySportsBonus(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        String statDate = "";
        String txn = dto.getTransactionId() == null ? "" : dto.getTransactionId().trim();
        if (txn.startsWith("daily-sports-")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^daily-sports-\\d+-(\\d{4}-\\d{2}-\\d{2})$")
                .matcher(txn);
            if (m.find()) {
                statDate = m.group(1);
            }
        }

        long activityId = resolveDailySportsActivityId(dto);
        if (activityId <= 0) {
            throw new GlobalException(500, "日投注闯关活动未配置，请联系客服");
        }

        JSONObject status = statDate.isEmpty()
            ? sportDailyBonusService.fetchStatus(userId)
            : sportDailyBonusService.fetchStatus(userId, statDate);
        JSONObject claim = status == null ? null : status.getJSONObject("claim");
        if (claim == null || !Boolean.TRUE.equals(claim.getBoolean("can_claim"))) {
            throw new GlobalException(40903, "当前暂无可领取的日投注闯关彩金");
        }

        int level = claim.getIntValue("level");
        BigDecimal bonus = claim.getBigDecimal("bonus");
        if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(40903, "彩金金额无效");
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        if (statDate.isEmpty()) {
            statDate = claim.getString("stat_date");
        }
        String tradeNo = claim.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            tradeNo = "daily-sports-" + activityId + "-" + statDate;
        }

        String externalId = "daily-sports-" + activityId + "-" + statDate + "-" + userId;
        ensureNotApplied(externalId);

        JSONObject extra = new JSONObject();
        extra.put("scope", "private");
        extra.put("targetUserId", userId);
        extra.put("activityId", activityId);
        extra.put("transactionId", tradeNo);
        extra.put("level", level);
        extra.put("statDate", statDate);
        extra.put("flowMultiplier", status.getInteger("flow_multiplier"));

        Redpacket rp = buildRedpacket(externalId, botUserId, userId, bonus,
            "日投注闯关", "daily_sports_bonus", extra);
        finishApply(botUserId, userId, DAILY_SPORTS_BONUS_SUCCESS_MSG, rp, "daily-sports-bonus", tradeNo, bonus);
    }

    private LuckyBonusApplyResultVO applySportWinStreak(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        String phase = dto.getPhase() == null ? "" : dto.getPhase().trim().toLowerCase();
        if ("claim".equals(phase)) {
            claimSportWinStreakRedpacket(userId, dto, botUserId);
            return successApplyResult();
        }
        return reviewSportWinStreak(userId, dto, botUserId);
    }

    private LuckyBonusApplyResultVO reviewSportWinStreak(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        long activityId = resolveSportWinStreakActivityId(dto);
        if (activityId <= 0) {
            throw new GlobalException(500, "体育连红挑战活动未配置，请联系客服");
        }

        JSONObject review = sportWinStreakService.applyReview(userId);
        if (review == null || !Boolean.TRUE.equals(review.getBoolean("review_passed"))) {
            String msg = review == null ? null : review.getString("message");
            throw new GlobalException(40903, msg == null || msg.isBlank() ? "当前暂不符合体育连红领取条件" : msg);
        }

        BigDecimal bonus = review.getBigDecimal("bonus");
        if (bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(40903, "彩金金额无效");
        }

        botPrivateMessageService.sendText(botUserId, userId, SPORT_WIN_STREAK_REVIEW_MSG);

        LuckyBonusApplyResultVO result = new LuckyBonusApplyResultVO();
        result.setKind("sport_win_streak");
        result.setApplied(false);
        result.setReviewPassed(true);
        return result;
    }

    private void claimSportWinStreakRedpacket(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        long activityId = resolveSportWinStreakActivityId(dto);
        if (activityId <= 0) {
            throw new GlobalException(500, "体育连红挑战活动未配置，请联系客服");
        }

        JSONObject status = sportWinStreakService.fetchStatus(userId);
        JSONObject claim = status == null ? null : status.getJSONObject("claim");
        boolean canClaimRedpacket = claim != null && Boolean.TRUE.equals(claim.getBoolean("can_claim_redpacket"));
        if (!canClaimRedpacket && claim != null && Boolean.TRUE.equals(claim.getBoolean("can_claim"))) {
            canClaimRedpacket = true;
        }
        if (!canClaimRedpacket) {
            throw new GlobalException(40903, "请先提交申请并通过资格审核，再领取红包");
        }

        int level = claim.getIntValue("level");
        BigDecimal bonus = claim.getBigDecimal("bonus");
        if (level <= 0 || bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(40903, "彩金金额无效");
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        String weekStart = claim.getString("week_start");
        String tradeNo = claim.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            tradeNo = "sport-streak-" + activityId + "-" + weekStart;
        }

        String externalId = "sport-streak-" + activityId + "-" + weekStart + "-" + userId;
        ensureNotApplied(externalId);

        JSONObject extra = new JSONObject();
        extra.put("scope", "private");
        extra.put("targetUserId", userId);
        extra.put("activityId", activityId);
        extra.put("transactionId", tradeNo);
        extra.put("level", level);
        extra.put("weekStart", weekStart);
        extra.put("flowMultiplier", status.getInteger("flow_multiplier"));

        Redpacket rp = buildRedpacket(externalId, botUserId, userId, bonus,
            "体育连红挑战", "sport_win_streak", extra);
        finishApply(botUserId, userId, SPORT_WIN_STREAK_SUCCESS_MSG, rp, "sport-win-streak", tradeNo, bonus);
    }

    private void applyInviteFriendsBonus(Long userId, LuckyBonusApplyDTO dto, Long botUserId) {
        Integer level = dto.getLevel();
        if (level == null || level <= 0) {
            String txn = dto.getTransactionId() == null ? "" : dto.getTransactionId().trim();
            if (txn.startsWith("invite-bonus-") && txn.contains("-L")) {
                int idx = txn.lastIndexOf("-L");
                try {
                    level = Integer.parseInt(txn.substring(idx + 2));
                } catch (Exception ignore) {
                    level = null;
                }
            }
        }
        if (level == null || level <= 0) {
            throw new GlobalException(400, "领取档位无效");
        }

        long activityId = resolveInviteActivityId(dto);
        if (activityId <= 0) {
            throw new GlobalException(500, "邀请好友活动未配置，请联系客服");
        }

        JSONObject quota = inviteFriendsBonusService.fetchQuota(userId);
        if (quota == null || !Boolean.TRUE.equals(quota.getBoolean("canClaim"))) {
            throw new GlobalException(40903, "当前暂无可领取的邀请彩礼");
        }

        JSONObject invite = quota.getJSONObject("invite_friends");
        if (invite == null) {
            throw new GlobalException(40903, "邀请活动数据无效");
        }

        JSONArray claimable = invite.getJSONArray("claimable_tiers");
        JSONObject matched = null;
        if (claimable != null) {
            for (int i = 0; i < claimable.size(); i++) {
                JSONObject t = claimable.getJSONObject(i);
                if (t != null && level.equals(t.getInteger("level"))) {
                    matched = t;
                    break;
                }
            }
        }
        if (matched == null) {
            throw new GlobalException(40903, "该档位暂不可领取");
        }

        BigDecimal bonus = matched.getBigDecimal("bonus");
        if (bonus == null || bonus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(40903, "彩礼金额无效");
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        String tradeNo = matched.getString("claim_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            tradeNo = "invite-bonus-" + activityId + "-L" + level;
        }

        String externalId = "invite-" + activityId + "-L" + level + "-" + userId;
        ensureNotApplied(externalId);

        JSONObject extra = new JSONObject();
        extra.put("scope", "private");
        extra.put("targetUserId", userId);
        extra.put("activityId", activityId);
        extra.put("transactionId", tradeNo);
        extra.put("level", level);

        Redpacket rp = buildRedpacket(externalId, botUserId, userId, bonus,
            "邀请好友送彩礼", "invite_friends_bonus", extra);
        finishApply(botUserId, userId, INVITE_SUCCESS_MSG, rp, "invite-friends-bonus", tradeNo, bonus);
    }

    private long resolveDailyActivityId(LuckyBonusApplyDTO dto) {
        if (dto.getActivityId() != null && dto.getActivityId() > 0) {
            return dto.getActivityId();
        }
        return boximProps.getActivities().getDailyGoodLuckGoldId();
    }

    private long resolveInviteActivityId(LuckyBonusApplyDTO dto) {
        if (dto.getActivityId() != null && dto.getActivityId() > 0) {
            return dto.getActivityId();
        }
        return boximProps.getActivities().getInviteFriendsBonusId();
    }

    private long resolveCommissionActivityId(LuckyBonusApplyDTO dto) {
        if (dto.getActivityId() != null && dto.getActivityId() > 0) {
            return dto.getActivityId();
        }
        return boximProps.getActivities().getCommissionBonus999Id();
    }

    private long resolveFriendDepositActivityId(LuckyBonusApplyDTO dto) {
        if (dto.getActivityId() != null && dto.getActivityId() > 0) {
            return dto.getActivityId();
        }
        return boximProps.getActivities().getFriendDepositBonus4215Id();
    }

    private long resolveWeeklySportsActivityId(LuckyBonusApplyDTO dto) {
        if (dto.getActivityId() != null && dto.getActivityId() > 0) {
            return dto.getActivityId();
        }
        return boximProps.getActivities().getWeeklySportsBonusId();
    }

    private long resolveDailySportsActivityId(LuckyBonusApplyDTO dto) {
        if (dto.getActivityId() != null && dto.getActivityId() > 0) {
            return dto.getActivityId();
        }
        return boximProps.getActivities().getDailySportsBonusId();
    }

    private long resolveSportWinStreakActivityId(LuckyBonusApplyDTO dto) {
        if (dto.getActivityId() != null && dto.getActivityId() > 0) {
            return dto.getActivityId();
        }
        return boximProps.getActivities().getSportWinStreakId();
    }

    private void ensureNotApplied(String externalId) {
        Redpacket existing = redpacketMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Redpacket>()
                .eq(Redpacket::getExternalId, externalId));
        if (existing != null) {
            throw new GlobalException(40902, "您已申请过该活动彩金");
        }
    }

    private Redpacket buildRedpacket(String externalId, Long botUserId, Long userId, BigDecimal bonus,
                                     String title, String eligibilityRule, JSONObject extra) {
        User bot = userService.getById(botUserId);
        Long botId = bot != null ? bot.getId() : botUserId;

        Redpacket rp = new Redpacket();
        rp.setExternalId(externalId);
        rp.setGroupId(0L);
        rp.setBotUserId(botId);
        rp.setAmount(bonus);
        rp.setTotalCount(1);
        rp.setClaimedCount(0);
        rp.setTitle(title);
        rp.setEligibilityRule(eligibilityRule);
        rp.setExtra(extra.toJSONString());
        rp.setStatus(0);
        long now = System.currentTimeMillis();
        rp.setExpireAt(new java.util.Date(now + 24L * 60 * 60 * 1000));
        rp.setCreatedAt(new java.util.Date(now));
        rp.setUpdatedAt(new java.util.Date(now));
        redpacketMapper.insert(rp);
        return rp;
    }

    private void finishApply(Long botUserId, Long userId, String successMsg, Redpacket rp,
                             String logTag, String ref, BigDecimal bonus) {
        reserveActivityBonusOnAagames(userId, rp);
        botPrivateMessageService.sendText(botUserId, userId, successMsg);
        botPrivateMessageService.sendPrivateRedpacket(botUserId, userId, rp);
        redpacketMapper.updateById(rp);
        log.info("[{}] applied userId={} ref={} bonus={} redpacketId={}", logTag, userId, ref, bonus, rp.getId());
    }

    private void reserveActivityBonusOnAagames(Long userId, Redpacket rp) {
        if (userId == null || rp == null || rp.getExtra() == null || rp.getExtra().isBlank()) {
            return;
        }
        try {
            JSONObject extra = JSON.parseObject(rp.getExtra());
            if (extra == null) {
                return;
            }
            Long activityId = extra.getLong("activityId");
            String tradeNo = extra.getString("transactionId");
            if (activityId == null || activityId <= 0 || tradeNo == null || tradeNo.isBlank()) {
                return;
            }
            aagamesBusinessClient.reserveActivityBonus(userId, activityId, tradeNo.trim(), rp.getAmount());
        } catch (Exception e) {
            log.warn("[lucky-bonus] reserve activity bonus failed userId={} redpacketId={} err={}",
                userId, rp.getId(), e.getMessage());
        }
    }

    /** 送彩票额度 = 张数 × 2 元，保证为 2 的整数倍。 */
    private static BigDecimal lotteryGiftAmountFromTickets(int ticketCount, BigDecimal apiAmount) {
        if (ticketCount > 0) {
            return BigDecimal.valueOf(ticketCount * 2L).setScale(2, RoundingMode.UNNECESSARY);
        }
        if (apiAmount == null || apiAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
        int derived = apiAmount.divide(BigDecimal.valueOf(2), 0, RoundingMode.FLOOR).intValue();
        if (derived <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(derived * 2L).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static boolean isLotteryGiftAlreadyClaimed(JSONObject data) {
        if (data == null) {
            return false;
        }
        if (Boolean.TRUE.equals(data.getBoolean("already_claimed"))) {
            return true;
        }
        Object raw = data.get("already_claimed");
        if (raw instanceof Number) {
            return ((Number) raw).intValue() == 1;
        }
        return raw != null && "true".equalsIgnoreCase(String.valueOf(raw));
    }
}
