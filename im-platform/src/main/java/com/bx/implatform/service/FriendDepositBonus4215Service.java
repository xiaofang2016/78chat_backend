package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 好友存款领赏金 4215（活动 143523），对接 aagames {@code GET /external/user/{userId}/friend-deposit-bonus/status}。
 */
public interface FriendDepositBonus4215Service {

    boolean matchesActivityKeywords(String userMessage);

    JSONObject fetchStatus(Long userId);

}
