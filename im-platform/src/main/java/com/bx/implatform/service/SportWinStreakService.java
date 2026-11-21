package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 体育连红挑战，对接 aagames {@code GET /external/user/{userId}/sport-win-streak/status}。
 */
public interface SportWinStreakService {

    boolean matchesActivityKeywords(String userMessage);

    JSONObject fetchStatus(Long userId);

    JSONObject applyReview(Long userId);
}
