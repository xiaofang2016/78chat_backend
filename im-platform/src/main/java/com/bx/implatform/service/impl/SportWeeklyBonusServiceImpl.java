package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.service.SportWeeklyBonusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SportWeeklyBonusServiceImpl implements SportWeeklyBonusService {

    private static final Pattern KEYWORDS = Pattern.compile(
        "体育周周领|周周领|体育周.*领|最高领588|588.*体育|weekly_sports_bonus|sport_weekly");

    private final AagamesBusinessClient businessClient;

    @Override
    public boolean matchesActivityKeywords(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return KEYWORDS.matcher(userMessage).find();
    }

    @Override
    public JSONObject fetchStatus(Long userId) {
        return fetchStatus(userId, null);
    }

    @Override
    public JSONObject fetchStatus(Long userId, String weekStart) {
        return businessClient.weeklySportsBonusStatus(userId, weekStart);
    }
}
