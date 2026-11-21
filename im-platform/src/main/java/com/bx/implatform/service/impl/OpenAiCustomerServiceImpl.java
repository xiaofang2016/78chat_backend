package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.config.props.BoximIntegrationProperties;
import com.bx.implatform.contant.AiBrandingConstants;
import com.bx.implatform.contant.OfficialCsNames;
import com.bx.implatform.config.props.AiKnowledgeBaseProperties;
import com.bx.implatform.config.props.OpenAIProperties;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.UserType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.openai.OpenAiChatCompletionClient;
import com.bx.implatform.service.AiCustomerService;
import com.bx.implatform.service.AiKnowledgeBaseService;
import com.bx.implatform.service.AiToolService;
import com.bx.implatform.service.AvailableActivitiesService;
import com.bx.implatform.service.CommissionBonus999Service;
import com.bx.implatform.service.FriendDepositBonus4215Service;
import com.bx.implatform.service.SportWeeklyBonusService;
import com.bx.implatform.service.SportDailyBonusService;
import com.bx.implatform.service.SportWinStreakService;
import com.bx.implatform.service.InviteFriendsBonusService;
import com.bx.implatform.vo.ai.AiReplyResult;
import com.bx.implatform.vo.ai.LuckyBonusApplyCardVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiCustomerServiceImpl implements AiCustomerService {

    /** 单次对话最多 tool_call 轮数，防止 AI 死循环刷工具消耗 token */
    private static final int MAX_TOOL_ROUNDS = 4;

    private final OpenAiChatCompletionClient openAiChatCompletionClient;
    private final OpenAIProperties openAIProperties;
    private final AiCustomerServiceProperties aiProps;
    private final AiKnowledgeBaseProperties kbProps;
    private final AiToolService toolService;
    private final AiKnowledgeBaseService knowledgeBaseService;
    private final InviteFriendsBonusService inviteFriendsBonusService;
    private final CommissionBonus999Service commissionBonus999Service;
    private final FriendDepositBonus4215Service friendDepositBonus4215Service;
    private final SportWeeklyBonusService sportWeeklyBonusService;
    private final SportDailyBonusService sportDailyBonusService;
    private final SportWinStreakService sportWinStreakService;
    private final AiConversationContextService conversationContextService;
    private final AvailableActivitiesService availableActivitiesService;
    private final BoximIntegrationProperties boximProps;
    private final UserMapper userMapper;

    private long sportWinStreakActivityId() {
        return boximProps.getActivities().getSportWinStreakId();
    }

    @Override
    public AiReplyResult reply(Long userId, Long botUserId, String userMessage) {
        if (!aiProps.isEnabled()) {
            return AiReplyResult.textOnly("");
        }
        openAiChatCompletionClient.ensureChatConfigured();
        if (userMessage == null) {
            userMessage = "";
        }

        log.info("[ai-cs] reply start userId={} botId={} text={}", userId, botUserId, clip(userMessage));
        String crossed = crossMerchantReply(userId, botUserId);
        if (crossed != null) {
            log.info("[ai-cs] skip cross-merchant userId={} botId={} merchant={}",
                userId, botUserId, merchantNoOf(userId));
            return AiReplyResult.textOnly(crossed);
        }
        boolean guest = isGuestUser(userId);
        List<LuckyBonusApplyCardVO> applyCards = new ArrayList<>();
        String prefetchedActivitiesBlock = "";
        JSONObject prefetchedActivitiesOverview = null;

        if (!guest && availableActivitiesService.matchesListKeywords(userMessage)) {
            try {
                JSONObject overview = availableActivitiesService.fetchOverview(userId);
                prefetchedActivitiesOverview = overview;
                prefetchedActivitiesBlock = availableActivitiesService.formatOverviewForPrompt(overview);
                JSONObject wrap = new JSONObject(new LinkedHashMap<>());
                wrap.put("ok", true);
                wrap.put("data", overview);
                applyCards.addAll(ActivityApplyCardBuilder.fromAvailableActivitiesToolResults(
                    wrap, botUserId, sportWinStreakActivityId()));
                int count = overview == null ? 0 : overview.getIntValue("activity_count");
                log.info("[ai-cs] prefetched activities userId={} count={} empty={} errors={}",
                    userId, count, count <= 0, overview == null ? null : overview.get("fetch_errors"));
            } catch (Exception e) {
                log.warn("[ai-cs] prefetch activities failed userId={} err={}", userId, e.getMessage());
            }
        }

        AiConversationContextService.AiChatContext chatContext =
            conversationContextService.build(userId, botUserId, userMessage);
        if (chatContext.getActiveTopicKey() != null) {
            log.info("[ai-cs] topic userId={} topic={} followUp={} fromCache={}",
                userId, chatContext.getActiveTopicKey(), chatContext.isFollowUp(), chatContext.isFromCache());
        }

        String prefetchedTurnoverBlock = guest ? "" : prefetchTurnoverBlock(userId, chatContext);

        String kbContext = "";
        if (kbProps.appliesTo(botUserId)) {
            try {
                kbContext = knowledgeBaseService.buildRetrievalContext(userMessage, botUserId);
            } catch (Exception e) {
                log.warn("[ai-cs] knowledge base retrieval failed: {}", e.getMessage());
            }
        }

        // 消息历史：system + 近期私聊 + 当前 user；tool 轮次内再追加 assistant(tool_calls) + tool
        JSONArray messages = new JSONArray();
        messages.add(systemMessage(kbContext, chatContext, prefetchedActivitiesBlock, prefetchedTurnoverBlock,
            resolvePlatformName(botUserId), resolveAgentName(botUserId), guest));
        JSONArray history = chatContext.getHistoryMessages();
        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                messages.add(history.getJSONObject(i));
            }
        }
        messages.add(simpleMessage("user", userMessage));

        JSONArray tools = guest ? guestTools(toolService.getToolsSchema()) : toolService.getToolsSchema();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JSONObject reply = chatCompletion(messages, tools);
            JSONObject assistantMsg = extractAssistantMessage(reply);
            if (assistantMsg == null) {
                log.info("[ai-cs] model reply empty userId={} round={}", userId, round);
                return finishReply(userId, botUserId, userMessage, chatContext, "", applyCards,
                    prefetchedActivitiesOverview);
            }
            JSONArray toolCalls = assistantMsg.getJSONArray("tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                String content = assistantMsg.getString("content");
                log.info("[ai-cs] model reply userId={} round={} text={}", userId, round, clip(content));
                return finishReply(userId, botUserId, userMessage, chatContext,
                    content == null ? "" : content.trim(), applyCards, prefetchedActivitiesOverview);
            }
            // 把 assistant message 原样塞回历史（OpenAI 协议要求）
            messages.add(assistantMsg);
            // 逐个执行 tool_call，把结果作为 role=tool message 追加
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String tcId = tc.getString("id");
                JSONObject fn = tc.getJSONObject("function");
                String name = fn == null ? null : fn.getString("name");
                String args = fn == null ? null : fn.getString("arguments");
                log.info("[ai-cs.tool-call] userId={} round={} name={} args={}", userId, round, name, args);
                JSONObject result;
                try {
                    result = toolService.invoke(userId, name, args);
                } catch (Exception e) {
                    log.warn("[ai-cs.tool-call] failed userId={} name={} err={}", userId, name, e.getMessage(), e);
                    result = new JSONObject();
                    result.put("ok", false);
                    result.put("error", "tool_invocation_failed");
                    result.put("message", e.getMessage());
                }
                log.info("[ai-cs.tool-result] userId={} name={} ok={} empty={} body={}",
                    userId, name,
                    result != null && Boolean.TRUE.equals(result.getBoolean("ok")),
                    result == null || result.isEmpty(),
                    clip(result == null ? "" : result.toJSONString()));
                appendApplyCardsFromTool(name, result, botUserId, applyCards);
                if (result != null && Boolean.TRUE.equals(result.getBoolean("ok"))) {
                    conversationContextService.rememberTopicFromTool(userId, botUserId, name);
                }
                JSONObject toolMsg = new JSONObject(new LinkedHashMap<>());
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tcId);
                toolMsg.put("name", name);
                toolMsg.put("content", JSON.toJSONString(result));
                messages.add(toolMsg);
            }
            // 进入下一轮，让 GPT 看到 tool result 后给出最终回答（或继续要工具）
        }

        // 超出最大轮数：兜底再请求一次（不带 tools，强制给文本结果）
        log.warn("[ai-cs] tool rounds exhausted (>={}), userId={}, final fallback", MAX_TOOL_ROUNDS, userId);
        JSONObject finalReply = chatCompletion(messages, null);
        JSONObject finalMsg = extractAssistantMessage(finalReply);
        if (finalMsg == null) {
            return finishReply(userId, botUserId, userMessage, chatContext, "", applyCards,
                prefetchedActivitiesOverview);
        }
        String content = finalMsg.getString("content");
        return finishReply(userId, botUserId, userMessage, chatContext,
            content == null ? "" : content.trim(), applyCards, prefetchedActivitiesOverview);
    }

    /**
     * 回复结束后补发活动卡片：避免模型只文字说明、未调工具时用户看不到领取按钮。
     */
    private AiReplyResult finishReply(Long userId, Long botUserId, String userMessage,
                                     AiConversationContextService.AiChatContext chatContext,
                                     String text, List<LuckyBonusApplyCardVO> cards,
                                     JSONObject prefetchedActivitiesOverview) {
        supplementAvailableActivitiesCards(userId, botUserId, userMessage, chatContext, cards);
        supplementInviteFriendsCards(userId, botUserId, userMessage, chatContext, cards);
        supplementCommissionBonusCards(userId, botUserId, userMessage, chatContext, cards);
        supplementFriendDepositBonusCards(userId, botUserId, userMessage, chatContext, cards);
        supplementWeeklySportsBonusCards(userId, botUserId, userMessage, chatContext, cards);
        supplementDailySportsBonusCards(userId, botUserId, userMessage, chatContext, cards);
        supplementSportWinStreakCards(userId, botUserId, userMessage, chatContext, cards);
        text = ensureActivityOverviewInReply(userId, userMessage, text, prefetchedActivitiesOverview);
        log.info("[ai-cs] final reply userId={} botId={} textEmpty={} text={}",
            userId, botUserId, text == null || text.isBlank(), clip(text));
        conversationContextService.persistTopicIfPresent(userId, botUserId, chatContext);
        AiReplyResult r = AiReplyResult.textOnly(text);
        if (cards != null) {
            for (LuckyBonusApplyCardVO card : cards) {
                r.addApplyCard(card);
            }
        }
        return r;
    }

    private String ensureActivityOverviewInReply(Long userId, String userMessage, String text,
                                                 JSONObject prefetchedActivitiesOverview) {
        if (!availableActivitiesService.matchesListKeywords(userMessage)) {
            return text == null ? "" : text;
        }
        JSONObject overview = prefetchedActivitiesOverview;
        if (overview == null) {
            try {
                overview = availableActivitiesService.fetchOverview(userId);
            } catch (Exception e) {
                log.warn("[ai-cs] activity overview for reply failed userId={} err={}", userId, e.getMessage());
                return text == null ? "" : text;
            }
        }
        String block = availableActivitiesService.formatOverviewForUserReply(overview);
        if (block.isBlank()) {
            return text == null ? "" : text;
        }
        // 活动总览以 API 实时拼装为准；若保留模型生成的列表，会复读聊天历史里的旧投注额（如今日 50 万、本周 0）
        return block;
    }

    private void supplementAvailableActivitiesCards(Long userId, Long botUserId, String userMessage,
                                                    AiConversationContextService.AiChatContext chatContext,
                                                    List<LuckyBonusApplyCardVO> cards) {
        if (!availableActivitiesService.matchesListKeywords(userMessage)) {
            return;
        }
        try {
            JSONObject overview = availableActivitiesService.fetchOverview(userId);
            JSONObject wrap = new JSONObject();
            wrap.put("ok", true);
            wrap.put("data", overview);
            List<LuckyBonusApplyCardVO> extra =
                ActivityApplyCardBuilder.fromAvailableActivitiesToolResults(
                    wrap, botUserId, sportWinStreakActivityId());
            if (extra.isEmpty() || cards == null) {
                return;
            }
            java.util.Set<String> seenTxn = new java.util.LinkedHashSet<>();
            for (LuckyBonusApplyCardVO c : cards) {
                if (c != null && c.getTransactionId() != null && !c.getTransactionId().isBlank()) {
                    seenTxn.add(c.getTransactionId());
                }
            }
            int added = 0;
            for (LuckyBonusApplyCardVO card : extra) {
                String txn = card.getTransactionId();
                if (txn != null && !txn.isBlank() && !seenTxn.add(txn)) {
                    continue;
                }
                cards.add(card);
                added++;
            }
            if (added > 0) {
                log.info("[ai-cs] supplemented available-activities cards userId={} count={}", userId, added);
            }
        } catch (Exception e) {
            log.warn("[ai-cs] supplement available-activities cards failed userId={} err={}", userId, e.getMessage());
        }
    }

    private void supplementFriendDepositBonusCards(Long userId, Long botUserId, String userMessage,
                                                   AiConversationContextService.AiChatContext chatContext,
                                                   List<LuckyBonusApplyCardVO> cards) {
        if (!shouldSupplementActivity(userMessage, chatContext, "friend_deposit_bonus_4215",
            friendDepositBonus4215Service::matchesActivityKeywords)) {
            return;
        }
        boolean hasCard = cards != null && cards.stream()
            .anyMatch(c -> c != null && "friend_deposit_bonus_4215".equals(c.getKind()));
        if (hasCard) {
            return;
        }
        try {
            JSONObject status = friendDepositBonus4215Service.fetchStatus(userId);
            List<LuckyBonusApplyCardVO> extra = ActivityApplyCardBuilder.fromFriendDepositBonusResults(status, botUserId);
            if (!extra.isEmpty() && cards != null) {
                cards.addAll(extra);
                log.info("[ai-cs] supplemented friend-deposit-bonus cards userId={} count={}", userId, extra.size());
            }
        } catch (Exception e) {
            log.warn("[ai-cs] supplement friend-deposit-bonus cards failed userId={} err={}", userId, e.getMessage());
        }
    }

    private void supplementWeeklySportsBonusCards(Long userId, Long botUserId, String userMessage,
                                                  AiConversationContextService.AiChatContext chatContext,
                                                  List<LuckyBonusApplyCardVO> cards) {
        if (!shouldSupplementActivity(userMessage, chatContext, "weekly_sports_bonus",
            sportWeeklyBonusService::matchesActivityKeywords)) {
            return;
        }
        boolean hasCard = cards != null && cards.stream()
            .anyMatch(c -> c != null && "weekly_sports_bonus".equals(c.getKind()));
        if (hasCard) {
            return;
        }
        try {
            JSONObject status = sportWeeklyBonusService.fetchStatus(userId);
            List<LuckyBonusApplyCardVO> extra = ActivityApplyCardBuilder.fromWeeklySportsBonusToolResults(
                wrapOk(status), botUserId);
            if (!extra.isEmpty() && cards != null) {
                cards.addAll(extra);
                log.info("[ai-cs] supplemented weekly-sports-bonus cards userId={} count={}", userId, extra.size());
            }
        } catch (Exception e) {
            log.warn("[ai-cs] supplement weekly-sports-bonus cards failed userId={} err={}", userId, e.getMessage());
        }
    }

    private void supplementDailySportsBonusCards(Long userId, Long botUserId, String userMessage,
                                                 AiConversationContextService.AiChatContext chatContext,
                                                 List<LuckyBonusApplyCardVO> cards) {
        if (!shouldSupplementActivity(userMessage, chatContext, "daily_sports_bonus",
            sportDailyBonusService::matchesActivityKeywords)) {
            return;
        }
        boolean hasCard = cards != null && cards.stream()
            .anyMatch(c -> c != null && "daily_sports_bonus".equals(c.getKind()));
        if (hasCard) {
            return;
        }
        try {
            JSONObject status = sportDailyBonusService.fetchStatus(userId);
            List<LuckyBonusApplyCardVO> extra = ActivityApplyCardBuilder.fromDailySportsBonusToolResults(
                wrapOk(status), botUserId);
            if (!extra.isEmpty() && cards != null) {
                cards.addAll(extra);
                log.info("[ai-cs] supplemented daily-sports-bonus cards userId={} count={}", userId, extra.size());
            }
        } catch (Exception e) {
            log.warn("[ai-cs] supplement daily-sports-bonus cards failed userId={} err={}", userId, e.getMessage());
        }
    }

    private void supplementSportWinStreakCards(Long userId, Long botUserId, String userMessage,
                                               AiConversationContextService.AiChatContext chatContext,
                                               List<LuckyBonusApplyCardVO> cards) {
        if (!availableActivitiesService.matchesListKeywords(userMessage)
            && !shouldSupplementActivity(userMessage, chatContext, "sport_win_streak",
            sportWinStreakService::matchesActivityKeywords)) {
            return;
        }
        boolean hasCard = cards != null && cards.stream()
            .anyMatch(c -> c != null && "sport_win_streak".equals(c.getKind()));
        if (hasCard) {
            return;
        }
        try {
            JSONObject status = sportWinStreakService.fetchStatus(userId);
            List<LuckyBonusApplyCardVO> extra = ActivityApplyCardBuilder.fromSportWinStreakToolResults(
                wrapOk(status), botUserId, sportWinStreakActivityId());
            if (!extra.isEmpty() && cards != null) {
                cards.addAll(extra);
                log.info("[ai-cs] supplemented sport-win-streak cards userId={} count={}", userId, extra.size());
            }
        } catch (Exception e) {
            log.warn("[ai-cs] supplement sport-win-streak cards failed userId={} err={}", userId, e.getMessage());
        }
    }

    private JSONObject wrapOk(JSONObject data) {
        JSONObject wrap = new JSONObject(new LinkedHashMap<>());
        wrap.put("ok", true);
        wrap.put("data", data);
        return wrap;
    }

    private void supplementCommissionBonusCards(Long userId, Long botUserId, String userMessage,
                                                AiConversationContextService.AiChatContext chatContext,
                                                List<LuckyBonusApplyCardVO> cards) {
        if (!shouldSupplementActivity(userMessage, chatContext, "commission_bonus_999",
            commissionBonus999Service::matchesActivityKeywords)) {
            return;
        }
        boolean hasCard = cards != null && cards.stream()
            .anyMatch(c -> c != null && "commission_bonus_999".equals(c.getKind()));
        if (hasCard) {
            return;
        }
        try {
            JSONObject status = commissionBonus999Service.fetchStatus(userId);
            List<LuckyBonusApplyCardVO> extra = ActivityApplyCardBuilder.fromCommissionBonusResults(status, botUserId);
            if (!extra.isEmpty() && cards != null) {
                cards.addAll(extra);
                log.info("[ai-cs] supplemented commission-bonus cards userId={} count={}", userId, extra.size());
            }
        } catch (Exception e) {
            log.warn("[ai-cs] supplement commission-bonus cards failed userId={} err={}", userId, e.getMessage());
        }
    }

    private boolean shouldSupplementActivity(String userMessage,
                                             AiConversationContextService.AiChatContext chatContext,
                                             String topicKey,
                                             java.util.function.Predicate<String> keywordMatcher) {
        if (keywordMatcher.test(userMessage)) {
            return true;
        }
        return chatContext != null && chatContext.isFollowUp()
            && topicKey.equals(chatContext.getActiveTopicKey());
    }

    private void supplementInviteFriendsCards(Long userId, Long botUserId, String userMessage,
                                              AiConversationContextService.AiChatContext chatContext,
                                              List<LuckyBonusApplyCardVO> cards) {
        if (!shouldSupplementActivity(userMessage, chatContext, "invite_friends_bonus",
            inviteFriendsBonusService::matchesActivityKeywords)) {
            return;
        }
        boolean hasInviteCard = cards != null && cards.stream()
            .anyMatch(c -> c != null && "invite_friends_bonus".equals(c.getKind()));
        if (hasInviteCard) {
            return;
        }
        try {
            JSONObject quota = inviteFriendsBonusService.fetchQuota(userId);
            List<LuckyBonusApplyCardVO> extra = ActivityApplyCardBuilder.fromInviteFriendsQuotaResults(quota, botUserId);
            if (!extra.isEmpty() && cards != null) {
                cards.addAll(extra);
                log.info("[ai-cs] supplemented invite-friends cards userId={} count={}", userId, extra.size());
            }
        } catch (Exception e) {
            log.warn("[ai-cs] supplement invite-friends cards failed userId={} err={}", userId, e.getMessage());
        }
    }

    // ---------- OpenAI HTTP ----------

    private JSONObject chatCompletion(JSONArray messages, JSONArray tools) {
        return openAiChatCompletionClient.chatCompletion(messages, tools, aiProps.getTemperature(),
            aiProps.getMaxTokens());
    }

    private JSONObject extractAssistantMessage(JSONObject rawJson) {
        JSONArray choices = rawJson == null ? null : rawJson.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject first = choices.getJSONObject(0);
        if (first == null) {
            return null;
        }
        return first.getJSONObject("message");
    }

    private JSONObject simpleMessage(String role, String content) {
        JSONObject m = new JSONObject(new LinkedHashMap<>());
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private void appendApplyCardsFromTool(String name, JSONObject result, Long botUserId,
                                          List<LuckyBonusApplyCardVO> applyCards) {
        try {
            if ("get_lucky_bet_eligibility".equals(name)) {
                applyCards.addAll(ActivityApplyCardBuilder.fromLuckyBetToolResults(result, botUserId));
            } else if ("get_daily_good_luck_gold_status".equals(name)) {
                LuckyBonusApplyCardVO card = ActivityApplyCardBuilder.fromDailyGoodLuckGoldToolResult(result, botUserId);
                if (card != null) {
                    applyCards.add(card);
                }
            } else if ("get_invite_friends_quota".equals(name)) {
                applyCards.addAll(ActivityApplyCardBuilder.fromInviteFriendsQuotaToolResults(result, botUserId));
            } else if ("get_commission_bonus_status".equals(name)) {
                applyCards.addAll(ActivityApplyCardBuilder.fromCommissionBonusToolResults(result, botUserId));
            } else if ("get_friend_deposit_bonus_status".equals(name)) {
                applyCards.addAll(ActivityApplyCardBuilder.fromFriendDepositBonusToolResults(result, botUserId));
            } else if ("get_weekly_sports_bonus_status".equals(name)) {
                applyCards.addAll(ActivityApplyCardBuilder.fromWeeklySportsBonusToolResults(result, botUserId));
            } else if ("get_daily_sports_bonus_status".equals(name)) {
                applyCards.addAll(ActivityApplyCardBuilder.fromDailySportsBonusToolResults(result, botUserId));
            } else if ("get_sport_win_streak_status".equals(name)) {
                applyCards.addAll(ActivityApplyCardBuilder.fromSportWinStreakToolResults(
                    result, botUserId, sportWinStreakActivityId()));
            } else if ("get_available_activities".equals(name)) {
                applyCards.addAll(ActivityApplyCardBuilder.fromAvailableActivitiesToolResults(
                    result, botUserId, sportWinStreakActivityId()));
            }
        } catch (Exception e) {
            log.warn("[ai-cs] apply card from tool {} failed: {}", name, e.getMessage());
        }
    }

    private String prefetchTurnoverBlock(Long userId, AiConversationContextService.AiChatContext chatContext) {
        if (chatContext == null || chatContext.getActiveTopicKey() == null) {
            return "";
        }
        String topic = chatContext.getActiveTopicKey();
        try {
            if ("sport_turnover".equals(topic)) {
                JSONObject result = toolService.invoke(userId, "get_sport_turnover_summary", "{}");
                if (result == null || !Boolean.TRUE.equals(result.getBoolean("ok"))) {
                    log.warn("[ai-cs] prefetch sport turnover failed userId={} result={}", userId, result);
                    return "";
                }
                JSONObject data = result.getJSONObject("data");
                if (data == null) {
                    return "";
                }
                StringBuilder sb = new StringBuilder();
                sb.append("【体育流水数据 — 已预取，必须直接使用，禁止说无法查询】\n");
                appendSportPeriod(sb, "今日", data.getJSONObject("today"));
                appendSportPeriod(sb, "昨日", data.getJSONObject("yesterday"));
                appendSportPeriod(sb, "本周", data.getJSONObject("this_week"));
                log.info("[ai-cs] prefetched sport turnover userId={}", userId);
                return sb.toString();
            }
            if ("wallet_ledger".equals(topic)) {
                return prefetchWalletLedgerBlock(userId, chatContext.getCurrentUserMessage());
            }
            if ("game_turnover".equals(topic)) {
                JSONObject result = toolService.invoke(userId, "get_bet_log", "{}");
                if (result == null || !Boolean.TRUE.equals(result.getBoolean("ok"))) {
                    log.warn("[ai-cs] prefetch game betlog failed userId={} result={}", userId, result);
                    return "";
                }
                JSONObject data = result.getJSONObject("data");
                if (data == null) {
                    return "";
                }
                JSONObject stat = data.getJSONObject("stat");
                StringBuilder sb = new StringBuilder();
                sb.append("【游戏流水数据 — 已预取，必须直接使用，禁止说无法查询】\n");
                if (stat != null) {
                    sb.append("- 有效投注合计 stat.valid_bet: ").append(stat.getString("valid_bet")).append('\n');
                    sb.append("- 投注合计 stat.bet: ").append(stat.getString("bet")).append('\n');
                    sb.append("- 输赢合计 stat.win: ").append(stat.getString("win")).append('\n');
                }
                log.info("[ai-cs] prefetched game betlog userId={}", userId);
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("[ai-cs] prefetch turnover failed userId={} topic={} err={}",
                userId, topic, e.getMessage());
        }
        return "";
    }

    private String prefetchWalletLedgerBlock(Long userId, String message) {
        String text = message == null ? "" : message;
        JSONObject args = new JSONObject();
        String scope = "全部账户流水";
        if (text.contains("转账")) {
            args.put("type", "6");
            scope = "额度转账";
        } else if (text.contains("提现") || text.contains("取款")) {
            args.put("type", "2");
            scope = "会员提现";
        } else if (text.contains("存款") || text.contains("充值")) {
            args.put("type", "1");
            scope = "会员存款";
        } else if (text.contains("红利")) {
            args.put("type", "5");
            scope = "领取红利";
        }
        String tzId = aiProps.getTimezone();
        if (tzId == null || tzId.isBlank()) {
            tzId = "Asia/Shanghai";
        }
        LocalDate today = ZonedDateTime.now(ZoneId.of(tzId)).toLocalDate();
        LocalDate start = null;
        LocalDate end = null;
        String when = "最近记录";
        if (text.contains("今天") || text.contains("今日")) {
            start = today;
            end = today;
            when = "今天 " + today;
        } else if (text.contains("昨天") || text.contains("昨日")) {
            start = today.minusDays(1);
            end = start;
            when = "昨天 " + start;
        } else if (text.contains("前天")) {
            start = today.minusDays(2);
            end = start;
            when = "前天 " + start;
        } else if (text.contains("本周")) {
            start = today.with(DayOfWeek.MONDAY);
            end = today;
            when = "本周 " + start + " 至 " + end;
        } else if (text.contains("本月")) {
            start = today.withDayOfMonth(1);
            end = today;
            when = "本月 " + start + " 至 " + end;
        }
        if (start != null) {
            args.put("start_time", start.toString());
            args.put("end_time", end.toString());
        }
        try {
            JSONObject result = toolService.invoke(userId, "get_wallet_record", args.toJSONString());
            if (result == null || !Boolean.TRUE.equals(result.getBoolean("ok"))) {
                log.warn("[ai-cs] prefetch wallet ledger failed userId={} result={}", userId, result);
                return "";
            }
            JSONObject data = result.getJSONObject("data");
            JSONArray rows = data == null ? null : data.getJSONArray("data");
            StringBuilder sb = new StringBuilder();
            sb.append("【账户流水 — 已预取，必须直接使用，禁止说无法查询】\n");
            sb.append("- 范围：").append(when).append(" / ").append(scope).append('\n');
            if (rows == null || rows.isEmpty()) {
                sb.append("- 该时段没有记录。直接告诉用户没有，不要改口引导去页面自己查。\n");
                return sb.toString();
            }
            int shown = Math.min(rows.size(), 20);
            Object total = data.get("total");
            sb.append("- 条数：本页 ").append(rows.size());
            if (total != null) {
                sb.append("，合计 ").append(total);
            }
            sb.append('\n');
            for (int i = 0; i < shown; i++) {
                JSONObject row = rows.getJSONObject(i);
                if (row == null) {
                    continue;
                }
                sb.append("- ").append(row.getString("created_at"));
                sb.append(' ').append(walletTypeName(row.getString("type")));
                String detail = firstNonBlank(row.getString("log"), row.getString("second_type_name"));
                if (detail != null) {
                    sb.append(' ').append(detail);
                }
                sb.append(" 金额 ").append(row.get("money"));
                if (row.get("after_money") != null) {
                    sb.append(" 余额 ").append(row.get("after_money"));
                }
                sb.append('\n');
            }
            log.info("[ai-cs] prefetched wallet ledger userId={} rows={}", userId, rows.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("[ai-cs] prefetch wallet ledger failed userId={} err={}", userId, e.getMessage());
            return "";
        }
    }

    private static String walletTypeName(String type) {
        if (type == null) {
            return "账变";
        }
        return switch (type) {
            case "1" -> "会员存款";
            case "2" -> "会员提现";
            case "3" -> "充值补单";
            case "4" -> "提现补单";
            case "5" -> "领取红利";
            case "6" -> "额度转账";
            case "7" -> "人工操作";
            case "9" -> "存款送";
            case "10" -> "代理代充";
            case "11" -> "切换货币";
            default -> "账变";
        };
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void appendSportPeriod(StringBuilder sb, String label, JSONObject period) {
        if (period == null) {
            return;
        }
        sb.append("- ").append(label).append(" valid_bet: ").append(period.get("valid_bet"));
        sb.append("，activity_eligible_valid_bet: ").append(period.get("activity_eligible_valid_bet"));
        sb.append("，bet_count: ").append(period.get("bet_count")).append('\n');
    }

    private static String clip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "...";
    }

    /**
     * 小红只代表吉祥体育，小绿只代表 UED，客服美美只代表说球帝。身份跟机器人走，不跟提问用户临时切换。
     */
    private String resolvePlatformName(Long botUserId) {
        return OfficialCsNames.platformName(botUserId);
    }

    private String resolveAgentName(Long botUserId) {
        String name = OfficialCsNames.displayName(botUserId);
        return name == null ? "" : name;
    }

    /** 用户商户和这个客服账号不是一家时，不查账户，只告知去找对应客服。 */
    private String crossMerchantReply(Long userId, Long botUserId) {
        String botMerchant = OfficialCsNames.merchantNo(botUserId);
        if (botMerchant == null) {
            return null;
        }
        String userMerchant = merchantNoOf(userId);
        if (userMerchant.isEmpty() || botMerchant.equals(userMerchant)) {
            return null;
        }
        Long ownBotId = OfficialCsNames.botIdForMerchant(userMerchant);
        String ownName = OfficialCsNames.displayName(ownBotId);
        String botName = OfficialCsNames.displayName(botUserId);
        if (ownName == null || botName == null) {
            return null;
        }
        return "这里是" + botName + "，不办理另一家的业务。请联系" + ownName + "。";
    }

    private String merchantNoOf(Long userId) {
        if (userId == null) {
            return "";
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getMerchantNo() == null) {
            return "";
        }
        return user.getMerchantNo().trim();
    }

    private boolean isGuestUser(Long userId) {
        if (userId == null) {
            return false;
        }
        User user = userMapper.selectById(userId);
        return user != null && UserType.GUEST.getValue().equals(user.getType());
    }

    /** 访客只能问时间，账户类工具不交给模型。 */
    private JSONArray guestTools(JSONArray tools) {
        JSONArray allowed = new JSONArray();
        if (tools == null) {
            return allowed;
        }
        for (int i = 0; i < tools.size(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            JSONObject fn = tool == null ? null : tool.getJSONObject("function");
            if (fn != null && "get_server_time".equals(fn.getString("name"))) {
                allowed.add(tool);
            }
        }
        return allowed;
    }

    private JSONObject systemMessage(String knowledgeContext,
                                   AiConversationContextService.AiChatContext chatContext,
                                   String prefetchedActivitiesBlock,
                                   String prefetchedTurnoverBlock,
                                   String platformName,
                                   String agentName,
                                   boolean guest) {
        // 在配置 system-prompt 基础上追加严格"防编数据"约束
        StringBuilder sb = new StringBuilder();
        sb.append(buildCurrentTimeBlock()).append("\n\n");
        if (prefetchedActivitiesBlock != null && !prefetchedActivitiesBlock.isBlank()) {
            sb.append(prefetchedActivitiesBlock).append("\n\n");
        }
        if (prefetchedTurnoverBlock != null && !prefetchedTurnoverBlock.isBlank()) {
            sb.append(prefetchedTurnoverBlock).append("\n\n");
        }
        String topicHint = conversationContextService.buildTopicHintBlock(chatContext);
        if (!topicHint.isBlank()) {
            sb.append(topicHint).append("\n\n");
        }
        if (knowledgeContext != null && !knowledgeContext.isBlank()) {
            sb.append(knowledgeContext).append("\n\n");
            sb.append("【知识库优先级】上文【知识库参考】中标记为 [官方知识库]、来源以 kb-entry- 开头的片段，")
                .append("为管理后台维护的官方条目，**必须最优先**用于回答；仅当官方条目不足以回答时，")
                .append("才可参考其他来源片段。条目里的「本平台」按当前平台名改口，不要原样说出 7PG。\n\n");
        }
        if (aiProps.getSystemPrompt() != null && !aiProps.getSystemPrompt().isBlank()) {
            sb.append(aiProps.getSystemPrompt()).append("\n\n");
        }
        sb.append(AiBrandingConstants.PROMPT_BLOCK);
        String gameSite = platformName == null || platformName.isBlank()
            ? AiBrandingConstants.GAME_SITE : platformName;
        sb.append("【好友语义 — 必读】用户说「好友」「邀请好友」「推广好友」时，默认指 **")
            .append(gameSite)
            .append("**里通过推广链接邀请注册的下级游戏用户（j_users.from_uid），**绝不是** ")
            .append(AiBrandingConstants.IM_PRODUCT)
            .append(" 通讯录里的好友关系。"
            + "查询这类数据只能用 get_invite_spread / get_invite_friends_quota，禁止查询或臆造通讯录好友列表；"
            + "回答时可用「邀请的下级」「推广好友」等表述，避免与 ")
            .append(AiBrandingConstants.IM_PRODUCT)
            .append(" 好友混淆。\n\n");
        sb.append("【严禁伪造数据】当用户询问任何具体业务数据（余额、流水、订单号、游戏记录、金额、" +
            "时间戳、单据状态、签到天数、红包记录、账户信息等）时：\n");
        sb.append("- **必须**先调用相应 tool 获取真实数据后再回答；\n");
        sb.append("- 在没有 tool 返回值的情况下，**绝不**自行猜测或编造任何具体数字、订单号、时间；\n");
        sb.append("- 用户问「现在几点」「今天/昨天/前天是几号」「当前时间」时，**只许**使用上文【当前系统时间】或调用 get_server_time；"
            + "禁止使用模型训练记忆里的年月（如 2023 年）；不得说「无法提供未来时间」或「无法告知具体时间」。\n");
        sb.append("- 说明「昨天」「上周」等相对日期时，必须基于【当前系统时间】推算后再回答。\n");
        sb.append("- 工具调用失败或返回 ok=false 时，要明确告诉用户'暂时无法查询，请稍后重试'，不可虚构内容；"
            + "但若上文已注入【活动总览数据 — 已预取】或【体育流水数据 — 已预取】或【游戏流水数据 — 已预取】，"
            + "必须直接用该数据回答，禁止说「无法查询」。\n");
        sb.append("- 工具返回的数据是当前对话用户本人的信息，不要询问用户的 userId 也不要让用户提供身份证/手机号；\n");
        sb.append("- 如果用户要求你查询其他人的数据、修改资料、转账、领奖等写操作，礼貌拒绝。\n");
        sb.append("- 不要重复上一轮已经发给用户的整段回复。用户这次问什么就只答这一句；"
            + "上一轮列过的活动名单、金额、规则不要再原样重发。\n");
        sb.append("- 用户问「可以参加的活动有哪些」「能参加什么活动」「有哪些优惠/活动」时："
            + "若上文已有【活动总览数据 — 已预取】则直接用它回答（无需再调工具）；否则调用 get_available_activities。"
            + "只逐条列出本次 activity_summaries / 预取块里的活动，顺序与条数以接口为准；"
            + "接口没有的活动一律不要写。不同商户的活动不同，禁止套用其他商户或记忆中的活动名单。"
            + "用每条 summary 组织自然语言；can_claim_now=true 时系统会自动展示领取按钮。"
            + "名单为空就说当前暂无进行中的活动。\n");
        sb.append("- **纯盈亏问题**（用户问盈亏/输赢/盈利/亏损/负盈利/昨天亏多少等，**未**提活动/优惠/领取/好运金）："
            + "只调用 get_bet_log，按用户时间范围传 start_time/end_time（相对日期先 get_server_time 推算）；"
            + "汇总 winorlosses 返回**该时段全部分类**合计盈亏；**禁止**调用任何活动 status 工具，"
            + "**禁止**扯活动规则、关卡、领取资格。\n");
        sb.append("- 用户问「活动」「优惠」「有活动吗」「优惠活动」「现在有什么活动」「能领什么」，"
            + "没有点名某一档彩金时："
            + "若上文已有【活动总览数据 — 已预取】则直接用它逐条回答（无需再调工具）；否则调用 get_available_activities。"
            + "只列本次名单，禁止改叫天天好运金，也禁止说无法查询。\n");
        sb.append("- 用户明确问「天天好运金」「雪中送炭」「昨日亏损彩金」时，才调用 get_daily_good_luck_gold_status；"
            + "说明 adjusted_loss 是【电子+捕鱼+棋牌+德州】合计亏损，"
            + "真人/彩票/体育不计入；可用 loss_by_cate 分项说明。"
            + "当前关卡 current_level / 可领彩金 current_bonus、下一关 need_more_loss / next_bonus；"
            + "若 current_claimable=true，系统会在回复后自动展示「领取」按钮，你不要在正文里写按钮或链接。"
            + "工具失败，或这项不在本次活动总览里，不要说无法查询，改用活动总览回答当前商户正在进行的活动。\n");
        sb.append("- 用户问「邀请好友」「推广好友」「送彩礼」「拉新奖励」时，必须调用 get_invite_friends_quota（活动 id 150137）；"
            + "此处好友=" + gameSite + " 邀请游戏下级；说明有效推广人数、各档奖励与剩余可领；"
            + "若 canClaim=true，系统会自动展示领取按钮。\n");
        sb.append("- 用户问「有多少个好友」「邀请了多少人」「哪些好友有有效投注」「谁充过值」时，"
            + "必须调用 get_invite_spread（friend_scope=aagames_invite_spread，非 "
            + AiBrandingConstants.IM_PRODUCT + " 通讯录好友）；"
            + "用 register_count 答邀请下级总人数，valid_bet_user_count 答有有效投注人数，"
            + "列举用户名用 valid_bet_invitees / no_valid_bet_invitees；与 quota 互补。\n");
        sb.append("【多轮对话 — 必读】你会收到近期私聊历史，以及系统注入的 last_topic（上一轮话题缓存）。"
            + "用户省略主语的追问（如「可以领多少」「还能领吗」「现在呢」）"
            + "默认延续**上一轮正在讨论的活动/话题**，按该话题调用对应 tool，禁止擅自切换到其他活动。\n");
        sb.append("- 「能领多少佣金」「待领佣金」明确带**佣金**二字时，才调用 get_spread_info；"
            + "若上一轮在聊好友存款赏金/4215，用户只说「可以领多少」，应调用 get_friend_deposit_bonus_status，"
            + "不是 get_spread_info。\n");
        sb.append("- 用户问「未领取佣金」「能领多少佣金」「待领佣金」「推广佣金有多少」时，必须调用 get_spread_info（/spreadinfo）；"
            + "用 no_get_benefits 回答未领取佣金，用 has_get_benefits 说明已领、total_benefits 说明累计；"
            + "也可含 register_count、spread_url 等推广信息。\n");
        sb.append("- 用户问本周/本月推广统计、推广趋势、好友汇总数据时，调用 get_spread_stat（/spreadstat）。\n");
        sb.append("- 用户问佣金记录、佣金明细、spread_commission_log、某段时间佣金列表时，"
            + "调用 get_spread_commission_log（/spread_commission_log）。\n");
        sb.append("- 用户问「能否享受加倍活动」「佣金加码」「999奖上奖」「躺着赚佣金」时，"
            + "必须调用 get_commission_bonus_status（活动 id 143755）；说明 claimed_commission_total、"
            + "各档门槛与 remain_bonus_total；若 can_claim=true，系统会自动展示领取按钮。\n");
        sb.append("- 用户同时问基础佣金与加码活动（如「能领多少佣金，能享受加倍吗」）时，"
            + "须同时调用 get_spread_info（no_get_benefits）与 get_commission_bonus_status，分别说明两类金额。\n");
        sb.append("- 用户问「好友存款赏金」「好友存款送4215」「好友任务存款奖」「赚钱好友任务」时，"
            + "必须调用 get_friend_deposit_bonus_status（活动 id 143523）；说明 weekly_friend_deposit_total、"
            + "week_start/week_end、各档存款门槛与 remain_bonus_total；若 can_claim=true，系统会自动展示领取按钮。\n");
        sb.append("- 用户问「体育流水」「体育有效投注」「今天/本周体育投了多少」等**体育流水**时，"
            + "必须优先调用 get_sport_turnover_summary（与体育活动统计口径一致）；"
            + "需要逐笔明细时再 get_bet_log（game_cate_id=4）。**不要**用活动 status 代替流水。\n");
        sb.append("- 用户问「游戏流水」「投注流水」「投注明细」「今天有效投注多少」（未提体育）时，"
            + "必须调用 get_bet_log（不传 game_cate_id）；用 stat.valid_bet 答合计、list[] 答明细。"
            + "问「流水要求/打码/还差多少流水」时调用 get_balance 的 flow_require/need_flow_money。\n");
        sb.append("- 用户问「体育连红挑战」「连红挑战」「体育连红」「最高领88」时，"
            + "必须调用 get_sport_win_streak_status；说明 current_streak、档位 bonus、单关投注≥200、赔率≥1.75。"
            + "流程分两步：先点卡片「提交申请」系统自动核查资格，通过后点「领取红包」；"
            + "claim.can_apply=true 展示申请按钮，claim.can_claim_redpacket=true 展示领取红包按钮；"
            + "你不要在正文里写按钮文字。\n");
        sb.append("- 用户问「排列五」「送彩票」「132467」「充值送彩票」「一夜暴富」等已下架活动时，"
            + "礼貌说明该活动暂不在 IM 客服办理，请用户前往活动优惠页或联系人工客服，禁止调用任何工具查询或展示领取按钮。\n");
        sb.append("- 用户提到「幸运电子活动」「幸运老虎机活动」或尾号 8 加码时，必须调用 get_lucky_bet_eligibility（活动 id 固定 132499）；"
            + "禁止向该工具传任何日期参数。用自然语言说明查询结果即可；"
            + "若存在 applicable=1 的注单，系统会为每条可领取注单各展示一张「申请加码金」卡片（可能多张），"
            + "你不要在回复里让用户点击按钮、不要输出 Markdown 链接或按钮文字。\n");
        sb.append("【提现到账时效 — 必读】用户询问提现/取款多久到账、到账时间时：审核通过后预计 **1-3 小时**到账。"
            + "必须使用该口径，禁止说「1到3个工作日」「几个工作日」等更长周期；"
            + "若已提交申请但超时未到账，引导联系客服查询。\n");
        sb.append("【H5 与官网 — 必读】用户询问 H5、手机网页版、官网、网站地址、域名时："
            + "用当前平台名「").append(gameSite).append("」回答，也可引导下载 APP。"
            + "知识库里的「本平台」「本平台官网」要改口成这个平台名。"
            + "知识库如果写了具体网址，用知识库的网址；没有就不要编造域名。"
            + "禁止说 7PG、7pg、7pg.IM、78chat、chat-h5。"
            + "禁止说「没有提供 H5 版本」「不支持 H5」。"
            + "打不开时让用户核对网址、切换网络、更换浏览器或清缓存、关闭代理，仍不行再联系在线客服。\n");
        sb.append("- 用户问「转账记录」「账户流水」「账变」「资金明细」「钱包记录」时，"
            + "必须使用已预取的账户流水或调用 get_wallet_record。"
            + "问转账记录时 type=6（额度转账），问全部账户流水时不要传 type；"
            + "今天/昨天用 get_server_time 换成 yyyy-MM-dd 传入 start_time 和 end_time。"
            + "按记录里的时间、类型、金额回答。禁止说无法查询，禁止改口让用户自己去【我的】→【转账】查看。\n");
        sb.append("【主钱包转账 — 必读】用户问怎么把钱转到主钱包、额度转入转出、游戏钱包回收、一键回收时，按以下口径回复。"
            + "用户要查转账记录或账户流水时不要用这段，改查账户流水：\n"
            + "您好，进入不同游戏平台前，需要先将额度转入对应游戏平台钱包。\n\n"
            + "转入：进入【我的】→【转账】，可开启自动转换，或使用自定义转账设置每次转入金额。\n\n"
            + "转出：若退出游戏后余额未自动带出，可进入【我的】→【转账】点击一键回收，或通过自定义转账将游戏平台余额转回主钱包。\n");
        sb.append("【下载APP福利 — 必读】用户问「APP已下载为什么福利领不了」「下载APP彩金」「APP登录奖励」时，按以下口径说明"
            + "（领取入口：【优惠-每日任务】），不要编造其他条件：\n"
            + "1. 参与账号需为注册近90天内的账号，并完成100元及以上存款；\n"
            + "2. 用户下载并登录APP，且存款100元及以上，可至【优惠-每日任务】领取下载APP彩金；\n"
            + "3. 每位会员仅限一个账号参与，且一个账号只能领取一次；\n"
            + "如发现违规套利行为，平台有权审核并扣除红利。"
            + "仅下载APP、未满足存款等条件时，明确告知尚不可领，引导先完成条件后再到每日任务领取。\n");
        if (agentName != null && !agentName.isBlank()) {
            sb.append("【客服身份 — 必读，优先于上文任何自称】你是「").append(agentName)
                .append("」。游戏业务对用户只说「").append(gameSite)
                .append("」，不要说 7PG、7pg、本平台。即时通讯说「职圈」。")
                .append("自我介绍、署名、用户问「你是谁 / 你是什么客服」时，只使用这个身份。")
                .append("不要自称 78CHAT 客服、7PG 客服、盒子IM客服。\n");
        }
        if (guest) {
            sb.append("【访客 — 必读，优先于上文任何账户查询要求】当前用户没有登录游戏账号。")
                .append("禁止调用余额、流水、活动、领取、邀请、佣金等账户工具，也不要编造这些数据。")
                .append("用户要查账户或办理账户业务时，请他先登录游戏站后再联系客服。")
                .append("忘记密码、登录不了、页面打不开这类问题，按知识库和已有口径说明，不要索要密码。\n");
        }
        return simpleMessage("system", sb.toString());
    }

    /** 每次对话注入服务器真实时刻，避免模型臆造 2023 等错误日期。 */
    private String buildCurrentTimeBlock() {
        String tzId = aiProps.getTimezone();
        if (tzId == null || tzId.isBlank()) {
            tzId = "Asia/Shanghai";
        }
        ZoneId zone = ZoneId.of(tzId);
        ZonedDateTime now = ZonedDateTime.now(zone);
        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String yesterday = now.minusDays(1).toLocalDate().toString();
        String dayBefore = now.minusDays(2).toLocalDate().toString();
        return "【当前系统时间】（北京时间 UTC+8，由服务器注入，回答一切与时间相关的问题时以此为准）\n"
            + "- 现在：" + formatted + "（时区 " + tzId + "）\n"
            + "- 今天：" + now.toLocalDate() + "\n"
            + "- 昨天：" + yesterday + "\n"
            + "- 前天：" + dayBefore;
    }
}
