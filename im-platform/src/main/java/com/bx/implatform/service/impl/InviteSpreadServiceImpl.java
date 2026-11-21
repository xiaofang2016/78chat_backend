package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.service.InviteSpreadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class InviteSpreadServiceImpl implements InviteSpreadService {

    private static final Pattern KEYWORDS = Pattern.compile(
        "推广数据|下级好友|邀请列表|好友详情|推广统计|注册人数|有效推广|有效投注|邀请了多少|"
            + "我邀请了|多少个好友|有多少.*好友|哪些.*好友|好友.*投注|下级.*充值|推广记录|邀请好友");

    private final AagamesBusinessClient businessClient;

    @Override
    public boolean matchesActivityKeywords(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return KEYWORDS.matcher(userMessage).find();
    }

    @Override
    public JSONObject fetchSummary(Long userId) {
        return businessClient.inviteSpread(userId);
    }
}
