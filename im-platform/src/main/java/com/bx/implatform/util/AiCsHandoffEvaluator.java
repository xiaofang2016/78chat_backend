package com.bx.implatform.util;

import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.vo.ai.AiReplyResult;

import java.util.regex.Pattern;

/**
 * 仅在管理员在线监控时评估是否转人工；无人监控时不触发。
 */
public final class AiCsHandoffEvaluator {

    private static final Pattern TRANSFER_KEYWORD = Pattern.compile(
        "转人工|人工客服|真人客服|找人工|接人工|要人工");

    private static final Pattern AI_REFUSAL = Pattern.compile(
        "无法查询|暂时无法|请稍后重试|请联系人工|不清楚|不知道|无法回答|没有相关|暂未找到");

    private AiCsHandoffEvaluator() {
    }

    public static boolean hasTransferKeyword(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return TRANSFER_KEYWORD.matcher(userMessage).find();
    }

    /**
     * 管理员正在监控该机器人会话时，判断是否应转人工（不发 AI 正文，改发固定话术）。
     */
    public static boolean shouldHandoffWhileMonitored(AiCustomerServiceProperties aiProps,
                                                      String userMessage,
                                                      AiReplyResult aiResult,
                                                      double kbTopScore) {
        if (aiProps == null || aiProps.getHandoff() == null || !aiProps.getHandoff().isEnabled()) {
            return false;
        }
        if (hasTransferKeyword(userMessage)) {
            return true;
        }
        if (aiResult == null) {
            return true;
        }
        var cards = aiResult.getApplyCards();
        if (cards != null && !cards.isEmpty()) {
            return false;
        }
        String text = aiResult.getText();
        if (text == null || text.isBlank()) {
            return true;
        }
        double minScore = aiProps.getHandoff().getKbMinScore();
        if (kbTopScore < minScore && AI_REFUSAL.matcher(text).find()) {
            return true;
        }
        return false;
    }
}
