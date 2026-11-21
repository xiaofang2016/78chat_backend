package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 幸运电子/老虎机注单尾号加码（固定 activity_id=132499，对接 aagames eligibility；不传日期）。
 */
public interface LuckyBetSuffixService {

    /** 幸运电子活动、幸运老虎机活动 */
    long DEFAULT_ACTIVITY_ID = 132499L;

    boolean matchesActivityKeywords(String userMessage);

    JSONObject fetchEligibility(Long userId, Long activityId);

    JSONObject fetchEligibility(Long userId, Long activityId, String transactionId);
}
