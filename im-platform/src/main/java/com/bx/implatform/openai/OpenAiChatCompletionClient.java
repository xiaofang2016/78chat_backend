package com.bx.implatform.openai;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.OpenAIProperties;
import com.bx.implatform.exception.GlobalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 统一 Chat Completions 调用：API Key（平台）或 Codex OAuth（ChatGPT 订阅后端）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiChatCompletionClient {

    private static final String DEFAULT_CODEX_BASE = "https://chatgpt.com/backend-api/codex";
    private static final String DEFAULT_CODEX_ORIGINATOR = "codex_cli_rs";
    /** ChatGPT 订阅 Codex 后端默认模型。gpt-5.5 已从 ChatGPT 账号下线，改用 gpt-6-luna。 */
    private static final String CODEX_CHATGPT_DEFAULT_MODEL = "gpt-6-luna";

    private final OpenAIProperties openAIProperties;
    private final RestTemplate openAiRestTemplate;
    private final CodexOAuthTokenService codexOAuthTokenService;

    public void ensureChatConfigured() {
        if (authMode() == OpenAiAuthMode.CODEX_OAUTH) {
            codexOAuthTokenService.ensureCodexAuthConfigured();
            return;
        }
        if (StrUtil.isBlank(openAIProperties.getApiKey())) {
            throw new GlobalException(500, "openai.api-key 未配置");
        }
    }

    /**
     * @return Chat Completions 形态的 JSON（含 {@code choices[0].message}）
     */
    public JSONObject chatCompletion(JSONArray messages, JSONArray tools, Double temperature, Integer maxTokens) {
        ensureChatConfigured();
        if (authMode() == OpenAiAuthMode.CODEX_OAUTH) {
            // ChatGPT Codex /responses 不接受 max_output_tokens（会 400）
            return codexResponsesStream(messages, tools);
        }
        return platformChatCompletions(messages, tools, temperature, maxTokens);
    }

    public OpenAiAuthMode authMode() {
        return OpenAiAuthMode.fromConfig(openAIProperties.getAuthMode());
    }

    private JSONObject platformChatCompletions(JSONArray messages, JSONArray tools, Double temperature,
                                               Integer maxTokens) {
        String baseUrl = normalizeBase(openAIProperties.getBaseUrl());
        String url = baseUrl + "/v1/chat/completions";

        JSONObject body = new JSONObject(new LinkedHashMap<>());
        body.put("model", openAIProperties.getModel());
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(openAIProperties.getApiKey().trim());
        return exchangeJson(url, headers, body, "openai");
    }

    /**
     * ChatGPT Codex 后端要求 {@code POST /responses} + {@code stream:true} + {@code store:false}；
     * {@code /responses/compact} 已下线（404）。
     */
    private JSONObject codexResponsesStream(JSONArray messages, JSONArray tools) {
        CodexOAuthTokenService.CodexSession session = codexOAuthTokenService.currentSession();
        String base = normalizeBase(
            StrUtil.blankToDefault(openAIProperties.getCodexBaseUrl(), DEFAULT_CODEX_BASE));
        String url = base + "/responses";

        CodexRequestParts parts = buildCodexRequestParts(messages);
        String configuredModel = StrUtil.blankToDefault(openAIProperties.getCodexModel(), openAIProperties.getModel());
        String model = resolveCodexModel(configuredModel);
        if (!model.equals(configuredModel == null ? "" : configuredModel.trim())) {
            log.info("[openai.codex] remapped model {} -> {}", configuredModel, model);
        }
        JSONObject body = assembleCodexRequestBody(model, parts, tools,
            openAIProperties.getCodexReasoningEffort());

        HttpHeaders headers = jsonHeaders();
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        headers.setBearerAuth(session.accessToken());
        if (StrUtil.isNotBlank(session.accountId())) {
            headers.set("ChatGPT-Account-ID", session.accountId());
        }
        headers.set("originator",
            StrUtil.blankToDefault(openAIProperties.getCodexOriginator(), DEFAULT_CODEX_ORIGINATOR));
        headers.set("OpenAI-Beta", "responses=experimental");

        byte[] payload = JSON.toJSONString(body).getBytes(StandardCharsets.UTF_8);
        RequestCallback requestCallback = request -> {
            request.getHeaders().putAll(headers);
            request.getBody().write(payload);
        };
        ResponseExtractor<JSONObject> extractor = response -> parseCodexSseResponse(response, url);
        try {
            JSONObject raw = openAiRestTemplate.execute(url, HttpMethod.POST, requestCallback, extractor);
            JSONObject shaped = toChatCompletionsShape(raw);
            warnIfEmptyAssistant(shaped);
            return shaped;
        } catch (RestClientException e) {
            log.warn("[openai.codex] request failed: {}", e.getMessage(), e);
            throw new GlobalException(500, "AI客服暂时不可用");
        }
    }

    private JSONObject parseCodexSseResponse(ClientHttpResponse response, String url) {
        try {
            if (!response.getStatusCode().is2xxSuccessful()) {
                String errBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("[openai.codex] non-2xx: status={}, url={}, body={}", response.getStatusCode(), url,
                    errBody.length() > 500 ? errBody.substring(0, 500) : errBody);
                throw new GlobalException(500, "AI客服暂时不可用");
            }
            StringBuilder textDelta = new StringBuilder();
            JSONObject completedResponse = null;
            JSONArray outputItems = new JSONArray();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JSONObject evt;
                    try {
                        evt = JSON.parseObject(data);
                    } catch (Exception parseEx) {
                        continue;
                    }
                    if (evt == null) {
                        continue;
                    }
                    String type = evt.getString("type");
                    if ("response.output_text.delta".equals(type)) {
                        String delta = evt.getString("delta");
                        if (StrUtil.isNotBlank(delta)) {
                            textDelta.append(delta);
                        }
                    } else if ("response.completed".equals(type) || "response.done".equals(type)) {
                        JSONObject resp = evt.getJSONObject("response");
                        completedResponse = resp != null ? resp : evt;
                    } else if ("response.output_item.done".equals(type)) {
                        JSONObject item = evt.getJSONObject("item");
                        if (item != null) {
                            outputItems.add(item);
                        }
                    }
                }
            }
            if (completedResponse != null) {
                mergeOutputItems(completedResponse, outputItems);
                return completedResponse;
            }
            JSONObject synthetic = new JSONObject(new LinkedHashMap<>());
            if (!outputItems.isEmpty()) {
                synthetic.put("output", outputItems);
            } else if (!textDelta.isEmpty()) {
                JSONObject msg = new JSONObject(new LinkedHashMap<>());
                msg.put("type", "message");
                msg.put("role", "assistant");
                JSONArray parts = new JSONArray();
                JSONObject part = new JSONObject(new LinkedHashMap<>());
                part.put("type", "output_text");
                part.put("text", textDelta.toString());
                parts.add(part);
                msg.put("content", parts);
                JSONArray output = new JSONArray();
                output.add(msg);
                synthetic.put("output", output);
            }
            return synthetic;
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[openai.codex] SSE parse failed: {}", e.getMessage(), e);
            throw new GlobalException(500, "AI客服暂时不可用");
        }
    }

    private JSONObject exchangeJson(String url, HttpHeaders headers, JSONObject body, String logTag) {
        HttpEntity<String> req = new HttpEntity<>(JSON.toJSONString(body), headers);
        try {
            ResponseEntity<String> resp = openAiRestTemplate.exchange(url, HttpMethod.POST, req, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("[{}] non-2xx: status={}, body={}", logTag, resp.getStatusCode(), resp.getBody());
                throw new GlobalException(500, "AI客服暂时不可用");
            }
            return JSON.parseObject(resp.getBody());
        } catch (RestClientException e) {
            log.warn("[{}] request failed: {}", logTag, e.getMessage(), e);
            throw new GlobalException(500, "AI客服暂时不可用");
        }
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private static String normalizeBase(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    record CodexRequestParts(String instructions, JSONArray input) {
    }

    static CodexRequestParts buildCodexRequestParts(JSONArray messages) {
        StringBuilder instructions = new StringBuilder();
        JSONArray input = new JSONArray();
        if (messages == null) {
            return new CodexRequestParts("", input);
        }
        for (int i = 0; i < messages.size(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            if (msg == null) {
                continue;
            }
            String role = msg.getString("role");
            if ("system".equals(role)) {
                appendLine(instructions, msg.getString("content"));
                continue;
            }
            if ("tool".equals(role)) {
                JSONObject item = new JSONObject(new LinkedHashMap<>());
                item.put("type", "function_call_output");
                item.put("call_id", msg.getString("tool_call_id"));
                item.put("output", msg.getString("content"));
                input.add(item);
                continue;
            }
            if ("assistant".equals(role)) {
                JSONArray toolCalls = msg.getJSONArray("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (int t = 0; t < toolCalls.size(); t++) {
                        JSONObject tc = toolCalls.getJSONObject(t);
                        if (tc == null) {
                            continue;
                        }
                        JSONObject fn = tc.getJSONObject("function");
                        JSONObject call = new JSONObject(new LinkedHashMap<>());
                        call.put("type", "function_call");
                        String callId = tc.getString("id");
                        if (callId == null) {
                            callId = tc.getString("call_id");
                        }
                        call.put("call_id", callId);
                        if (fn != null) {
                            call.put("name", fn.getString("name"));
                            call.put("arguments", normalizeFunctionArguments(fn.get("arguments")));
                        } else {
                            call.put("arguments", "{}");
                        }
                        input.add(call);
                    }
                }
                String content = msg.getString("content");
                if (StrUtil.isNotBlank(content)) {
                    input.add(assistantMessageItem(content));
                }
                continue;
            }
            if ("user".equals(role)) {
                input.add(userMessageItem(msg.getString("content")));
            }
        }
        return new CodexRequestParts(instructions.toString().trim(), input);
    }

    private static JSONObject userMessageItem(String content) {
        JSONObject item = new JSONObject(new LinkedHashMap<>());
        item.put("type", "message");
        item.put("role", "user");
        item.put("content", codexTextContentParts(content, false));
        return item;
    }

    private static JSONObject assistantMessageItem(String content) {
        JSONObject item = new JSONObject(new LinkedHashMap<>());
        item.put("type", "message");
        item.put("role", "assistant");
        item.put("content", codexTextContentParts(content, true));
        return item;
    }

    private static JSONArray codexTextContentParts(String content, boolean assistantRole) {
        JSONArray parts = new JSONArray();
        JSONObject part = new JSONObject(new LinkedHashMap<>());
        part.put("type", assistantRole ? "output_text" : "input_text");
        part.put("text", content == null ? "" : content);
        parts.add(part);
        return parts;
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(line.trim());
    }

    /**
     * 将 Codex Responses 响应规范化为 Chat Completions，便于现有解析逻辑复用。
     */
    static JSONObject toChatCompletionsShape(JSONObject raw) {
        if (raw == null) {
            return new JSONObject();
        }
        JSONArray choices = raw.getJSONArray("choices");
        if (choices != null && !choices.isEmpty()) {
            return raw;
        }
        JSONObject assistant = parseAssistantFromCodexOutput(raw);
        JSONObject choice = new JSONObject(new LinkedHashMap<>());
        choice.put("message", assistant);
        JSONArray outChoices = new JSONArray();
        outChoices.add(choice);
        JSONObject wrapped = new JSONObject(new LinkedHashMap<>());
        wrapped.put("choices", outChoices);
        return wrapped;
    }

    private static JSONObject parseAssistantFromCodexOutput(JSONObject raw) {
        JSONObject assistant = new JSONObject(new LinkedHashMap<>());
        assistant.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        JSONArray toolCalls = new JSONArray();

        JSONArray output = raw.getJSONArray("output");
        if (output != null) {
            collectFromOutputArray(output, text, toolCalls);
        }
        JSONObject nested = raw.getJSONObject("response");
        if (nested != null && nested.getJSONArray("output") != null) {
            collectFromOutputArray(nested.getJSONArray("output"), text, toolCalls);
        }

        if (text.isEmpty() && raw.getString("output_text") != null) {
            text.append(raw.getString("output_text"));
        }

        if (!text.isEmpty()) {
            assistant.put("content", collapseDuplicatedReply(text.toString()));
        }
        if (!toolCalls.isEmpty()) {
            assistant.put("tool_calls", toolCalls);
        }
        return assistant;
    }

    private static void collectFromOutputArray(JSONArray output, StringBuilder text, JSONArray toolCalls) {
        for (int i = 0; i < output.size(); i++) {
            JSONObject item = output.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String type = item.getString("type");
            if ("message".equals(type)) {
                appendMessageText(item, text);
            } else if ("function_call".equals(type)) {
                toolCalls.add(toChatToolCall(item));
            } else if ("output_text".equals(type) && item.getString("text") != null) {
                appendLine(text, item.getString("text"));
            }
        }
    }

    private static void appendMessageText(JSONObject messageItem, StringBuilder text) {
        Object content = messageItem.get("content");
        if (content instanceof String s) {
            appendLine(text, s);
            return;
        }
        if (content instanceof JSONArray parts) {
            for (int j = 0; j < parts.size(); j++) {
                JSONObject part = parts.getJSONObject(j);
                if (part == null) {
                    continue;
                }
                if ("output_text".equals(part.getString("type")) || "text".equals(part.getString("type"))
                    || "input_text".equals(part.getString("type"))) {
                    appendLine(text, part.getString("text"));
                } else if (part.getString("text") != null) {
                    appendLine(text, part.getString("text"));
                }
            }
        }
    }

    private static JSONObject toChatToolCall(JSONObject functionCall) {
        JSONObject tc = new JSONObject(new LinkedHashMap<>());
        String callId = functionCall.getString("call_id");
        if (callId == null) {
            callId = functionCall.getString("id");
        }
        tc.put("id", callId);
        tc.put("type", "function");
        JSONObject fn = new JSONObject(new LinkedHashMap<>());
        fn.put("name", functionCall.getString("name"));
        fn.put("arguments", normalizeFunctionArguments(functionCall.get("arguments")));
        tc.put("function", fn);
        return tc;
    }

    private static String normalizeFunctionArguments(Object arguments) {
        if (arguments == null) {
            return "{}";
        }
        if (arguments instanceof String s) {
            return s.isBlank() ? "{}" : s;
        }
        return JSON.toJSONString(arguments);
    }

    private static void mergeOutputItems(JSONObject completedResponse, JSONArray outputItems) {
        if (outputItems == null || outputItems.isEmpty()) {
            return;
        }
        JSONArray output = completedResponse.getJSONArray("output");
        if (output == null || output.isEmpty()) {
            completedResponse.put("output", outputItems);
            return;
        }
        for (int i = 0; i < outputItems.size(); i++) {
            JSONObject item = outputItems.getJSONObject(i);
            if (item == null) {
                continue;
            }
            if (!outputContainsItem(output, item)) {
                output.add(item);
            }
        }
    }

    private static boolean outputContainsItem(JSONArray output, JSONObject candidate) {
        String type = candidate.getString("type");
        String callId = candidate.getString("call_id");
        String candidateId = candidate.getString("id");
        for (int i = 0; i < output.size(); i++) {
            JSONObject existing = output.getJSONObject(i);
            if (existing == null) {
                continue;
            }
            if (!Objects.equals(type, existing.getString("type"))) {
                continue;
            }
            if ("function_call".equals(type) && callId != null
                && callId.equals(existing.getString("call_id"))) {
                return true;
            }
            if (candidateId != null && candidateId.equals(existing.getString("id"))) {
                return true;
            }
            if ("message".equals(type)) {
                String candidateText = messagePlainText(candidate);
                String existingText = messagePlainText(existing);
                if (!candidateText.isEmpty() && candidateText.equals(existingText)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * response.completed 的 output 里已经有正文时，output_item.done 会再带一条相同 message。
     * 拼进同一条气泡后，用户看到同一句话连说两遍。
     */
    static String collapseDuplicatedReply(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        int from = 0;
        while (from < trimmed.length()) {
            int nl = trimmed.indexOf('\n', from);
            if (nl < 0) {
                break;
            }
            String left = trimmed.substring(0, nl).trim();
            String right = trimmed.substring(nl + 1).trim();
            if (!left.isEmpty() && left.equals(right)) {
                return left;
            }
            from = nl + 1;
        }
        return trimmed;
    }

    private static String messagePlainText(JSONObject messageItem) {
        if (messageItem == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        appendMessageText(messageItem, text);
        return collapseDuplicatedReply(text.toString());
    }

    private void warnIfEmptyAssistant(JSONObject shaped) {
        JSONArray choices = shaped.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            log.warn("[openai.codex] empty choices after SSE parse");
            return;
        }
        JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
        if (msg == null) {
            log.warn("[openai.codex] missing assistant message after SSE parse");
            return;
        }
        boolean hasText = StrUtil.isNotBlank(msg.getString("content"));
        JSONArray tcs = msg.getJSONArray("tool_calls");
        boolean hasTools = tcs != null && !tcs.isEmpty();
        if (!hasText && !hasTools) {
            log.warn("[openai.codex] assistant message has no content and no tool_calls");
        }
    }

    /**
     * 组装 Codex {@code POST /responses} 请求体（与线上 im-platform 一致，供测试校验）。
     */
    static JSONObject assembleCodexRequestBody(String model, CodexRequestParts parts, JSONArray chatCompletionTools) {
        return assembleCodexRequestBody(model, parts, chatCompletionTools, "low");
    }

    static JSONObject assembleCodexRequestBody(String model, CodexRequestParts parts, JSONArray chatCompletionTools,
                                               String reasoningEffort) {
        JSONObject body = new JSONObject(new LinkedHashMap<>());
        body.put("model", model);
        body.put("instructions", parts.instructions());
        body.put("input", parts.input());
        body.put("store", false);
        body.put("stream", true);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            JSONObject reasoning = new JSONObject(new LinkedHashMap<>());
            reasoning.put("effort", reasoningEffort.trim());
            body.put("reasoning", reasoning);
        }
        if (chatCompletionTools != null && !chatCompletionTools.isEmpty()) {
            body.put("tools", toResponsesTools(chatCompletionTools));
        }
        return body;
    }

    /**
     * Chat Completions 工具为 {@code {type,function:{name,...}}}；Responses/Codex 要求 {@code name} 在顶层。
     */
    static JSONArray toResponsesTools(JSONArray chatCompletionTools) {
        JSONArray out = new JSONArray();
        if (chatCompletionTools == null) {
            return out;
        }
        for (int i = 0; i < chatCompletionTools.size(); i++) {
            JSONObject tool = chatCompletionTools.getJSONObject(i);
            if (tool == null) {
                continue;
            }
            JSONObject fn = tool.getJSONObject("function");
            if (fn != null && fn.getString("name") != null) {
                JSONObject flat = new JSONObject(new LinkedHashMap<>());
                flat.put("type", "function");
                flat.put("name", fn.getString("name"));
                if (fn.getString("description") != null) {
                    flat.put("description", fn.getString("description"));
                }
                flat.put("parameters", normalizeToolParameters(fn.get("parameters")));
                flat.put("strict", false);
                out.add(flat);
                continue;
            }
            if (tool.getString("name") != null) {
                out.add(tool);
            }
        }
        return out;
    }

    /**
     * ChatGPT 账号的 Codex 不接受平台 API 的 gpt-4.x，也不再接受已下线的 gpt-5.5。
     */
    private static JSONObject normalizeToolParameters(Object parameters) {
        if (parameters instanceof JSONObject obj && !obj.isEmpty()) {
            return obj;
        }
        JSONObject p = new JSONObject(new LinkedHashMap<>());
        p.put("type", "object");
        p.put("properties", new JSONObject());
        p.put("required", new JSONArray());
        return p;
    }

    static String resolveCodexModel(String configured) {
        if (StrUtil.isBlank(configured)) {
            return CODEX_CHATGPT_DEFAULT_MODEL;
        }
        String m = configured.trim();
        if (m.endsWith("-codex") || "gpt-5.5".equals(m) || "gpt-5.4".equals(m) || "gpt-5.4-mini".equals(m)) {
            return CODEX_CHATGPT_DEFAULT_MODEL;
        }
        if (m.startsWith("gpt-4") || m.startsWith("o3") || m.startsWith("o4")) {
            return CODEX_CHATGPT_DEFAULT_MODEL;
        }
        return m;
    }
}
