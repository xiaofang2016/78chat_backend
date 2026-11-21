package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.service.InviteFriendsBonusService;
import com.bx.implatform.vo.external.UserDailyQuotaVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class InviteFriendsBonusServiceImpl implements InviteFriendsBonusService {

    private static final Pattern KEYWORDS = Pattern.compile(
        "邀请好友|推广好友|送彩礼|拉新|邀请.*礼|好友.*推广|邀请好友送彩礼|成功推广|有效推广|推广了.{0,6}好友");

    private final AagamesBusinessClient businessClient;

    @Override
    public boolean matchesActivityKeywords(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return KEYWORDS.matcher(userMessage).find();
    }

    @Override
    public JSONObject fetchQuota(Long userId) {
        return fetchQuota(userId, null);
    }

    @Override
    public JSONObject fetchQuota(Long userId, String date) {
        UserDailyQuotaVO vo = businessClient.quota(userId, date);
        return vo == null ? null : vo.getRawPayload();
    }
}
