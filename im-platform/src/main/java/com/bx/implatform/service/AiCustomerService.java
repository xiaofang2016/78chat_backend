package com.bx.implatform.service;

import com.bx.implatform.vo.ai.AiReplyResult;

public interface AiCustomerService {

    /**
     * 生成客服回复。若有可领取的活动，{@link AiReplyResult#getApplyCards()} 非空，
     * 调用方应在文字消息之后单独下发交互卡片，勿合并进 AI 正文。
     */
    AiReplyResult reply(Long userId, Long botUserId, String userMessage);
}

