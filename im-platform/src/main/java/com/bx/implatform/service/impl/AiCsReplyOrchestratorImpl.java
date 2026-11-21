package com.bx.implatform.service.impl;

import cn.hutool.core.util.StrUtil;
import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.config.props.AiKnowledgeBaseProperties;
import com.bx.implatform.entity.AiCsSession;
import com.bx.implatform.mapper.AiCsSessionMapper;
import com.bx.implatform.service.AiCsHandoffService;
import com.bx.implatform.service.AiCsReplyOrchestrator;
import com.bx.implatform.service.AiCustomerService;
import com.bx.implatform.service.AiKnowledgeBaseService;
import com.bx.implatform.service.BotPrivateMessageService;
import com.bx.implatform.util.AiCsHandoffEvaluator;
import com.bx.implatform.vo.ai.AiReplyResult;
import com.bx.implatform.vo.ai.LuckyBonusApplyCardVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class AiCsReplyOrchestratorImpl implements AiCsReplyOrchestrator {

    private static final String REASON_KEYWORD = "keyword";
    private static final String REASON_AUTO = "auto_no_answer";

    private final AiCustomerService aiCustomerService;
    private final AiKnowledgeBaseService knowledgeBaseService;
    private final BotPrivateMessageService botPrivateMessageService;
    private final AiCustomerServiceProperties aiCsProps;
    private final AiKnowledgeBaseProperties kbProps;
    private final RedissonClient redissonClient;
    private final AiCsSessionMapper sessionMapper;
    private final AiCsHandoffService aiCsHandoffService;

    private final ConcurrentHashMap<String, AtomicLong> generationMap = new ConcurrentHashMap<>();

    public AiCsReplyOrchestratorImpl(AiCustomerService aiCustomerService,
                                     AiKnowledgeBaseService knowledgeBaseService,
                                     BotPrivateMessageService botPrivateMessageService,
                                     AiCustomerServiceProperties aiCsProps,
                                     AiKnowledgeBaseProperties kbProps,
                                     RedissonClient redissonClient,
                                     AiCsSessionMapper sessionMapper,
                                     @Lazy AiCsHandoffService aiCsHandoffService) {
        this.aiCustomerService = aiCustomerService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.botPrivateMessageService = botPrivateMessageService;
        this.aiCsProps = aiCsProps;
        this.kbProps = kbProps;
        this.redissonClient = redissonClient;
        this.sessionMapper = sessionMapper;
        this.aiCsHandoffService = aiCsHandoffService;
    }

    @Override
    public void handleCustomerMessage(Long botUserId, Long customerUserId, Long userId,
                                      Long messageId, String userMsg) {
        log.info("[ai-cs] handle start bot={} customer={} userId={} msgId={} text={}",
            botUserId, customerUserId, userId, messageId, clip(userMsg));
        if (!aiCsProps.isEnabled() || !isAiCsBot(botUserId)) {
            log.info("[ai-cs] handle skip bot={} enabled={} isBot={}",
                botUserId, aiCsProps.isEnabled(), isAiCsBot(botUserId));
            return;
        }
        if (alreadyReplied(messageId)) {
            log.info("[ai-cs] skip duplicate reply bot={} customer={} msgId={}",
                botUserId, customerUserId, messageId);
            return;
        }
        String sessionKey = sessionKey(botUserId, customerUserId);
        long gen = generationMap.computeIfAbsent(sessionKey, k -> new AtomicLong(0)).incrementAndGet();
        try {
            if (isFormalTakeover(botUserId, customerUserId)) {
                log.info("[ai-cs] skip AI reply, operator takeover bot={} customer={}",
                    botUserId, customerUserId);
                return;
            }

            boolean monitored = isAdminMonitoring(botUserId, customerUserId);

            if (monitored && AiCsHandoffEvaluator.hasTransferKeyword(userMsg)) {
                if (isCurrentGeneration(sessionKey, gen)) {
                    aiCsHandoffService.transferToHuman(botUserId, customerUserId, REASON_KEYWORD, messageId);
                }
                return;
            }

            double kbScore = (monitored && kbProps.appliesTo(botUserId))
                ? knowledgeBaseService.topKbSimilarity(userMsg, botUserId) : 0;
            AiReplyResult aiResult = aiCustomerService.reply(userId, botUserId, userMsg);
            if (!isCurrentGeneration(sessionKey, gen)) {
                log.info("[ai-cs] skip stale AI reply bot={} customer={} msgId={}",
                    botUserId, customerUserId, messageId);
                return;
            }

            if (!monitored) {
                deliver(botUserId, customerUserId, aiResult);
                return;
            }

            if (AiCsHandoffEvaluator.shouldHandoffWhileMonitored(aiCsProps, userMsg, aiResult, kbScore)) {
                aiCsHandoffService.transferToHuman(botUserId, customerUserId, REASON_AUTO, messageId);
                return;
            }

            waitWhileAdminTyping(botUserId, customerUserId, sessionKey, gen);
            if (!isCurrentGeneration(sessionKey, gen)) {
                return;
            }
            deliver(botUserId, customerUserId, aiResult);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ai-cs] reply wait interrupted bot={} customer={}", botUserId, customerUserId);
        } catch (Exception e) {
            log.warn("[ai-cs] reply orchestration failed bot={} customer={} err={}",
                botUserId, customerUserId, e.getMessage(), e);
            if (isCurrentGeneration(sessionKey, gen)) {
                botPrivateMessageService.sendText(botUserId, customerUserId,
                    "这边暂时没能生成回复，请再发一次。");
            }
        }
    }

    @Override
    public void reportAdminTyping(Long botUserId, Long customerUserId, Long operatorId) {
        if (handoff() == null || !handoff().isEnabled()) {
            return;
        }
        int ttl = Math.max(1, handoff().getAdminTypingTtlSeconds());
        typingBucket(botUserId, customerUserId)
            .set(String.valueOf(operatorId), ttl, TimeUnit.SECONDS);
        reportAdminWatching(botUserId, customerUserId, operatorId);
    }

    @Override
    public void reportAdminWatching(Long botUserId, Long customerUserId, Long operatorId) {
        if (handoff() == null || !handoff().isEnabled()) {
            return;
        }
        int ttl = Math.max(1, handoff().getAdminWatchTtlSeconds());
        botWatchBucket(botUserId).set(String.valueOf(operatorId), ttl, TimeUnit.SECONDS);
        if (customerUserId != null && customerUserId > 0) {
            sessionWatchBucket(botUserId, customerUserId)
                .set(String.valueOf(operatorId), ttl, TimeUnit.SECONDS);
        }
    }

    @Override
    public void cancelPendingAiReply(Long botUserId, Long customerUserId) {
        generationMap.computeIfAbsent(sessionKey(botUserId, customerUserId), k -> new AtomicLong(0))
            .incrementAndGet();
        typingBucket(botUserId, customerUserId).delete();
    }

    private void waitWhileAdminTyping(Long botUserId, Long customerUserId,
                                      String sessionKey, long gen) throws InterruptedException {
        AiCustomerServiceProperties.Handoff handoff = handoff();
        if (handoff == null || !handoff.isEnabled() || !isAdminMonitoring(botUserId, customerUserId)) {
            return;
        }
        if (!isAdminTyping(botUserId, customerUserId)) {
            return;
        }
        long maxWaitMs = Math.max(1L, handoff.getAdminReplyMaxWaitSeconds()) * 1000L;
        long quietMs = Math.max(0L, handoff.getAdminTypingQuietSeconds()) * 1000L;
        long deadline = System.currentTimeMillis() + maxWaitMs;
        log.info("[ai-cs] admin typing while monitoring, defer AI bot={} customer={}",
            botUserId, customerUserId);

        while (System.currentTimeMillis() < deadline) {
            if (!isCurrentGeneration(sessionKey, gen)) {
                return;
            }
            if (!isAdminTyping(botUserId, customerUserId)) {
                sleepQuiet(quietMs, sessionKey, gen);
                if (!isCurrentGeneration(sessionKey, gen)) {
                    return;
                }
                if (!isAdminTyping(botUserId, customerUserId)) {
                    return;
                }
            }
            Thread.sleep(200);
        }
    }

    private void sleepQuiet(long quietMs, String sessionKey, long gen) throws InterruptedException {
        long end = System.currentTimeMillis() + quietMs;
        while (System.currentTimeMillis() < end) {
            if (!isCurrentGeneration(sessionKey, gen)) {
                return;
            }
            if (isAdminTypingFromKey(sessionKey)) {
                return;
            }
            Thread.sleep(Math.min(150L, Math.max(1L, end - System.currentTimeMillis())));
        }
    }

    private boolean isAdminMonitoring(Long botUserId, Long customerUserId) {
        return botWatchBucket(botUserId).isExists()
            || sessionWatchBucket(botUserId, customerUserId).isExists();
    }

    private void deliver(Long botUserId, Long customerUserId, AiReplyResult aiResult) {
        if (aiResult == null) {
            log.info("[ai-cs] deliver empty result bot={} customer={}", botUserId, customerUserId);
            return;
        }
        int cardCount = aiResult.getApplyCards() == null ? 0 : aiResult.getApplyCards().size();
        boolean blank = StrUtil.isBlank(aiResult.getText());
        log.info("[ai-cs] deliver bot={} customer={} textEmpty={} cards={} text={}",
            botUserId, customerUserId, blank, cardCount, clip(aiResult.getText()));
        String replyText = aiResult.getText();
        if (StrUtil.isNotBlank(replyText)) {
            botPrivateMessageService.sendText(botUserId, customerUserId, replyText);
        }
        List<LuckyBonusApplyCardVO> cards = dedupeCards(aiResult.getApplyCards());
        for (LuckyBonusApplyCardVO card : cards) {
            deliverApplyCard(botUserId, customerUserId, card, replyText);
        }
        touchSession(botUserId, customerUserId);
    }

    /**
     * 正文已经说明活动时只补领取按钮。没有正文时才把卡片说明单独发成一条。
     * 已领取 / 纯展示类卡片不发按钮。
     */
    private void deliverApplyCard(Long botUserId, Long customerUserId, LuckyBonusApplyCardVO card,
                                  String replyText) {
        if (card == null) {
            return;
        }
        if ("available_activities".equals(card.getKind())) {
            return;
        }
        boolean showButton = !Boolean.FALSE.equals(card.getShowButton())
            && !Boolean.TRUE.equals(card.getClaimed())
            && StrUtil.isNotBlank(card.getButtonText());
        String desc = card.getText();
        // 正文已经讲过时，卡片说明不再单独发一条，避免同一轮把活动内容说两遍。
        if (StrUtil.isNotBlank(desc) && StrUtil.isBlank(replyText)) {
            botPrivateMessageService.sendText(botUserId, customerUserId, desc.trim());
        }
        if (!showButton) {
            return;
        }
        LuckyBonusApplyCardVO buttonOnly = new LuckyBonusApplyCardVO();
        buttonOnly.setKind(card.getKind());
        buttonOnly.setBonus(card.getBonus());
        buttonOnly.setTransactionId(card.getTransactionId());
        buttonOnly.setActivityId(card.getActivityId());
        buttonOnly.setBotUserId(card.getBotUserId());
        buttonOnly.setButtonText(card.getButtonText());
        buttonOnly.setStatDate(card.getStatDate());
        buttonOnly.setCurrentLevel(card.getCurrentLevel());
        buttonOnly.setShowButton(true);
        buttonOnly.setButtonOnly(true);
        buttonOnly.setText(null);
        botPrivateMessageService.sendLuckyBonusApplyCard(botUserId, customerUserId, buttonOnly);
    }

    private void touchSession(Long botUserId, Long customerUserId) {
        AiCsSession session = sessionMapper.selectOne(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiCsSession>lambdaQuery()
                .eq(AiCsSession::getBotUserId, botUserId)
                .eq(AiCsSession::getCustomerUserId, customerUserId)
                .last("limit 1"));
        if (session == null) {
            return;
        }
        Date now = new Date();
        session.setLastMsgAt(now);
        session.setUpdatedTime(now);
        sessionMapper.updateById(session);
    }

    private boolean isAdminTyping(Long botUserId, Long customerUserId) {
        return typingBucket(botUserId, customerUserId).isExists();
    }

    private boolean isAdminTypingFromKey(String sessionKey) {
        String[] parts = sessionKey.split(":");
        if (parts.length != 2) {
            return false;
        }
        return typingBucket(Long.parseLong(parts[0]), Long.parseLong(parts[1])).isExists();
    }

    private RBucket<String> typingBucket(Long botUserId, Long customerUserId) {
        return redissonClient.getBucket("ai-cs:typing:" + botUserId + ":" + customerUserId);
    }

    private RBucket<String> botWatchBucket(Long botUserId) {
        return redissonClient.getBucket("ai-cs:watch-bot:" + botUserId);
    }

    private RBucket<String> sessionWatchBucket(Long botUserId, Long customerUserId) {
        return redissonClient.getBucket("ai-cs:watch:" + botUserId + ":" + customerUserId);
    }

    private static String clip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "...";
    }

    private boolean alreadyReplied(Long messageId) {
        if (messageId == null || messageId <= 0) {
            return false;
        }
        try {
            return !redissonClient.getBucket("ai-cs:replied:" + messageId)
                .trySet("1", 10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[ai-cs] reply dedupe failed msgId={} err={}", messageId, e.getMessage());
            return false;
        }
    }

    private List<LuckyBonusApplyCardVO> dedupeCards(List<LuckyBonusApplyCardVO> cards) {
        List<LuckyBonusApplyCardVO> out = new ArrayList<>();
        if (cards == null || cards.isEmpty()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (LuckyBonusApplyCardVO card : cards) {
            if (card == null || "available_activities".equals(card.getKind())) {
                continue;
            }
            String key = String.valueOf(card.getKind()) + "|" + card.getTransactionId() + "|"
                + card.getActivityId() + "|" + card.getCurrentLevel() + "|" + card.getStatDate();
            if (!seen.add(key)) {
                continue;
            }
            out.add(card);
        }
        return out;
    }

    private boolean isCurrentGeneration(String sessionKey, long gen) {
        AtomicLong current = generationMap.get(sessionKey);
        return current != null && current.get() == gen;
    }

    private String sessionKey(Long botUserId, Long customerUserId) {
        return botUserId + ":" + customerUserId;
    }

    private boolean isFormalTakeover(Long botUserId, Long customerUserId) {
        if (handoff() == null || !handoff().isEnabled()) {
            return false;
        }
        AiCsSession session = sessionMapper.selectOne(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiCsSession>lambdaQuery()
                .eq(AiCsSession::getBotUserId, botUserId)
                .eq(AiCsSession::getCustomerUserId, customerUserId)
                .last("limit 1"));
        return session != null
            && Objects.equals(session.getMode(), AiCsSession.MODE_HUMAN)
            && session.getOperatorId() != null;
    }

    private AiCustomerServiceProperties.Handoff handoff() {
        return aiCsProps.getHandoff();
    }

    private boolean isAiCsBot(Long botUserId) {
        if (botUserId == null || !aiCsProps.isEnabled()) {
            return false;
        }
        Long primary = aiCsProps.getBotUserId();
        if (primary != null && primary > 0 && Objects.equals(primary, botUserId)) {
            return true;
        }
        List<Long> ids = aiCsProps.getBotUserIds();
        return ids != null && ids.stream().anyMatch(id -> id != null && Objects.equals(id, botUserId));
    }
}
