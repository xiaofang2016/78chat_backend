package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.service.SportDailyBonusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SportDailyBonusServiceImpl implements SportDailyBonusService {

    private static final Pattern KEYWORDS = Pattern.compile(
        "日投注闯关|体育每日任务|每日任务.*体育|最高领58|58.*体育|daily_sports_bonus|sport_daily");

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
    public JSONObject fetchStatus(Long userId, String statDate) {
        return businessClient.dailySportsBonusStatus(userId, statDate);
    }
}
