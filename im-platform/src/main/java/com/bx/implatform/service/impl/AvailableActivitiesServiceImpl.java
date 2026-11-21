package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.config.props.BoximIntegrationProperties;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.service.AvailableActivitiesService;
import com.bx.implatform.service.CommissionBonus999Service;
import com.bx.implatform.service.DailyGoodLuckGoldService;
import com.bx.implatform.service.FriendDepositBonus4215Service;
import com.bx.implatform.service.InviteFriendsBonusService;
import com.bx.implatform.service.LuckyBetSuffixService;
import com.bx.implatform.service.SportWeeklyBonusService;
import com.bx.implatform.service.SportDailyBonusService;
import com.bx.implatform.service.SportWinStreakService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AvailableActivitiesServiceImpl implements AvailableActivitiesService {

    private static final Pattern LIST_KEYWORDS = Pattern.compile(
        "可以参加|能参加|有哪些活动|什么活动|有什么活动|哪些活动|活动有哪些|能参加什么|可以参与|有哪些优惠|"
            + "能领什么活动|参加的活动|可参加.*活动|活动可以|可以领什么活动|"
            + "优惠活动|活动优惠|活动列表|有没有活动|有活动吗|有优惠吗|有什么优惠|问活动|咨询活动|看看活动");

    /** 「活动」「问活动」「现在有活动吗」这类整句，不拿去套某一档 7PG 彩金。 */
    private static final Pattern SHORT_ACTIVITY_ASK = Pattern.compile(
        "^[\\s,，。！？?!、]*"
            + "(?:请问|想问下|想问|问一下|问下|问|咨询一下|咨询|你好|在吗|帮我看看|帮我|给我)?"
            + "[\\s,，。！？?!、]*"
            + "(?:看下|看看|查一下|查下|查询|介绍一下|介绍|说说)?"
            + "[\\s,，。！？?!、]*"
            + "(?:现在|最近|目前|今天)?"
            + "[\\s,，。！？?!、]*"
            + "(?:有没有|有什么|有|什么|哪些)?"
            + "[\\s,，。！？?!、]*"
            + "(?:优惠)?活动"
            + "(?:信息|名单|列表|介绍)?"
            + "(?:吗|呢|啊|呀|么|吧)?"
            + "[\\s。！？?!]*$");

    private static final Pattern SHORT_CLAIM_ASK = Pattern.compile(
        "^[\\s,，。！？?!、]*"
            + "(?:请问|想问|问一下|现在|我)?"
            + "[\\s,，。！？?!、]*"
            + "(?:能领什么|可以领什么)"
            + "(?:活动|优惠)?"
            + "(?:吗|呢|啊)?"
            + "[\\s。！？?!]*$");

    private static final List<ActivityCatalog> CATALOG = List.of(
        new ActivityCatalog("friend_deposit_bonus_4215", "好友存款领赏金积少成多现拿4215",
            "get_friend_deposit_bonus_status", BoximIntegrationProperties.Activities::getFriendDepositBonus4215Id),
        new ActivityCatalog("weekly_sports_bonus", "体育周周领 最高领588元",
            "get_weekly_sports_bonus_status", BoximIntegrationProperties.Activities::getWeeklySportsBonusId),
        new ActivityCatalog("daily_sports_bonus", "日投注闯关 每日最高领58元",
            "get_daily_sports_bonus_status", BoximIntegrationProperties.Activities::getDailySportsBonusId),
        new ActivityCatalog("sport_win_streak", "体育连红挑战 最高领88元",
            "get_sport_win_streak_status", BoximIntegrationProperties.Activities::getSportWinStreakId),
        new ActivityCatalog("commission_bonus_999", "佣金加码999奖上奖",
            "get_commission_bonus_status", BoximIntegrationProperties.Activities::getCommissionBonus999Id),
        new ActivityCatalog("invite_friends_bonus", "邀请好友送彩礼",
            "get_invite_friends_quota", BoximIntegrationProperties.Activities::getInviteFriendsBonusId),
        new ActivityCatalog("daily_good_luck_gold", "天天好运金",
            "get_daily_good_luck_gold_status", BoximIntegrationProperties.Activities::getDailyGoodLuckGoldId),
        new ActivityCatalog("lucky_bet_suffix", "幸运电子尾号加码",
            "get_lucky_bet_eligibility", activities -> LuckyBetSuffixService.DEFAULT_ACTIVITY_ID)
    );

    private final FriendDepositBonus4215Service friendDepositBonus4215Service;
    private final SportWeeklyBonusService sportWeeklyBonusService;
    private final SportDailyBonusService sportDailyBonusService;
    private final SportWinStreakService sportWinStreakService;
    private final CommissionBonus999Service commissionBonus999Service;
    private final InviteFriendsBonusService inviteFriendsBonusService;
    private final DailyGoodLuckGoldService dailyGoodLuckGoldService;
    private final LuckyBetSuffixService luckyBetSuffixService;
    private final BoximIntegrationProperties boximProps;
    private final AagamesBusinessClient aagamesBusinessClient;

    @Override
    public boolean matchesListKeywords(String userMessage) {
        return matchesActivityListQuestion(userMessage);
    }

    public static boolean matchesActivityListQuestion(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String text = userMessage.trim();
        return LIST_KEYWORDS.matcher(text).find()
            || SHORT_ACTIVITY_ASK.matcher(text).matches()
            || SHORT_CLAIM_ASK.matcher(text).matches();
    }

    @Override
    public JSONObject fetchOverview(Long userId) {
        try {
            JSONObject data = aagamesBusinessClient.availableActivitiesOverview(userId);
            int count = data == null ? 0 : data.getIntValue("activity_count");
            if (data == null || data.isEmpty() || count <= 0) {
                log.info("[ai-cs] available-activities overview empty userId={} data={}",
                    userId, data);
            } else {
                log.info("[ai-cs] available-activities overview userId={} count={} errors={}",
                    userId, count, data.get("fetch_errors"));
            }
            if (data != null && !data.isEmpty()) {
                data.remove("userId");
                return data;
            }
        } catch (Exception e) {
            if (loginExpired(e)) {
                try {
                    JSONObject listed = aagamesBusinessClient.availableActivitiesListed(userId);
                    if (listed != null && !listed.isEmpty()) {
                        listed.remove("userId");
                        log.info("[ai-cs] available-activities listed without user token userId={} count={}",
                            userId, listed.getIntValue("activity_count"));
                        return listed;
                    }
                } catch (Exception fallback) {
                    log.warn("[ai-cs] available-activities listed fallback failed userId={} err={}",
                        userId, fallback.getMessage());
                }
            }
            log.warn("[ai-cs] aagames available-activities overview failed userId={} err={}",
                userId, e.getMessage());
            return unavailableOverview();
        }
        return emptyOverview();
    }

    private static boolean loginExpired(Exception e) {
        if (!(e instanceof GlobalException ge)) {
            return false;
        }
        if (Integer.valueOf(410).equals(ge.getCode())) {
            return true;
        }
        String message = ge.getMessage();
        return message != null && (message.contains("externalLoginExpired") || message.contains("登录已失效"));
    }

    /** 接口没有返回该商户的活动时，不拿另一套写死名单顶上。 */
    private static JSONObject emptyOverview() {
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("activity_count", 0);
        out.put("activity_summaries", new JSONArray());
        out.put("hint", "当前商户没有进行中的活动。不要补充其他商户或记忆中的活动名称。");
        return out;
    }

    private static JSONObject unavailableOverview() {
        JSONObject out = emptyOverview();
        out.put("unavailable", true);
        out.put("hint", "活动列表暂时无法查询。不要编造活动名称。");
        return out;
    }

    /** aagames 总览不可用时的本地降级（不含预占状态，仅作兜底）。 */
    private JSONObject fetchOverviewLocal(Long userId) {
        JSONObject statuses = new JSONObject(new LinkedHashMap<>());
        JSONArray summaries = new JSONArray();
        JSONArray fetchErrors = new JSONArray();

        for (ActivityCatalog catalog : CATALOG) {
            long activityId = resolveActivityId(catalog);
            if ("lucky_bet_suffix".equals(catalog.key())) {
                activityId = LuckyBetSuffixService.DEFAULT_ACTIVITY_ID;
            }

            FetchResult fetched = fetchStatus(catalog.key(), userId, activityId);
            if (fetched.error() != null) {
                JSONObject err = new JSONObject();
                err.put("key", catalog.key());
                err.put("message", fetched.error());
                fetchErrors.add(err);
            }

            JSONObject summary = buildSummary(catalog, activityId, fetched.status(), fetched.error());
            if (fetched.status() != null) {
                statuses.put(catalog.key(), fetched.status());
            }
            summaries.add(summary);
        }

        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("activity_count", summaries.size());
        out.put("activity_summaries", summaries);
        out.put("statuses", statuses);
        if (!fetchErrors.isEmpty()) {
            out.put("fetch_errors", fetchErrors);
        }
        out.put("hint", "回答用户时必须逐条列出 activity_summaries 中的全部活动（含 fetch_ok=false 的条目也要列出名称与说明），"
            + "不可只说「无法查询」；可领金额以 can_claim_now / remain_bonus_total 为准；"
            + "部分活动查询失败时说明「实时进度暂不可用」，但仍要列出该活动。");
        return out;
    }

    @Override
    public String formatOverviewForPrompt(JSONObject overview) {
        if (overview == null) {
            return "";
        }
        JSONArray summaries = overview.getJSONArray("activity_summaries");
        if (summaries == null || summaries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【活动总览数据 — 已预取，直接用于回答，无需再调工具】\n");
        for (int i = 0; i < summaries.size(); i++) {
            JSONObject s = summaries.getJSONObject(i);
            if (s == null) {
                continue;
            }
            sb.append(i + 1).append(". ").append(s.getString("name"));
            appendClaimBadge(sb, s);
            sb.append("：").append(s.getString("summary")).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String formatOverviewForUserReply(JSONObject overview) {
        if (overview == null) {
            return "";
        }
        if (Boolean.TRUE.equals(overview.getBoolean("unavailable"))) {
            return "活动名单这次没有取到。请再发一次「有哪些活动」，我按当前商户的活动页回复。";
        }
        JSONArray summaries = overview.getJSONArray("activity_summaries");
        if (summaries == null || summaries.isEmpty()) {
            return "当前暂无进行中的活动。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前可参与的活动如下：\n");
        int claimable = 0;
        for (int i = 0; i < summaries.size(); i++) {
            JSONObject s = summaries.getJSONObject(i);
            if (s == null) {
                continue;
            }
            sb.append(i + 1).append(". ").append(s.getString("name"));
            if (Boolean.TRUE.equals(s.getBoolean("can_claim_now"))) {
                sb.append("【可领取");
                if (s.get("remain_bonus_total") != null) {
                    sb.append(" ¥").append(s.get("remain_bonus_total"));
                }
                sb.append("】");
                claimable++;
            } else if (Boolean.TRUE.equals(s.getBoolean("applied"))) {
                sb.append("【红包待领】");
            } else if (Boolean.TRUE.equals(s.getBoolean("already_claimed"))) {
                sb.append("【已领取】");
            }
            String summary = s.getString("summary");
            if (summary != null && !summary.isBlank()) {
                sb.append("\n   ").append(summary.trim());
            }
            sb.append("\n");
        }
        if (claimable > 0) {
            sb.append("\n标有【可领取】的活动，下方会显示领取按钮；向 IM 客服发送对应活动关键词也可办理。");
        } else {
            sb.append("\n您当前暂无可立即领取的彩金；向 IM 客服发送活动名称可查询进度或了解参与方式。");
        }
        return sb.toString().trim();
    }

    private FetchResult fetchStatus(String key, Long userId, long activityId) {
        if (activityId <= 0 && !"lucky_bet_suffix".equals(key)) {
            return new FetchResult(null, "活动 ID 未配置（boxim.activities）");
        }
        try {
            return switch (key) {
                case "friend_deposit_bonus_4215" ->
                    new FetchResult(friendDepositBonus4215Service.fetchStatus(userId), null);
                case "weekly_sports_bonus" ->
                    new FetchResult(sportWeeklyBonusService.fetchStatus(userId), null);
                case "daily_sports_bonus" ->
                    new FetchResult(sportDailyBonusService.fetchStatus(userId), null);
                case "sport_win_streak" ->
                    new FetchResult(sportWinStreakService.fetchStatus(userId), null);
                case "commission_bonus_999" ->
                    new FetchResult(commissionBonus999Service.fetchStatus(userId), null);
                case "invite_friends_bonus" ->
                    new FetchResult(inviteFriendsBonusService.fetchQuota(userId), null);
                case "daily_good_luck_gold" ->
                    new FetchResult(dailyGoodLuckGoldService.fetchStatus(userId), null);
                case "lucky_bet_suffix" ->
                    new FetchResult(luckyBetSuffixService.fetchEligibility(userId,
                        LuckyBetSuffixService.DEFAULT_ACTIVITY_ID), null);
                default -> new FetchResult(null, "未知活动类型");
            };
        } catch (Exception e) {
            log.warn("[ai-cs] available-activities {} fetch failed userId={} err={}", key, userId, e.getMessage());
            return new FetchResult(null, e.getMessage() == null ? "查询失败" : e.getMessage());
        }
    }

    private JSONObject buildSummary(ActivityCatalog catalog, long activityId,
                                    JSONObject status, String fetchError) {
        JSONObject s = baseSummary(catalog.key(), activityId, catalog.defaultName(), catalog.detailTool(), false);
        if (status == null) {
            s.put("fetch_ok", false);
            s.put("summary", catalog.defaultName() + "：活动进行中，实时进度查询暂不可用"
                + (fetchError != null && !fetchError.isBlank() ? "（" + fetchError + "）" : ""));
            return s;
        }
        s.put("fetch_ok", true);
        return switch (catalog.key()) {
            case "friend_deposit_bonus_4215" -> enrichFriendDeposit(s, status);
            case "weekly_sports_bonus" -> enrichWeeklySports(s, status);
            case "daily_sports_bonus" -> enrichDailySports(s, status);
            case "sport_win_streak" -> enrichSportWinStreak(s, status);
            case "commission_bonus_999" -> enrichCommission(s, status);
            case "invite_friends_bonus" -> enrichInvite(s, status);
            case "daily_good_luck_gold" -> enrichDailyGold(s, status);
            case "lucky_bet_suffix" -> enrichLuckyBet(s, status);
            default -> s;
        };
    }

    private JSONObject enrichFriendDeposit(JSONObject s, JSONObject status) {
        double weeklyDeposit = status.getDoubleValue("weekly_friend_deposit_total");
        double remain = status.getDoubleValue("remain_bonus_total");
        boolean canClaim = Boolean.TRUE.equals(status.getBoolean("can_claim"));
        JSONObject next = status.getJSONObject("next_tier");
        s.put("can_claim_now", canClaim);
        s.put("weekly_friend_deposit_total", weeklyDeposit);
        s.put("remain_bonus_total", remain);
        s.put("name", str(status, "activity_name", s.getString("name")));
        if (canClaim) {
            s.put("summary", String.format("当周邀请好友累计存款 ¥%.2f，现可领取赏金 ¥%.2f（3倍流水）",
                weeklyDeposit, remain));
        } else if (next != null) {
            s.put("summary", String.format("当周邀请好友累计存款 ¥%.2f，暂未达标；距下一档还差存款 ¥%.2f",
                weeklyDeposit, next.getDoubleValue("need_more_deposit")));
        } else {
            s.put("summary", String.format("当周邀请好友累计存款 ¥%.2f，本周赏金已全部领取或暂无可领", weeklyDeposit));
        }
        return s;
    }

    private JSONObject enrichWeeklySports(JSONObject s, JSONObject status) {
        JSONObject claim = status.getJSONObject("claim");
        JSONObject current = status.getJSONObject("current_week");
        boolean canClaim = claim != null && Boolean.TRUE.equals(claim.getBoolean("can_claim"));
        double claimBonus = claim == null ? 0 : claim.getDoubleValue("bonus");
        double currentBet = current == null ? 0 : current.getDoubleValue("weekly_valid_bet");
        s.put("can_claim_now", canClaim);
        s.put("remain_bonus_total", claimBonus);
        s.put("name", str(status, "activity_name", s.getString("name")));
        if (canClaim) {
            s.put("summary", String.format("上周体育有效投注达标，现可领取体育周周领彩金 ¥%.2f（3倍体育流水）", claimBonus));
        } else if (current != null) {
            JSONObject next = new JSONObject();
            next.put("need_more_bet", current.getDoubleValue("need_more_bet"));
            s.put("summary", String.format("本周体育有效投注 ¥%.2f，次周一 12:00 后领取上周彩金；继续投注可提升档位",
                currentBet));
        } else {
            s.put("summary", "体育周周领：请在体育场馆投注，次周一领取上周最高档彩金");
        }
        return s;
    }

    private JSONObject enrichDailySports(JSONObject s, JSONObject status) {
        JSONObject claim = status.getJSONObject("claim");
        JSONObject current = status.getJSONObject("current_day");
        boolean canClaim = claim != null && Boolean.TRUE.equals(claim.getBoolean("can_claim"));
        double claimBonus = claim == null ? 0 : claim.getDoubleValue("bonus");
        double currentBet = current == null ? 0 : current.getDoubleValue("daily_valid_bet");
        s.put("can_claim_now", canClaim);
        s.put("remain_bonus_total", claimBonus);
        s.put("name", str(status, "activity_name", s.getString("name")));
        if (canClaim) {
            s.put("summary", String.format("昨日体育有效投注达标，现可领取日投注闯关彩金 ¥%.2f（3倍体育流水）", claimBonus));
        } else if (current != null) {
            s.put("summary", String.format("今日体育有效投注 ¥%.2f，次日领取昨日彩金；继续投注可提升档位", currentBet));
        } else {
            s.put("summary", "日投注闯关：请在体育场馆投注，次日向 IM 客服发送「体育每日任务」领取");
        }
        return s;
    }

    private JSONObject enrichSportWinStreak(JSONObject s, JSONObject status) {
        JSONObject claim = status.getJSONObject("claim");
        JSONObject streak = status.getJSONObject("streak");
        boolean canApply = claim != null && Boolean.TRUE.equals(claim.getBoolean("can_apply"));
        boolean canClaimRedpacket = claim != null && Boolean.TRUE.equals(claim.getBoolean("can_claim_redpacket"));
        double claimBonus = claim == null ? 0 : claim.getDoubleValue("bonus");
        int currentStreak = streak == null ? 0 : streak.getIntValue("current_streak");
        int currentLevel = streak == null ? 0 : streak.getIntValue("current_level");
        int currentThreshold = streak == null ? 0 : streak.getIntValue("current_streak_threshold");
        int need = streak == null ? 0 : streak.getIntValue("need_more_wins");
        int nextThreshold = streak == null ? 0 : streak.getIntValue("next_streak_threshold");
        double weeklyBet = streak == null ? 0 : streak.getDoubleValue("weekly_valid_bet");
        double todayBet = streak == null ? 0 : streak.getDoubleValue("today_valid_bet");
        boolean alreadyClaimed = claim != null && Boolean.TRUE.equals(claim.getBoolean("already_claimed_this_week"));
        s.put("can_claim_now", canApply || canClaimRedpacket);
        s.put("remain_bonus_total", claimBonus);
        s.put("name", str(status, "activity_name", s.getString("name")));
        if (canClaimRedpacket) {
            s.put("summary", String.format("当前 %d 连红资格审核已通过，可领取体育连红彩金 ¥%.2f（3倍体育流水）",
                currentStreak, claimBonus));
        } else if (canApply) {
            s.put("summary", String.format("当前 %d 连红达标，可提交申请；系统自动核查通过后即可领取 ¥%.2f",
                currentStreak, claimBonus));
        } else if (alreadyClaimed) {
            s.put("summary", String.format("本周已领取连红彩金；当前 %d 连红", currentStreak));
        } else if (streak != null && currentLevel > 0 && currentThreshold > 0) {
            StringBuilder msg = new StringBuilder(String.format(
                "本周体育有效投注 ¥%.2f（今日 ¥%.2f），当前 %d 连红，已达 %d 连红档（¥%.2f）",
                weeklyBet, todayBet, currentStreak, currentThreshold, claimBonus));
            if (need > 0 && nextThreshold > 0) {
                msg.append(String.format("；距 %d 连红档还差 %d 场赢单", nextThreshold, need));
            }
            s.put("summary", msg.toString());
        } else if (streak != null) {
            if (need > 0 && nextThreshold > 0) {
                s.put("summary", String.format(
                    "本周体育有效投注 ¥%.2f（今日 ¥%.2f），当前 %d 连红，距 %d 连红档还差 %d 场赢单",
                    weeklyBet, todayBet, currentStreak, nextThreshold, need));
            } else {
                s.put("summary", String.format(
                    "本周体育有效投注 ¥%.2f（今日 ¥%.2f），当前 %d 连红；本周已领取或暂未达最低 3 连红",
                    weeklyBet, todayBet, currentStreak));
            }
        } else {
            s.put("summary", "体育连红挑战：单关体育赢单累计连红，向 IM 客服发送「体育连红挑战」领取");
        }
        return s;
    }

    private JSONObject enrichCommission(JSONObject s, JSONObject status) {
        double claimedCommission = status.getDoubleValue("claimed_commission_total");
        double remain = status.getDoubleValue("remain_bonus_total");
        boolean canClaim = Boolean.TRUE.equals(status.getBoolean("can_claim"));
        JSONObject next = status.getJSONObject("next_tier");
        s.put("can_claim_now", canClaim);
        s.put("claimed_commission_total", claimedCommission);
        s.put("remain_bonus_total", remain);
        s.put("name", str(status, "activity_name", s.getString("name")));
        if (canClaim) {
            s.put("summary", String.format("累计已领推广佣金 ¥%.2f，现可领取佣金加码 ¥%.2f（3倍流水）",
                claimedCommission, remain));
        } else if (next != null) {
            s.put("summary", String.format("累计已领推广佣金 ¥%.2f，加码暂未达标；距下一档还差佣金 ¥%.2f",
                claimedCommission, next.getDoubleValue("need_more_commission")));
        } else {
            s.put("summary", String.format("累计已领推广佣金 ¥%.2f，加码奖励已全部领取或暂无可领", claimedCommission));
        }
        return s;
    }

    private JSONObject enrichInvite(JSONObject s, JSONObject quota) {
        JSONObject invite = quota.getJSONObject("invite_friends");
        if (invite == null) {
            s.put("summary", "邀请好友送彩礼：活动进行中，邀请数据暂不可用");
            return s;
        }
        int valid = invite.getIntValue("valid_promotion_count");
        boolean canClaim = Boolean.TRUE.equals(quota.getBoolean("canClaim"));
        JSONArray claimable = invite.getJSONArray("claimable_tiers");
        double claimableTotal = sumTierBonus(claimable);
        JSONObject next = invite.getJSONObject("next_tier");
        s.put("can_claim_now", canClaim && claimableTotal > 0);
        s.put("valid_promotion_count", valid);
        s.put("remain_bonus_total", claimableTotal);
        if (canClaim && claimableTotal > 0) {
            s.put("summary", String.format("有效推广 %d 人，现可领取彩礼 ¥%.2f", valid, claimableTotal));
        } else if (next != null) {
            s.put("summary", String.format("有效推广 %d 人，距下一档还差 %d 人（下一档 ¥%.2f）",
                valid, next.getIntValue("need_more_valid"), next.getDoubleValue("bonus")));
        } else {
            s.put("summary", String.format("有效推广 %d 人，彩礼奖励已全部领取或暂无可领", valid));
        }
        return s;
    }

    private JSONObject enrichDailyGold(JSONObject s, JSONObject status) {
        boolean canClaim = Boolean.TRUE.equals(status.getBoolean("current_claimable"));
        String statDate = status.getString("stat_date");
        BigDecimal adjusted = status.getBigDecimal("adjusted_loss");
        BigDecimal currentBonus = status.getBigDecimal("current_bonus");
        s.put("can_claim_now", canClaim);
        s.put("stat_date", statDate);
        s.put("adjusted_loss", money(adjusted));
        if (canClaim && currentBonus != null) {
            s.put("remain_bonus_total", currentBonus.doubleValue());
            s.put("summary", String.format("统计日 %s，昨日电子/捕鱼/棋牌/德州有效负盈利 ¥%s，可领好运金 ¥%s",
                statDate, money(adjusted), money(currentBonus)));
        } else {
            // aagames 返回扁平字段：next_level(int)、need_more_loss、next_bonus（非嵌套对象）
            Double needMore = status.getDouble("need_more_loss");
            Double nextBonus = status.getDouble("next_bonus");
            if (needMore != null && needMore > 0 && nextBonus != null && nextBonus > 0) {
                s.put("summary", String.format("统计日 %s，昨日电子/捕鱼/棋牌/德州有效负盈利 ¥%s；距下一关还差亏损 ¥%.2f（可领 ¥%.2f）",
                    statDate, money(adjusted), needMore, nextBonus));
            } else {
                s.put("summary", String.format("统计日 %s，昨日电子/捕鱼/棋牌/德州有效负盈利 ¥%s，暂无可领好运金",
                    statDate, money(adjusted)));
            }
        }
        return s;
    }

    private JSONObject enrichLuckyBet(JSONObject s, JSONObject status) {
        int claimableCount = LuckyBetSuffixServiceImpl.listApplicable(status).size();
        boolean canClaim = claimableCount > 0;
        s.put("can_claim_now", canClaim);
        s.put("claimable_bet_count", claimableCount);
        if (canClaim) {
            s.put("summary", String.format("有 %d 笔注单符合尾号加码条件，可申请领取", claimableCount));
        } else {
            s.put("summary", "暂无符合尾号加码条件的可领取注单");
        }
        return s;
    }

    private JSONObject baseSummary(String key, long activityId, String name, String detailTool, boolean canClaim) {
        JSONObject s = new JSONObject(new LinkedHashMap<>());
        s.put("key", key);
        s.put("activity_id", activityId);
        s.put("name", name);
        s.put("detail_tool", detailTool);
        s.put("can_claim_now", canClaim);
        s.put("participating", true);
        s.put("fetch_ok", false);
        return s;
    }

    private long resolveActivityId(ActivityCatalog catalog) {
        if (boximProps.getActivities() == null) {
            return 0L;
        }
        return catalog.idGetter().applyAsLong(boximProps.getActivities());
    }

    private static double sumTierBonus(JSONArray tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < tiers.size(); i++) {
            JSONObject t = tiers.getJSONObject(i);
            if (t != null) {
                sum += t.getDoubleValue("bonus");
            }
        }
        return round2(sum);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String money(BigDecimal v) {
        if (v == null) {
            return "0.00";
        }
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static void appendClaimBadge(StringBuilder sb, JSONObject s) {
        if (Boolean.TRUE.equals(s.getBoolean("can_claim_now"))) {
            sb.append("（可领取");
            if (s.get("remain_bonus_total") != null) {
                sb.append(" ¥").append(s.get("remain_bonus_total"));
            }
            sb.append("）");
        } else if (Boolean.TRUE.equals(s.getBoolean("applied"))) {
            sb.append("（红包待领）");
        } else if (Boolean.TRUE.equals(s.getBoolean("already_claimed"))) {
            sb.append("（已领取）");
        }
    }

    private static String str(JSONObject o, String key, String fallback) {
        if (o == null) {
            return fallback;
        }
        String v = o.getString(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private record FetchResult(JSONObject status, String error) {
    }

    private record ActivityCatalog(String key, String defaultName, String detailTool,
                                   java.util.function.ToLongFunction<BoximIntegrationProperties.Activities> idGetter) {
    }
}
