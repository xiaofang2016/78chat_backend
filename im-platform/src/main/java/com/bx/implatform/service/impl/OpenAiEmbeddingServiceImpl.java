package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.OpenAIProperties;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.openai.OpenAiAuthMode;
import com.bx.implatform.service.OpenAiEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiEmbeddingServiceImpl implements OpenAiEmbeddingService {

    /** 单次请求批量上限（避免 URL/Body 过大；可调） */
    private static final int BATCH_SIZE = 48;

    private final RestTemplate openAiRestTemplate;
    private final OpenAIProperties openAIProperties;

    @Override
    public float[] embedText(String text) {
        List<float[]> batch = embedTexts(List.of(text));
        return batch.isEmpty() ? new float[0] : batch.get(0);
    }

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (openAIProperties.getApiKey() == null || openAIProperties.getApiKey().isBlank()) {
            if (OpenAiAuthMode.fromConfig(openAIProperties.getAuthMode()) == OpenAiAuthMode.CODEX_OAUTH) {
                throw new GlobalException(500,
                    "Embedding 需要 openai.api-key（Codex OAuth 不支持 /v1/embeddings），请配置 api-key 或关闭知识库");
            }
            throw new GlobalException(500, "openai.api-key 未配置");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, texts.size());
            List<String> slice = texts.subList(from, to);
            out.addAll(callEmbeddingsApi(slice));
        }
        return out;
    }

    private List<float[]> callEmbeddingsApi(List<String> inputs) {
        JSONArray inputArr = new JSONArray();
        for (String s : inputs) {
            String t = normalize(s);
            inputArr.add(t);
        }

        String baseUrl = openAIProperties.getBaseUrl();
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl + "/v1/embeddings";

        JSONObject body = new JSONObject(new LinkedHashMap<>());
        body.put("model", openAIProperties.getEmbeddingModel());
        body.put("input", inputArr);
        body.put("encoding_format", "float");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(openAIProperties.getApiKey().trim());

        HttpEntity<String> req = new HttpEntity<>(JSON.toJSONString(body), headers);
        try {
            ResponseEntity<String> resp = openAiRestTemplate.exchange(url, HttpMethod.POST, req, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("[openai.embeddings] non-2xx: status={}, body={}", resp.getStatusCode(), resp.getBody());
                throw new GlobalException(500, "Embedding 请求失败");
            }
            return parseEmbeddingResponse(resp.getBody(), inputs.size());
        } catch (RestClientException e) {
            log.warn("[openai.embeddings] request failed: {}", e.getMessage(), e);
            throw new GlobalException(500, "Embedding 请求失败");
        }
    }

    private static List<float[]> parseEmbeddingResponse(String jsonBody, int expectedSize) {
        JSONObject root = JSON.parseObject(jsonBody);
        JSONArray data = root.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new GlobalException(500, "Embedding 响应无数据");
        }
        // API 按 index 排序；仍按 index 放入数组
        float[][] slots = new float[expectedSize][];
        for (int i = 0; i < data.size(); i++) {
            JSONObject row = data.getJSONObject(i);
            int idx = row.getIntValue("index");
            JSONArray emb = row.getJSONArray("embedding");
            if (emb == null || emb.isEmpty()) {
                continue;
            }
            float[] vec = new float[emb.size()];
            for (int j = 0; j < emb.size(); j++) {
                vec[j] = emb.getFloatValue(j);
            }
            if (idx >= 0 && idx < expectedSize) {
                slots[idx] = vec;
            }
        }
        List<float[]> list = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            if (slots[i] == null) {
                throw new GlobalException(500, "Embedding 响应不完整");
            }
            list.add(slots[i]);
        }
        return list;
    }

    /** 官方示例：将换行替换为空格，减少噪音 token */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
}
