package com.bx.implatform.service;

import com.alibaba.fastjson.JSONObject;

/**
 * 汇总当前用户可查询/可参与的营销活动（供 AI 回答「有哪些活动可以参加」）。
 */
public interface AvailableActivitiesService {

    boolean matchesListKeywords(String userMessage);

    JSONObject fetchOverview(Long userId);

    /** 将 fetchOverview 结果格式化为可注入 system prompt 的文本块 */
    String formatOverviewForPrompt(JSONObject overview);

    /** 将 fetchOverview 结果格式化为用户可见的活动总览文案（不依赖模型生成） */
    String formatOverviewForUserReply(JSONObject overview);

}
