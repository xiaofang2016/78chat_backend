package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 佣金加倍 999 奖上奖（活动 143755），对接 aagames {@code GET /external/user/{userId}/commission-bonus/status}。
 */
public interface CommissionBonus999Service {

    boolean matchesActivityKeywords(String userMessage);

    JSONObject fetchStatus(Long userId);

}
