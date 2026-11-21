package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.service.SportWinStreakService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SportWinStreakServiceImpl implements SportWinStreakService {

    private static final Pattern KEYWORDS = Pattern.compile(
        "体育连红挑战|连红挑战|体育连红|连红|最高领88|88.*连红|sport_win_streak|win_streak");

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
        return businessClient.sportWinStreakStatus(userId);
    }

    @Override
    public JSONObject applyReview(Long userId) {
        return businessClient.sportWinStreakApply(userId);
    }
}
