package com.bx.implatform.service;

/**
 * AI 客服回复编排：默认发送 AI 文案；管理员正在输入时暂缓，管理员未回复则再发 AI。
 */
public interface AiCsReplyOrchestrator {

    void handleCustomerMessage(Long botUserId, Long customerUserId, Long userId, Long messageId, String userMsg);

    void reportAdminTyping(Long botUserId, Long customerUserId, Long operatorId);

    void reportAdminWatching(Long botUserId, Long customerUserId, Long operatorId);

    /** 管理员人工发送前调用，取消尚未发出的 AI 回复 */
    void cancelPendingAiReply(Long botUserId, Long customerUserId);
}
