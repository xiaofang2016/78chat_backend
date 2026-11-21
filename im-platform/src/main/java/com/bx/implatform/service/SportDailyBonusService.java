package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 日投注闯关，对接 aagames {@code GET /external/user/{userId}/daily-sports-bonus/status}。
 */
public interface SportDailyBonusService {

    boolean matchesActivityKeywords(String userMessage);

    JSONObject fetchStatus(Long userId);

    JSONObject fetchStatus(Long userId, String statDate);
}
