package com.bx.implatform.config;

import com.bx.implatform.config.props.OpenAIProperties;
import com.bx.implatform.openai.OpenAiSecretValue;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * 启动时从 Secrets Manager 读取客服用的 OpenAI key，并改走 api-key。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiApiKeyLoader {

    private final OpenAIProperties properties;

    @PostConstruct
    public void load() {
        String secretId = properties.getApiKeySecretId();
        if (secretId == null || secretId.isBlank()) {
            return;
        }
        String region = properties.getApiKeySecretRegion();
        if (region == null || region.isBlank()) {
            region = "ap-northeast-1";
        }
        secretId = secretId.trim();
        region = region.trim();
        try (SecretsManagerClient client = SecretsManagerClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build()) {
            String secret = client.getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretId)
                .build()).secretString();
            properties.setApiKey(OpenAiSecretValue.parse(secret));
            properties.setAuthMode("api-key");
            log.info("[openai] using api key from Secrets Manager id={} region={}", secretId, region);
        } catch (RuntimeException e) {
            throw new IllegalStateException("读取 OpenAI 密钥失败: " + secretId, e);
        }
    }
}
