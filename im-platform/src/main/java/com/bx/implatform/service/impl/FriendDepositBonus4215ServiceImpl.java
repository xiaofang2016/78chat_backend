package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.service.FriendDepositBonus4215Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class FriendDepositBonus4215ServiceImpl implements FriendDepositBonus4215Service {

    private static final Pattern KEYWORDS = Pattern.compile(
        "好友存款|存款领赏金|存款送4215|4215|好友任务|赚钱.*好友|当周.*存款|好友.*存款.*赏金|积少成多");

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
        return businessClient.friendDepositBonusStatus(userId);
    }
}
