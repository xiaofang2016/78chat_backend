package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 体育流水汇总，对接 aagames {@code GET /external/user/{userId}/sport-turnover/summary}。
 * 与体育活动（周周领 / 日闯关）共用 SportBetScope 统计口径。
 */
public interface SportTurnoverService {

    JSONObject fetchSummary(Long userId);

    JSONObject fetchSummary(Long userId, String startDate, String endDate);
}
