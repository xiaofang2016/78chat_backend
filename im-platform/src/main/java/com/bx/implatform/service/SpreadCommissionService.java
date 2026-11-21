package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 推广佣金与推广页数据，对接 aagames 用户域接口（spreadinfo / spreadstat / spread_commission_log 等）。
 */
public interface SpreadCommissionService {

    boolean matchesWalletKeywords(String userMessage);

    boolean matchesLogKeywords(String userMessage);

    boolean matchesSpreadInfoKeywords(String userMessage);

    boolean matchesSpreadStatKeywords(String userMessage);

    JSONObject fetchWalletStatus(Long userId);

    /** GET /spreadinfo：含 no_get_benefits、total_benefits、has_get_benefits、下级 list、spread_url */
    JSONObject fetchSpreadInfo(Long userId, Integer page, Integer pageSize, String startTime, String endTime);

    /** GET /spreadstat：周期推广统计、图表、friend_summary */
    JSONObject fetchSpreadStat(Long userId, String period, String startTime, String endTime);

    /** GET /spread_commission_log */
    JSONObject fetchCommissionLog(Long userId, String period, Integer page, Integer pageSize, Integer status);

}
