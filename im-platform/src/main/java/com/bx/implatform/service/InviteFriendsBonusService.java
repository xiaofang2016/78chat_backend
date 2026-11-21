package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 邀请好友送彩礼（活动 150137），对接 aagames {@code GET /external/user/{userId}/redpacket/quota}。
 */
public interface InviteFriendsBonusService {

    long DEFAULT_ACTIVITY_ID = 150137L;

    boolean matchesActivityKeywords(String userMessage);

    JSONObject fetchQuota(Long userId);

    JSONObject fetchQuota(Long userId, String date);
}
