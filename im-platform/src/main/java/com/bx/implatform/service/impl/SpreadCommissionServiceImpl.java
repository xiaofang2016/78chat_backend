package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.client.AagamesUserApiClient;
import com.bx.implatform.service.SpreadCommissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SpreadCommissionServiceImpl implements SpreadCommissionService {

    private static final Pattern WALLET_KEYWORDS = Pattern.compile(
        "能领多少佣金|可领.*佣金|待领佣金|未领取佣金|佣金有多少|推广佣金.*领|领多少佣金|我的佣金|佣金余额|佣金钱包");

    private static final Pattern LOG_KEYWORDS = Pattern.compile(
        "佣金记录|佣金明细|佣金列表|佣金历史|哪笔佣金|下级.*佣金|spread_commission");

    private static final Pattern SPREAD_INFO_KEYWORDS = Pattern.compile(
        "spreadinfo|推广信息|推广链接|推广码|spread_url|total_benefits|no_get_benefits|累计佣金|未领佣金");

    private static final Pattern SPREAD_STAT_KEYWORDS = Pattern.compile(
        "spreadstat|推广统计|推广数据趋势|好友汇总|有效投注趋势|本周推广|本月推广|推广图表");

    private final AagamesBusinessClient businessClient;
    private final AagamesUserApiClient userApiClient;

    @Override
    public boolean matchesWalletKeywords(String userMessage) {
        return matches(SPREAD_INFO_KEYWORDS, userMessage) || matches(WALLET_KEYWORDS, userMessage);
    }

    @Override
    public boolean matchesLogKeywords(String userMessage) {
        return matches(LOG_KEYWORDS, userMessage);
    }

    @Override
    public boolean matchesSpreadInfoKeywords(String userMessage) {
        return matches(SPREAD_INFO_KEYWORDS, userMessage) || matches(WALLET_KEYWORDS, userMessage);
    }

    @Override
    public boolean matchesSpreadStatKeywords(String userMessage) {
        return matches(SPREAD_STAT_KEYWORDS, userMessage);
    }

    @Override
    public JSONObject fetchWalletStatus(Long userId) {
        return businessClient.spreadCommissionStatus(userId);
    }

    @Override
    public JSONObject fetchSpreadInfo(Long userId, Integer page, Integer pageSize, String startTime, String endTime) {
        Map<String, String> q = new LinkedHashMap<>();
        if (page != null && page > 0) {
            q.put("page", String.valueOf(page));
        }
        if (pageSize != null && pageSize > 0) {
            q.put("pagesize", String.valueOf(pageSize));
        }
        putIfNonBlank(q, "start_time", startTime);
        putIfNonBlank(q, "end_time", endTime);
        return userApiClient.getJson(userId, "/spreadinfo", q.isEmpty() ? null : q, "get_spread_info");
    }

    @Override
    public JSONObject fetchSpreadStat(Long userId, String period, String startTime, String endTime) {
        Map<String, String> q = new LinkedHashMap<>();
        if (period != null && !period.isBlank()) {
            q.put("period", period.trim());
        }
        putIfNonBlank(q, "start_time", startTime);
        putIfNonBlank(q, "end_time", endTime);
        return userApiClient.getJson(userId, "/spreadstat", q.isEmpty() ? null : q, "get_spread_stat");
    }

    @Override
    public JSONObject fetchCommissionLog(Long userId, String period, Integer page, Integer pageSize, Integer status) {
        Map<String, String> q = new LinkedHashMap<>();
        if (period != null && !period.isBlank()) {
            q.put("period", period.trim());
        }
        if (page != null && page > 0) {
            q.put("page", String.valueOf(page));
        }
        if (pageSize != null && pageSize > 0) {
            q.put("pagesize", String.valueOf(pageSize));
        }
        if (status != null && status > 0) {
            q.put("status", String.valueOf(status));
        }
        return userApiClient.getJson(userId, "/spread_commission_log", q.isEmpty() ? null : q, "get_spread_commission_log");
    }

    private static boolean matches(Pattern pattern, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return pattern.matcher(userMessage).find();
    }

    private static void putIfNonBlank(Map<String, String> q, String key, String value) {
        if (value != null && !value.isBlank()) {
            q.put(key, value.trim());
        }
    }
}
