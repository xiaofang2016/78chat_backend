package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 幸运排列五送彩票（活动 132467）：充值选活动 → IM claim 送 dc_lotto 额度并进游戏选号。
 */
public interface LotteryGift132467Service {

    boolean matchesActivityKeywords(String userMessage);

    JSONObject fetchStatus(Long userId);

    JSONObject claim(Long userId, String orderTradeNo);
}
