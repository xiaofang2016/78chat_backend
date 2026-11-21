package com.bx.implatform.openai;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenAiChatCompletionClientCodexModelTest {

    @Test
    void mapsRetiredCodexModelsToLuna() {
        assertEquals("gpt-6-luna", OpenAiChatCompletionClient.resolveCodexModel("gpt-4.1-mini"));
        assertEquals("gpt-6-luna", OpenAiChatCompletionClient.resolveCodexModel("gpt-5.5"));
        assertEquals("gpt-6-luna", OpenAiChatCompletionClient.resolveCodexModel("gpt-5.3-codex"));
    }

    @Test
    void passesThroughGpt6Luna() {
        assertEquals("gpt-6-luna", OpenAiChatCompletionClient.resolveCodexModel("gpt-6-luna"));
    }

    @Test
    void passesThroughGpt55() {
        assertEquals("gpt-5.6-sol", OpenAiChatCompletionClient.resolveCodexModel("gpt-5.6-sol"));
    }

    @Test
    void flattensChatCompletionsToolsForResponsesApi() {
        JSONObject fn = new JSONObject();
        fn.put("name", "get_balance");
        fn.put("description", "balance");
        fn.put("parameters", new JSONObject());
        JSONObject wrapped = new JSONObject();
        wrapped.put("type", "function");
        wrapped.put("function", fn);
        JSONArray in = new JSONArray();
        in.add(wrapped);

        JSONArray out = OpenAiChatCompletionClient.toResponsesTools(in);
        assertEquals(1, out.size());
        JSONObject t0 = out.getJSONObject(0);
        assertEquals("function", t0.getString("type"));
        assertEquals("get_balance", t0.getString("name"));
        assertNull(t0.getJSONObject("function"));
    }

    @Test
    void buildCodexInput_usesOutputTextForAssistantReplay() {
        JSONArray messages = new JSONArray();
        JSONObject assistant = new JSONObject();
        assistant.put("role", "assistant");
        assistant.put("content", "已查到活动列表");
        messages.add(assistant);

        OpenAiChatCompletionClient.CodexRequestParts parts =
            OpenAiChatCompletionClient.buildCodexRequestParts(messages);
        JSONObject item = parts.input().getJSONObject(0);
        assertEquals("message", item.getString("type"));
        assertEquals("assistant", item.getString("role"));
        assertEquals("output_text", item.getJSONArray("content").getJSONObject(0).getString("type"));
    }

    @Test
    void buildCodexInput_replaysFunctionCallWithStringArguments() {
        JSONArray messages = new JSONArray();
        JSONObject fn = new JSONObject();
        fn.put("name", "get_balance");
        fn.put("arguments", new JSONObject());
        JSONObject tc = new JSONObject();
        tc.put("id", "call_abc");
        tc.put("type", "function");
        tc.put("function", fn);
        JSONArray toolCalls = new JSONArray();
        toolCalls.add(tc);
        JSONObject assistant = new JSONObject();
        assistant.put("role", "assistant");
        assistant.put("tool_calls", toolCalls);
        messages.add(assistant);

        OpenAiChatCompletionClient.CodexRequestParts parts =
            OpenAiChatCompletionClient.buildCodexRequestParts(messages);
        JSONObject call = parts.input().getJSONObject(0);
        assertEquals("function_call", call.getString("type"));
        assertEquals("call_abc", call.getString("call_id"));
        assertEquals("get_balance", call.getString("name"));
        assertEquals("{}", call.getString("arguments"));
    }

    @Test
    void codexRequestBody_omitsUnsupportedCodexFields() {
        JSONArray messages = new JSONArray();
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "有什么活动");
        messages.add(user);
        OpenAiChatCompletionClient.CodexRequestParts parts =
            OpenAiChatCompletionClient.buildCodexRequestParts(messages);

        JSONObject fn = new JSONObject();
        fn.put("name", "get_available_activities");
        fn.put("description", "活动列表");
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", new JSONObject());
        fn.put("parameters", params);
        JSONObject wrapped = new JSONObject();
        wrapped.put("type", "function");
        wrapped.put("function", fn);
        JSONArray tools = new JSONArray();
        tools.add(wrapped);

        JSONObject body = OpenAiChatCompletionClient.assembleCodexRequestBody("gpt-6-luna", parts, tools);
        assertEquals("gpt-6-luna", body.getString("model"));
        assertEquals(Boolean.FALSE, body.getBoolean("store"));
        assertEquals(Boolean.TRUE, body.getBoolean("stream"));
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"));
        assertNull(body.get("max_output_tokens"));
        assertNull(body.get("tool_choice"));
        assertNull(body.get("temperature"));
        JSONObject t0 = body.getJSONArray("tools").getJSONObject(0);
        assertEquals("get_available_activities", t0.getString("name"));
        assertNull(t0.getJSONObject("function"));
        JSONObject userMsg = body.getJSONArray("input").getJSONObject(0);
        assertEquals("input_text", userMsg.getJSONArray("content").getJSONObject(0).getString("type"));
    }

    @Test
    void dropsRepeatedAssistantParagraph() {
        String greeting = "在的，吉祥体育官方客服为您服务。请问需要查询活动还是账户信息？";
        JSONObject first = assistantMessage(greeting);
        JSONObject second = assistantMessage(greeting);
        JSONArray output = new JSONArray();
        output.add(first);
        output.add(second);
        JSONObject raw = new JSONObject();
        raw.put("output", output);

        JSONObject shaped = OpenAiChatCompletionClient.toChatCompletionsShape(raw);
        String content = shaped.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content");
        assertEquals(greeting, content);
    }

    private static JSONObject assistantMessage(String text) {
        JSONObject part = new JSONObject();
        part.put("type", "output_text");
        part.put("text", text);
        JSONArray parts = new JSONArray();
        parts.add(part);
        JSONObject message = new JSONObject();
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("content", parts);
        return message;
    }
}
