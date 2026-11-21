package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.AiKnowledgeBaseProperties;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.service.KnowledgeEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Service
public class LocalKnowledgeEmbeddingService implements KnowledgeEmbeddingService {

    static final int DIMENSIONS = 384;
    private static final int MAX_CHARS = 12000;

    private final AiKnowledgeBaseProperties kbProps;
    private final RestTemplate restTemplate;

    public LocalKnowledgeEmbeddingService(AiKnowledgeBaseProperties kbProps) {
        this.kbProps = kbProps;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(20000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(text, "query");
    }

    @Override
    public float[] embedPassage(String text) {
        return embed(text, "passage");
    }

    @Override
    public List<float[]> embedPassages(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embedPassage(text));
        }
        return out;
    }

    private float[] embed(String text, String mode) {
        String normalized = normalize(text);
        if (normalized.isEmpty() || normalized.length() > MAX_CHARS) {
            throw new GlobalException(400, "知识库向量文本长度无效");
        }
        JSONObject body = new JSONObject(new LinkedHashMap<>());
        body.put("mode", mode);
        body.put("text", normalized);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<String> req = new HttpEntity<>(JSON.toJSONString(body), headers);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(endpoint(), HttpMethod.POST, req, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("[ai-kb.embed] non-2xx status={}", resp.getStatusCode());
                throw new GlobalException(500, "Embedding 请求失败");
            }
            return parseVector(resp.getBody());
        } catch (RestClientException e) {
            log.warn("[ai-kb.embed] request failed: {}", e.getMessage());
            throw new GlobalException(500, "Embedding 请求失败");
        }
    }

    private String endpoint() {
        String base = kbProps.getEmbeddingUrl();
        if (base == null || base.isBlank()) {
            base = "http://127.0.0.1:8791";
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/embed";
    }

    static float[] parseVector(String jsonBody) {
        JSONArray arr = JSON.parseArray(jsonBody);
        if (arr == null || arr.size() != DIMENSIONS) {
            throw new GlobalException(500, "Embedding 响应不完整");
        }
        float[] vec = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            vec[i] = arr.getFloatValue(i);
        }
        return vec;
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
}
