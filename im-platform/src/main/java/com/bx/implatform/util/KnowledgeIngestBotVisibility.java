package com.bx.implatform.util;

import com.bx.implatform.config.props.AiCustomerServiceProperties;

import java.util.List;

/**
 * 「AI知识库」录入机器人：仅管理员在前端/API 列表中可见，其它用户检索不到。
 */
public final class KnowledgeIngestBotVisibility {

    private KnowledgeIngestBotVisibility() {
    }

    public static Long botUserId(AiCustomerServiceProperties aiCsProps) {
        if (aiCsProps == null || aiCsProps.getKnowledgeIngestBot() == null) {
            return null;
        }
        Long id = aiCsProps.getKnowledgeIngestBot().getUserId();
        return (id != null && id > 0) ? id : null;
    }

    public static boolean ingestBotConfigured(AiCustomerServiceProperties aiCsProps) {
        AiCustomerServiceProperties.KnowledgeIngestBot kb = knowledgeIngestBot(aiCsProps);
        return kb != null && kb.isEnabled() && botUserId(aiCsProps) != null;
    }

    /** 是否在会话列表等区域展示快捷入口（启用 + 管理员） */
    public static boolean showKbQuickEntryToViewer(AiCustomerServiceProperties aiCsProps, Long viewerUserId) {
        return ingestBotConfigured(aiCsProps) && isIngestAdmin(aiCsProps, viewerUserId);
    }

    /**
     * 对当前访问者而言，{@code targetUserId} 是否应对其隐藏知识库机器人（搜索/详情均不可见）。
     */
    public static boolean hideIngestBotFromViewer(AiCustomerServiceProperties aiCsProps, Long viewerUserId, Long targetUserId) {
        if (!ingestBotConfigured(aiCsProps) || targetUserId == null) {
            return false;
        }
        Long botId = botUserId(aiCsProps);
        if (botId == null || !sameLong(botId, targetUserId)) {
            return false;
        }
        return !isIngestAdmin(aiCsProps, viewerUserId);
    }

    public static boolean isIngestAdmin(AiCustomerServiceProperties aiCsProps, Long viewerUserId) {
        AiCustomerServiceProperties.KnowledgeIngestBot kb = knowledgeIngestBot(aiCsProps);
        if (kb == null || viewerUserId == null) {
            return false;
        }
        List<Long> admins = kb.getAdminUserIds();
        if (admins == null || admins.isEmpty()) {
            return false;
        }
        long vid = viewerUserId.longValue();
        return admins.stream().anyMatch(a -> a != null && a.longValue() == vid);
    }

    private static AiCustomerServiceProperties.KnowledgeIngestBot knowledgeIngestBot(AiCustomerServiceProperties aiCsProps) {
        return aiCsProps == null ? null : aiCsProps.getKnowledgeIngestBot();
    }

    private static boolean sameLong(Number a, Number b) {
        if (a == null || b == null) {
            return false;
        }
        return a.longValue() == b.longValue();
    }
}
