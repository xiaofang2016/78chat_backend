package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesUserApiClient;
import com.bx.implatform.service.DailyGoodLuckGoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DailyGoodLuckGoldServiceImpl implements DailyGoodLuckGoldService {

    private final AagamesUserApiClient userApiClient;

    @Override
    public JSONObject fetchStatus(Long userId) {
        return fetchStatus(userId, null);
    }

    @Override
    public JSONObject fetchStatus(Long userId, String statDate) {
        Map<String, String> q = new LinkedHashMap<>();
        if (statDate != null && !statDate.isBlank()) {
            q.put("stat_date", statDate.trim());
        }
        return userApiClient.getJson(userId,
            "/activity/daily-good-luck-gold/status", q.isEmpty() ? null : q, "daily_good_luck_gold_status");
    }
}
