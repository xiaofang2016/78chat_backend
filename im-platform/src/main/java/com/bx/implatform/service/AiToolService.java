package com.bx.implatform.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * AI 客服 function calling 工具集。
 *
 * 安全约定：
 *   1. 所有 tool 的 user_id 由 AI 客服 service 强制注入（取自当前对话发起方），
 *      tool 内部不接受 AI 通过 arguments 传入 user_id（防越权）。
 *   2. 所有 tool 仅做"只读"业务查询；写操作（转账、领奖等）一律不暴露给 AI。
 *   3. 单次 tool 调用失败仅抛业务异常或返回错误结构，不允许影响主对话流程。
 */
public interface AiToolService {

    /**
     * 返回 OpenAI Chat Completions tools 字段（数组）。
     */
    JSONArray getToolsSchema();

    /**
     * 执行一次工具调用。
     *
     * @param userId    当前对话发起方真实 userId（box-im im_user.id == aagames j_users.id）
     * @param name      工具名（function name）
     * @param arguments JSON 字符串形式的参数（来自 OpenAI tool_call.function.arguments）
     * @return tool 执行结果的 JSON 摘要（喂回 OpenAI 时作为 tool message content）
     */
    JSONObject invoke(Long userId, String name, String arguments);
}
