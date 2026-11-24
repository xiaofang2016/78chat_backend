package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "openai")
public class OpenAIProperties {

    /**
     * 鉴权方式：{@code api-key}（平台 API Key）或 {@code codex-oauth}（Codex CLI {@code auth.json}）。
     */
    private String authMode = "api-key";

    /**
     * OpenAI API Key.
     * 建议通过环境变量 OPENAI_API_KEY 注入，避免写死到仓库。
     * 生产客服不读这项，启动时用 {@link #apiKeySecretId} 覆盖。
     */
    private String apiKey;

    /**
     * Secrets Manager 密钥名。值是 JSON，字段 {@code openAI-KEY}。
     * 读到之后客服对话和知识库向量都走 api-key，不再用 Codex。
     * 设成空字符串则沿用 {@link #authMode}。
     */
    private String apiKeySecretId = "prod/openAIKey";

    /** {@link #apiKeySecretId} 所在区域。 */
    private String apiKeySecretRegion = "ap-northeast-1";

    /** Codex OAuth 凭证文件，默认 {@code ~/.codex/auth.json}（由 {@code codex login} 生成） */
    private String codexAuthFile;

    /** ChatGPT Codex 后端，如 {@code https://chatgpt.com/backend-api/codex} */
    private String codexBaseUrl = "https://chatgpt.com/backend-api/codex";

    /** OAuth refresh 地址 */
    private String codexTokenUrl = "https://auth.openai.com/oauth/token";

    /** Codex 公共 OAuth client_id（与 Codex CLI 一致） */
    private String codexClientId = "app_EMoamEEZ73f0CkXaXp7hrann";

    /** Codex 请求 originator 头 */
    private String codexOriginator = "codex_cli_rs";

    /** Codex 对话模型；未设时使用 {@link #model} */
    private String codexModel;

    /**
     * Codex 思考档。客服要短答，默认 low，避免每句都按中高档先想再写。
     * 可设 minimal、low、medium、high。空字符串则不传该字段。
     */
    private String codexReasoningEffort = "low";

    /** e.g. https://api.openai.com */
    private String baseUrl = "https://api.openai.com";

    /** e.g. gpt-4o-mini */
    private String model = "gpt-4o-mini";

    /**
     * Embedding 模型（参见 OpenAI Embeddings 文档），默认 {@code text-embedding-3-small}（1536 维）。
     */
    private String embeddingModel = "text-embedding-3-small";

    /** HTTP 超时（毫秒） */
    private int timeoutMs = 15000;
}

