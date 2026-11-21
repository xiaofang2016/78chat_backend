package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.service.LotteryGift132467Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LotteryGift132467ServiceImpl implements LotteryGift132467Service {

    private static final Pattern KEYWORDS = Pattern.compile(
        "排列五|送彩票|132467|充值送彩票|幸运排列五|一夜暴富|彩票活动|体彩.*排列");

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
        return businessClient.lotteryGiftStatus(userId);
    }

    @Override
    public JSONObject claim(Long userId, String orderTradeNo) {
        return businessClient.lotteryGiftClaim(userId, orderTradeNo);
    }
}
