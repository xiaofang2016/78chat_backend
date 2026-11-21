package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.service.CommissionBonus999Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CommissionBonus999ServiceImpl implements CommissionBonus999Service {

    private static final Pattern KEYWORDS = Pattern.compile(
        "佣金加倍|999奖上奖|奖上奖|躺着赚佣金|佣金加码|推广佣金.*奖|累计佣金|佣金.*999|每周任务.*佣金");

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
        return businessClient.commissionBonusStatus(userId);
    }
}
