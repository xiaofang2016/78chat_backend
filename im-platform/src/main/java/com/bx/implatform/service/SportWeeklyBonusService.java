package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 体育周周领，对接 aagames {@code GET /external/user/{userId}/weekly-sports-bonus/status}。
 */
public interface SportWeeklyBonusService {

    boolean matchesActivityKeywords(String userMessage);

    JSONObject fetchStatus(Long userId);

    JSONObject fetchStatus(Long userId, String weekStart);

}
