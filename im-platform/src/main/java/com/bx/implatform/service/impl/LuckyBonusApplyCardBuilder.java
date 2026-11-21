package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.service.LuckyBetSuffixService;
import com.bx.implatform.vo.ai.LuckyBonusApplyCardVO;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * @deprecated 使用 {@link ActivityApplyCardBuilder}
 */
@Deprecated
public final class LuckyBonusApplyCardBuilder {

    private LuckyBonusApplyCardBuilder() {
    }

    public static LuckyBonusApplyCardVO fromToolResult(JSONObject toolResult, Long botUserId) {
        return ActivityApplyCardBuilder.fromLuckyBetToolResult(toolResult, botUserId);
    }

    public static LuckyBonusApplyCardVO fromMatch(JSONObject match, Long botUserId) {
        return ActivityApplyCardBuilder.fromLuckyBetMatch(match, botUserId);
    }
}
