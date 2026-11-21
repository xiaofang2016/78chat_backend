package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 天天好运金（昨日电子/捕鱼/棋牌/德州负盈利关卡），对接 aagames {@code /activity/daily-good-luck-gold/status}。
 */
public interface DailyGoodLuckGoldService {

    JSONObject fetchStatus(Long userId);

    JSONObject fetchStatus(Long userId, String statDate);
}
