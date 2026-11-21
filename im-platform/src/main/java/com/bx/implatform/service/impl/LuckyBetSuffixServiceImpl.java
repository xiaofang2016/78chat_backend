package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesUserApiClient;
import com.bx.implatform.service.LuckyBetSuffixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LuckyBetSuffixServiceImpl implements LuckyBetSuffixService {

    private static final Pattern KEYWORDS = Pattern.compile("幸运电子活动|幸运老虎机活动");

    private final AagamesUserApiClient userApiClient;

    @Override
    public boolean matchesActivityKeywords(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return KEYWORDS.matcher(userMessage).find();
    }

    @Override
    public JSONObject fetchEligibility(Long userId, Long activityId) {
        return fetchEligibility(userId, activityId, null);
    }

    @Override
    public JSONObject fetchEligibility(Long userId, Long activityId, String transactionId) {
        // 「幸运电子活动」「幸运老虎机活动」固定活动 132499；不传 date_from/date_to
        Map<String, String> q = new LinkedHashMap<>();
        q.put("activity_id", String.valueOf(DEFAULT_ACTIVITY_ID));
        if (transactionId != null && !transactionId.isBlank()) {
            q.put("transaction_id", transactionId.trim());
        }
        JSONObject data = userApiClient.getJson(userId,
            "/activity/lucky-bet-suffix/eligibility", q, "lucky_bet_eligibility");
        if (data.containsKey("code") && data.getInteger("code") != null && data.getInteger("code") != 0) {
            return data;
        }
        return data;
    }

    /**
     * 从 eligibility 响应里取 applicable=1 的最佳注单；无则 null。
     */
    public static JSONObject pickBestApplicable(JSONObject eligibility) {
        List<JSONObject> all = listApplicable(eligibility);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 全部可领取注单（优先 claimable_matches，与 aagames 接口一致）。
     */
    public static List<JSONObject> listApplicable(JSONObject eligibility) {
        List<JSONObject> out = new ArrayList<>();
        if (eligibility == null) {
            return out;
        }
        JSONArray claimable = eligibility.getJSONArray("claimable_matches");
        if (claimable != null && !claimable.isEmpty()) {
            for (int i = 0; i < claimable.size(); i++) {
                JSONObject m = claimable.getJSONObject(i);
                if (m != null && Integer.valueOf(1).equals(m.getInteger("applicable"))) {
                    out.add(m);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        JSONArray matches = eligibility.getJSONArray("matches");
        if (matches == null) {
            return out;
        }
        for (int i = 0; i < matches.size(); i++) {
            JSONObject m = matches.getJSONObject(i);
            if (m != null && Integer.valueOf(1).equals(m.getInteger("applicable"))) {
                out.add(m);
            }
        }
        return out;
    }
}
