package com.bx.implatform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 客服一次回复的结果：自然语言 + 可选的独立交互卡片（与正文分开发送）。
 */
@Data
public class AiReplyResult {

    private String text;

    /** 可领取的活动卡片（每条单独发 LUCKY_BONUS_APPLY 消息） */
    private List<LuckyBonusApplyCardVO> applyCards = new ArrayList<>();

    /** @deprecated 使用 {@link #getApplyCards()} */
    @Deprecated
    public LuckyBonusApplyCardVO getLuckyBonusApplyCard() {
        return applyCards == null || applyCards.isEmpty() ? null : applyCards.get(0);
    }

    /** @deprecated 使用 {@link #addApplyCard(LuckyBonusApplyCardVO)} */
    @Deprecated
    public void setLuckyBonusApplyCard(LuckyBonusApplyCardVO card) {
        applyCards = card == null ? new ArrayList<>() : new ArrayList<>(List.of(card));
    }

    public void addApplyCard(LuckyBonusApplyCardVO card) {
        if (card == null) {
            return;
        }
        if (applyCards == null) {
            applyCards = new ArrayList<>();
        }
        applyCards.add(card);
    }

    public List<LuckyBonusApplyCardVO> getApplyCards() {
        return applyCards == null ? Collections.emptyList() : applyCards;
    }

    public static AiReplyResult textOnly(String text) {
        AiReplyResult r = new AiReplyResult();
        r.setText(text == null ? "" : text);
        return r;
    }
}
