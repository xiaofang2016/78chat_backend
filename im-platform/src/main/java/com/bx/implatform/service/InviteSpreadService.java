package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 邀请好友推广数据，对接 aagames {@code GET /external/user/{userId}/invite-spread}。
 */
public interface InviteSpreadService {

    boolean matchesActivityKeywords(String userMessage);

    JSONObject fetchSummary(Long userId);

}
