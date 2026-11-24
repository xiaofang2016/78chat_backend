package com.bx.implatform.openai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * Secrets Manager {@code prod/openAIKey} 的 SecretString。
 * 线上是 JSON，密钥在 {@code openAI-KEY}。也接受裸的 key 字符串。
 */
public final class OpenAiSecretValue {

    private static final String[] JSON_FIELDS = {
        "openAI-KEY", "openAIKey", "apiKey", "api_key", "OPENAI_API_KEY"
    };

    private OpenAiSecretValue() {
    }

    public static String parse(String secretString) {
        if (secretString == null || secretString.isBlank()) {
            throw new IllegalArgumentException("openai secret 是空的");
        }
        String trimmed = secretString.trim();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        JSONObject obj = JSON.parseObject(trimmed);
        if (obj == null) {
            throw new IllegalArgumentException("openai secret 不是合法 JSON");
        }
        for (String field : JSON_FIELDS) {
            String value = obj.getString(field);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("openai secret 缺少 openAI-KEY");
    }
}
