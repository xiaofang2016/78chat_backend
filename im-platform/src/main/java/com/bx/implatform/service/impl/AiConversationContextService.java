package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.entity.AiCsHumanMessage;
import com.bx.implatform.entity.PrivateMessage;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.mapper.AiCsHumanMessageMapper;
import com.bx.implatform.mapper.PrivateMessageMapper;
import com.bx.implatform.util.ConvUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 为 AI 客服组装私聊上下文：近期消息历史 + 当前话题（活动）推断 + Redis last_topic 缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConversationContextService {

    private static final Pattern VAGUE_CLAIM = Pattern.compile(
        "可以领(取)?多少|能领(取)?多少|领多少|领取多少|现在(可以|能)?领|还能领|可领多少|有多少(可以|能)领");

    /** 省略主语的短追问，需结合 last_topic */
    private static final Pattern VAGUE_FOLLOW_UP = Pattern.compile(
        "这个|那个|继续|接着|还有吗|现在呢|然后呢|怎么说|怎么样了|可以吗|行不行|怎么领|如何领|帮我领");

    /** 用户明确在问活动/领取时，不走纯盈亏查询 */
    private static final Pattern ACTIVITY_INTENT = Pattern.compile(
        "活动|优惠|领取|好运金|雪中送炭|彩金|能领|可领|关卡|救济");

    private static final List<ActivityTopic> ACTIVITY_TOPICS = List.of(
        new ActivityTopic("wallet_ledger", "账户流水/转账记录",
            Pattern.compile("转账记录|账户流水|帐变|账变|资金明细|钱包流水|钱包记录|账单记录|资金记录"),
            "get_wallet_record", 0),
        new ActivityTopic("sport_turnover", "体育流水查询",
            Pattern.compile("体育流水|体育有效投注|体育投注.*多少|体育.*投了多少|体育.*流水|"
                + "(今天|今日|昨天|昨日|本周|上周).{0,8}体育.{0,8}(流水|有效投注|投了多少)"),
            "get_sport_turnover_summary", 0),
        new ActivityTopic("game_turnover", "游戏流水查询",
            Pattern.compile("游戏流水|投注流水|投注明细|游戏有效投注|游戏.*投了多少|"
                + "(今天|今日|昨天|昨日|本周).{0,8}(?:游戏)?(?:流水|有效投注)(?!.*体育)"),
            "get_bet_log", 0),
        new ActivityTopic("bet_log_pnl", "投注盈亏查询",
            Pattern.compile("盈亏|输赢|盈利|亏损|负盈利|正盈利|赚了多少|输了多少|赢了多少|"
                + "(昨天|昨日|今天|今日|本周|本月).*[盈亏赢输亏]|"
                + "[盈亏赢输亏].*(昨天|昨日|今天|今日|本周|本月)|"
                + "投注.*[盈亏赢输]|[盈亏赢输].*投注"),
            "get_bet_log", 0),
        new ActivityTopic("available_activities", "可参加活动总览",
            Pattern.compile("可以参加|能参加|有哪些活动|什么活动|有什么活动|哪些活动|活动有哪些|能参加什么|可以参与|"
                + "有哪些优惠|能领什么活动|参加的活动|可参加.*活动|活动可以|可以领什么活动"),
            "get_available_activities", 0),
        new ActivityTopic("friend_deposit_bonus_4215", "好友存款赏金4215",
            Pattern.compile("好友存款|存款赏金|存款领赏金|存款送4215|4215|好友任务.*存款|赚钱.*好友任务|积少成多|friend_deposit_bonus_4215"),
            "get_friend_deposit_bonus_status", 143523),
        new ActivityTopic("weekly_sports_bonus", "体育周周领",
            Pattern.compile("体育周周领|周周领|体育周.*领|最高领588|588.*体育|weekly_sports_bonus"),
            "get_weekly_sports_bonus_status", 0),
        new ActivityTopic("daily_sports_bonus", "日投注闯关",
            Pattern.compile("日投注闯关|体育每日任务|每日.*体育任务|最高领58|58.*体育|daily_sports_bonus"),
            "get_daily_sports_bonus_status", 0),
        new ActivityTopic("sport_win_streak", "体育连红挑战",
            Pattern.compile("体育连红挑战|连红挑战|体育连红|连红|最高领88|88.*连红|sport_win_streak"),
            "get_sport_win_streak_status", 0),
        new ActivityTopic("commission_bonus_999", "佣金加码999奖上奖",
            Pattern.compile("佣金加码|999奖上奖|奖上奖|佣金加倍|躺着赚佣金|每周任务.*佣金|commission_bonus_999"),
            "get_commission_bonus_status", 143755),
        new ActivityTopic("invite_friends_bonus", "邀请好友送彩礼",
            Pattern.compile("邀请好友|送彩礼|拉新奖励|推广好友.*奖|invite_friends_bonus"),
            "get_invite_friends_quota", 150137),
        new ActivityTopic("daily_good_luck_gold", "天天好运金",
            Pattern.compile("天天好运金|好运金|雪中送炭|昨日亏损彩金|daily_good_luck_gold"),
            "get_daily_good_luck_gold_status", 134255),
        new ActivityTopic("spread_commission", "推广佣金（待领）",
            Pattern.compile("未领取佣金|待领佣金|推广佣金有多少|能领多少佣金|no_get_benefits"),
            "get_spread_info", 0),
        new ActivityTopic("lucky_bet_suffix", "幸运电子尾号加码",
            Pattern.compile("幸运电子|幸运老虎机|尾号.*加码|注单尾号|lucky_bonus_apply|lucky_bet"),
            "get_lucky_bet_eligibility", 132499)
    );

    private static final Map<String, String> TOOL_TO_TOPIC_KEY = Map.ofEntries(
        Map.entry("get_available_activities", "available_activities"),
        Map.entry("get_friend_deposit_bonus_status", "friend_deposit_bonus_4215"),
        Map.entry("get_weekly_sports_bonus_status", "weekly_sports_bonus"),
        Map.entry("get_daily_sports_bonus_status", "daily_sports_bonus"),
        Map.entry("get_sport_win_streak_status", "sport_win_streak"),
        Map.entry("get_commission_bonus_status", "commission_bonus_999"),
        Map.entry("get_invite_friends_quota", "invite_friends_bonus"),
        Map.entry("get_daily_good_luck_gold_status", "daily_good_luck_gold"),
        Map.entry("get_spread_info", "spread_commission"),
        Map.entry("get_spread_commission_status", "spread_commission"),
        Map.entry("get_lucky_bet_eligibility", "lucky_bet_suffix"),
        Map.entry("get_sport_turnover_summary", "sport_turnover"),
        Map.entry("get_wallet_record", "wallet_ledger"),
        Map.entry("get_bet_log", "bet_log_pnl")
    );

    private final PrivateMessageMapper privateMessageMapper;
    private final AiCustomerServiceProperties aiProps;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AiCsHumanMessageMapper aiCsHumanMessageMapper;

    public AiChatContext build(Long userId, Long botUserId, String currentUserMessage) {
        AiChatContext ctx = new AiChatContext();
        if (userId == null || botUserId == null) {
            return ctx;
        }

        List<String> historyTexts = new ArrayList<>();
        int max = Math.max(0, aiProps.getHistoryMaxMessages());
        if (max > 0) {
            loadHistory(userId, botUserId, currentUserMessage, max, ctx, historyTexts);
        }

        ctx.setCurrentUserMessage(currentUserMessage);
        resolveTopic(userId, botUserId, ctx, currentUserMessage, historyTexts);
        return ctx;
    }

    /** 工具调用成功后更新 last_topic（比正文关键词更可靠）。 */
    public void rememberTopicFromTool(Long userId, Long botUserId, String toolName) {
        if (userId == null || botUserId == null || toolName == null || toolName.isBlank()) {
            return;
        }
        String topicKey = TOOL_TO_TOPIC_KEY.get(toolName);
        if (topicKey == null) {
            return;
        }
        saveLastTopic(userId, botUserId, topicKey, "tool:" + toolName);
    }

    /** 本轮对话结束后，将已解析话题写入 Redis。 */
    public void persistTopicIfPresent(Long userId, Long botUserId, AiChatContext ctx) {
        if (userId == null || botUserId == null || ctx == null) {
            return;
        }
        if (ctx.getActiveTopicKey() == null || ctx.getActiveTopicKey().isBlank()) {
            return;
        }
        String source = ctx.isFollowUp() ? "follow-up" : "resolved";
        if (ctx.isFromCache()) {
            source = "cache-hit";
        }
        saveLastTopic(userId, botUserId, ctx.getActiveTopicKey(), source);
    }

    private Set<Long> loadHumanMessageIds(Long botUserId, Long customerUserId) {
        LambdaQueryWrapper<AiCsHumanMessage> q = Wrappers.lambdaQuery();
        q.eq(AiCsHumanMessage::getBotUserId, botUserId);
        q.eq(AiCsHumanMessage::getCustomerUserId, customerUserId);
        List<AiCsHumanMessage> rows = aiCsHumanMessageMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (AiCsHumanMessage row : rows) {
            ids.add(row.getMessageId());
        }
        return ids;
    }

    private void loadHistory(Long userId, Long botUserId, String currentUserMessage, int max,
                             AiChatContext ctx, List<String> historyTexts) {
        String convKey = ConvUtil.buildConvKey(userId, botUserId);
        LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(PrivateMessage::getConvKey, convKey);
        wrapper.in(PrivateMessage::getType, MessageType.TEXT.code(), MessageType.LUCKY_BONUS_APPLY.code());
        wrapper.ne(PrivateMessage::getStatus, MessageStatus.RECALL.code());
        wrapper.orderByDesc(PrivateMessage::getId);
        wrapper.last("limit " + max);
        List<PrivateMessage> rows = privateMessageMapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Collections.reverse(rows);

        Set<Long> humanMsgIds = loadHumanMessageIds(botUserId, userId);

        boolean skipLastUserDup = false;
        PrivateMessage last = rows.get(rows.size() - 1);
        if (last.getSendId() != null && last.getSendId().equals(userId)
            && currentUserMessage != null && currentUserMessage.equals(last.getContent())) {
            skipLastUserDup = true;
        }

        int charBudget = Math.max(500, aiProps.getHistoryMaxChars());
        List<JSONObject> pending = new ArrayList<>();
        List<String> pendingTexts = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PrivateMessage m = rows.get(i);
            if (m.getId() != null && humanMsgIds.contains(m.getId())) {
                continue;
            }
            if (skipLastUserDup && i == rows.size() - 1) {
                continue;
            }
            String text = toDialogText(m);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (text.length() > 800) {
                text = text.substring(0, 800) + "…";
            }
            boolean fromUser = m.getSendId() != null && m.getSendId().equals(userId);
            JSONObject msg = new JSONObject(new LinkedHashMap<>());
            msg.put("role", fromUser ? "user" : "assistant");
            msg.put("content", fromUser ? text : compressAssistantReplay(text));
            pending.add(msg);
            pendingTexts.add(text);
        }
        int start = 0;
        int used = 0;
        for (int i = pending.size() - 1; i >= 0; i--) {
            int len = pending.get(i).getString("content").length();
            if (used > 0 && used + len > charBudget) {
                start = i + 1;
                break;
            }
            used += len;
            start = i;
        }
        for (int i = start; i < pending.size(); i++) {
            historyTexts.add(pendingTexts.get(i));
            ctx.getHistoryMessages().add(pending.get(i));
        }
    }

    /** 活动总览整段进历史后，下一轮会被原样再讲一遍。 */
    private static String compressAssistantReplay(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("当前可参与的活动如下") || trimmed.startsWith("当前暂无进行中的活动")) {
            return "（上一轮已发送该商户的活动名单，不要再整段重复）";
        }
        return text;
    }

    private void resolveTopic(Long userId, Long botUserId, AiChatContext ctx,
                              String currentUserMessage, List<String> historyTexts) {
        ActivityTopic explicit = matchTopic(currentUserMessage);
        if (explicit != null) {
            applyTopic(ctx, explicit, false);
            return;
        }

        boolean vague = isVagueUtterance(currentUserMessage);
        if (!vague) {
            return;
        }

        // 1) 从近期历史反查
        List<String> scan = new ArrayList<>(historyTexts);
        if (currentUserMessage != null && !currentUserMessage.isBlank()) {
            scan.add(currentUserMessage);
        }
        for (int i = scan.size() - 1; i >= 0; i--) {
            ActivityTopic t = matchTopic(scan.get(i));
            if (t != null) {
                applyTopic(ctx, t, true);
                return;
            }
        }

        // 2) Redis last_topic 兜底
        CachedTopic cached = loadLastTopic(userId, botUserId);
        if (cached != null) {
            ActivityTopic t = findTopicByKey(cached.getKey());
            if (t != null) {
                applyTopic(ctx, t, true);
                ctx.setFromCache(true);
                log.info("[ai-cs] last_topic cache hit userId={} botUserId={} topic={}",
                    userId, botUserId, cached.getKey());
            }
        }
    }

    private boolean isVagueUtterance(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return VAGUE_CLAIM.matcher(text).find() || VAGUE_FOLLOW_UP.matcher(text).find();
    }

    private void applyTopic(AiChatContext ctx, ActivityTopic topic, boolean followUp) {
        ctx.setActiveTopicKey(topic.key());
        ctx.setActiveTopicLabel(topic.label());
        ctx.setActiveTopicTool(topic.toolName());
        ctx.setFollowUp(followUp);
    }

    private ActivityTopic matchTopic(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (AvailableActivitiesServiceImpl.matchesActivityListQuestion(text)) {
            ActivityTopic overview = findTopicByKey("available_activities");
            if (overview != null) {
                return overview;
            }
        }
        for (ActivityTopic t : ACTIVITY_TOPICS) {
            if (!t.pattern().matcher(text).find()) {
                continue;
            }
            // 「昨日负盈利」等纯盈亏：若未提活动，优先 bet_log，不被活动话题抢走
            if ("bet_log_pnl".equals(t.key()) && ACTIVITY_INTENT.matcher(text).find()) {
                continue;
            }
            return t;
        }
        return null;
    }

    private ActivityTopic findTopicByKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (ActivityTopic t : ACTIVITY_TOPICS) {
            if (key.equals(t.key())) {
                return t;
            }
        }
        return null;
    }

    private void saveLastTopic(Long userId, Long botUserId, String topicKey, String source) {
        ActivityTopic topic = findTopicByKey(topicKey);
        if (topic == null) {
            return;
        }
        try {
            JSONObject payload = new JSONObject(new LinkedHashMap<>());
            payload.put("key", topic.key());
            payload.put("label", topic.label());
            payload.put("tool", topic.toolName());
            payload.put("activity_id", topic.activityId());
            payload.put("source", source);
            payload.put("updated_at", System.currentTimeMillis());
            long ttl = Math.max(300L, aiProps.getLastTopicTtlSeconds());
            redisTemplate.opsForValue().set(
                lastTopicRedisKey(userId, botUserId),
                payload.toJSONString(),
                ttl,
                TimeUnit.SECONDS
            );
            log.debug("[ai-cs] last_topic saved userId={} botUserId={} topic={} source={}",
                userId, botUserId, topicKey, source);
        } catch (Exception e) {
            log.warn("[ai-cs] last_topic save failed userId={} err={}", userId, e.getMessage());
        }
    }

    private CachedTopic loadLastTopic(Long userId, Long botUserId) {
        try {
            Object raw = redisTemplate.opsForValue().get(lastTopicRedisKey(userId, botUserId));
            if (raw == null) {
                return null;
            }
            JSONObject json = JSON.parseObject(String.valueOf(raw));
            if (json == null || json.getString("key") == null) {
                return null;
            }
            CachedTopic c = new CachedTopic();
            c.setKey(json.getString("key"));
            c.setLabel(json.getString("label"));
            c.setTool(json.getString("tool"));
            return c;
        } catch (Exception e) {
            log.warn("[ai-cs] last_topic load failed userId={} err={}", userId, e.getMessage());
            return null;
        }
    }

    private static String lastTopicRedisKey(Long userId, Long botUserId) {
        return RedisKey.IM_AI_CS_LAST_TOPIC + ":" + userId + ":" + botUserId;
    }

    private String toDialogText(PrivateMessage m) {
        if (m == null || m.getContent() == null) {
            return null;
        }
        if (MessageType.TEXT.code().equals(m.getType())) {
            return m.getContent().trim();
        }
        if (MessageType.LUCKY_BONUS_APPLY.code().equals(m.getType())) {
            try {
                JSONObject card = JSON.parseObject(m.getContent());
                if (card == null) {
                    return "[活动领取卡片]";
                }
                String kind = card.getString("kind");
                String text = card.getString("text");
                String button = card.getString("buttonText");
                String bonus = card.getString("bonus");
                StringBuilder sb = new StringBuilder("[活动领取卡片");
                if (kind != null && !kind.isBlank()) {
                    sb.append(" kind=").append(kind);
                }
                if (bonus != null) {
                    sb.append(" bonus=").append(bonus);
                }
                sb.append("]");
                if (text != null && !text.isBlank()) {
                    sb.append(" ").append(text);
                } else if (button != null && !button.isBlank()) {
                    sb.append(" ").append(button);
                }
                return sb.toString();
            } catch (Exception ignore) {
                return "[活动领取卡片]";
            }
        }
        return null;
    }

    public String buildTopicHintBlock(AiChatContext ctx) {
        if (ctx == null || ctx.getActiveTopicKey() == null || ctx.getActiveTopicKey().isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【对话上下文 — 必读】");
        if (ctx.isFollowUp()) {
            sb.append("用户本轮是**延续上一轮话题的追问**（如「可以领多少」「还能领吗」），");
            sb.append("省略了活动名称。**必须**按最近讨论的活动回答，禁止擅自切换到其他活动。\n");
        }
        if (ctx.isFromCache()) {
            sb.append("（上一轮话题来自会话缓存 last_topic，与用户刚聊内容一致）\n");
        }
        if ("bet_log_pnl".equals(ctx.getActiveTopicKey())) {
            sb.append("- 当前话题：**投注盈亏查询**（用户未问活动）。\n");
            sb.append("- **只调用** get_bet_log：按用户所指日期传 start_time/end_time（先 get_server_time 推算昨天/今天等）；");
            sb.append("汇总返回记录中 winorlosses 得**全部分类**合计盈亏。\n");
            sb.append("- **禁止**调用天天好运金等活动工具，**禁止**解释活动规则或领取资格，只回答盈亏数字。\n");
            return sb.toString();
        }
        if ("sport_turnover".equals(ctx.getActiveTopicKey())) {
            sb.append("- 当前话题：**体育流水/体育有效投注查询**。\n");
            sb.append("- **必须调用** get_sport_turnover_summary；用 today/yesterday/this_week 等字段的 ");
            sb.append("valid_bet 答体育流水，activity_eligible_valid_bet 答活动计入口径（与日闯关/周周领一致）。\n");
            sb.append("- 需要逐笔明细时再调用 get_bet_log 并传 game_cate_id=4；**禁止**用钱包 get_balance 或活动 status 代替流水。\n");
            return sb.toString();
        }
        if ("wallet_ledger".equals(ctx.getActiveTopicKey())) {
            sb.append("- 当前话题：**账户流水/转账记录**。\n");
            sb.append("- 上文若已注入【账户流水 — 已预取】，必须按那些记录回答时间、类型和金额。\n");
            sb.append("- **禁止**说无法查询、暂不支持，**禁止**改口让用户自己去【我的】→【转账】找入口。\n");
            sb.append("- 【主钱包转账】操作说明只用于用户问怎么转入、转出、一键回收，不用于查记录。\n");
            return sb.toString();
        }
        if ("game_turnover".equals(ctx.getActiveTopicKey())) {
            sb.append("- 当前话题：**游戏流水/投注明细查询**。\n");
            sb.append("- **必须调用** get_bet_log（不传 game_cate_id=全部分类）；用 stat.valid_bet 答时段有效投注合计，");
            sb.append("list[] 答逐笔明细；相对日期先 get_server_time。\n");
            sb.append("- 用户问「流水要求/打码/还差多少流水」时另调 get_balance 的 flow_require/need_flow_money。\n");
            sb.append("- **禁止**用 get_sport_turnover_summary（那是体育专用）。\n");
            return sb.toString();
        }
        sb.append("- 当前话题活动：").append(ctx.getActiveTopicLabel());
        sb.append("\n- 查询工具：").append(ctx.getActiveTopicTool()).append("\n");
        if (ctx.isFollowUp()) {
            sb.append("- 用户问「可以领多少/能领多少/领取多少」时，调用 ").append(ctx.getActiveTopicTool())
                .append("，用 remain_bonus_total / claimable_tiers 等字段回答，");
            if ("spread_commission".equals(ctx.getActiveTopicKey())) {
                sb.append("勿回答佣金加码或好友存款赏金。");
            } else if ("friend_deposit_bonus_4215".equals(ctx.getActiveTopicKey())) {
                sb.append("勿回答推广佣金或佣金加码999。");
            } else if ("commission_bonus_999".equals(ctx.getActiveTopicKey())) {
                sb.append("勿回答推广佣金或好友存款赏金。");
            } else {
                sb.append("勿混入其他活动数据。");
            }
        }
        return sb.toString();
    }

    @Data
    public static class AiChatContext {
        private JSONArray historyMessages = new JSONArray();
        private String currentUserMessage;
        private String activeTopicKey;
        private String activeTopicLabel;
        private String activeTopicTool;
        private boolean followUp;
        /** 本轮话题是否来自 Redis last_topic */
        private boolean fromCache;
    }

    @Data
    private static class CachedTopic {
        private String key;
        private String label;
        private String tool;
    }

    private record ActivityTopic(String key, String label, Pattern pattern, String toolName, int activityId) {
    }
}
