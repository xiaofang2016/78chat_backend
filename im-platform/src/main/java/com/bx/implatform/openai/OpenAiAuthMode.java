package com.bx.implatform.openai;

/**
 * OpenAI 鉴权：平台 API Key，或 Codex CLI 同款 ChatGPT OAuth（{@code ~/.codex/auth.json}）。
 */
public enum OpenAiAuthMode {
    API_KEY,
    CODEX_OAUTH;

    public static OpenAiAuthMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return API_KEY;
        }
        String n = raw.trim().toLowerCase().replace('_', '-');
        if ("codex-oauth".equals(n) || "codex".equals(n) || "chatgpt".equals(n)) {
            return CODEX_OAUTH;
        }
        return API_KEY;
    }
}
