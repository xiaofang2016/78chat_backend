package com.bx.implatform.openai;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 读取并刷新 Codex CLI {@code auth.json}（ChatGPT OAuth），与 {@code codex login} 共用凭证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodexOAuthTokenService {

    private static final String DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String DEFAULT_TOKEN_URL = "https://auth.openai.com/oauth/token";
    private static final int REFRESH_SKEW_SECONDS = 30;

    private final OpenAIProperties openAIProperties;
    private final RestTemplate openAiRestTemplate;
    private final ReentrantLock fileLock = new ReentrantLock();

    public record CodexSession(String accessToken, String accountId) {
    }

    public CodexSession currentSession() {
        JSONObject auth = loadAuthJson();
        JSONObject tokens = requireTokens(auth);
        String access = tokens.getString("access_token");
        if (StrUtil.isBlank(access)) {
            throw new GlobalException(500, "openai.codex-auth 缺少 access_token，请执行 codex login");
        }
        if (shouldRefresh(access)) {
            tokens = refreshAndPersist(auth, tokens);
            access = tokens.getString("access_token");
        }
        String accountId = resolveAccountId(tokens, access);
        return new CodexSession(access.trim(), accountId);
    }

    public void ensureCodexAuthConfigured() {
        Path path = resolveAuthFile();
        if (!Files.isRegularFile(path)) {
            throw new GlobalException(500,
                "openai.codex-auth-file 不存在: " + path + "，请先执行 codex login 并部署 auth.json");
        }
        currentSession();
    }

    private JSONObject refreshAndPersist(JSONObject auth, JSONObject tokens) {
        fileLock.lock();
        try {
            JSONObject latest = loadAuthJson();
            JSONObject latestTokens = requireTokens(latest);
            String access = latestTokens.getString("access_token");
            if (!shouldRefresh(access)) {
                return latestTokens;
            }
            String refreshToken = latestTokens.getString("refresh_token");
            if (StrUtil.isBlank(refreshToken)) {
                throw new GlobalException(500, "openai.codex-auth 缺少 refresh_token，请重新 codex login");
            }
            JSONObject refreshed = callRefresh(refreshToken);
            if (StrUtil.isNotBlank(refreshed.getString("access_token"))) {
                latestTokens.put("access_token", refreshed.getString("access_token"));
            }
            if (StrUtil.isNotBlank(refreshed.getString("id_token"))) {
                latestTokens.put("id_token", refreshed.getString("id_token"));
            }
            if (StrUtil.isNotBlank(refreshed.getString("refresh_token"))) {
                latestTokens.put("refresh_token", refreshed.getString("refresh_token"));
            }
            latest.put("tokens", latestTokens);
            latest.put("auth_mode", "chatgpt");
            latest.put("last_refresh", Instant.now().toString());
            writeAuthJson(latest);
            log.info("[openai.codex] access_token refreshed, auth_file={}", resolveAuthFile());
            return latestTokens;
        } finally {
            fileLock.unlock();
        }
    }

    private JSONObject callRefresh(String refreshToken) {
        String url = StrUtil.blankToDefault(openAIProperties.getCodexTokenUrl(), DEFAULT_TOKEN_URL);
        String clientId = StrUtil.blankToDefault(openAIProperties.getCodexClientId(), DEFAULT_CLIENT_ID);

        JSONObject body = new JSONObject(new LinkedHashMap<>());
        body.put("client_id", clientId);
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<String> req = new HttpEntity<>(JSON.toJSONString(body), headers);
        try {
            ResponseEntity<String> resp = openAiRestTemplate.exchange(url, HttpMethod.POST, req, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("[openai.codex] refresh non-2xx: status={}, body={}", resp.getStatusCode(), resp.getBody());
                throw new GlobalException(500, "Codex OAuth 刷新失败，请重新 codex login");
            }
            return JSON.parseObject(resp.getBody());
        } catch (RestClientException e) {
            log.warn("[openai.codex] refresh request failed: {}", e.getMessage());
            throw new GlobalException(500, "Codex OAuth 刷新失败: " + e.getMessage());
        }
    }

    private JSONObject loadAuthJson() {
        Path path = resolveAuthFile();
        if (!Files.isRegularFile(path)) {
            throw new GlobalException(500, "openai.codex-auth-file 不存在: " + path);
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JSONObject root = JSON.parseObject(text);
            if (root == null) {
                throw new GlobalException(500, "openai.codex-auth-file 格式无效");
            }
            return root;
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            throw new GlobalException(500, "读取 openai.codex-auth-file 失败: " + e.getMessage());
        }
    }

    private void writeAuthJson(JSONObject auth) {
        Path path = resolveAuthFile();
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.toJSONString(auth, true), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new GlobalException(500, "写入 openai.codex-auth-file 失败: " + e.getMessage());
        }
    }

    private static JSONObject requireTokens(JSONObject auth) {
        JSONObject tokens = auth.getJSONObject("tokens");
        if (tokens == null) {
            throw new GlobalException(500, "openai.codex-auth 缺少 tokens 节点");
        }
        return tokens;
    }

    private Path resolveAuthFile() {
        String configured = openAIProperties.getCodexAuthFile();
        if (StrUtil.isNotBlank(configured)) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        String home = System.getProperty("user.home");
        if (StrUtil.isBlank(home)) {
            throw new GlobalException(500, "无法解析 user.home，请配置 openai.codex-auth-file");
        }
        return Path.of(home, ".codex", "auth.json");
    }

    private static boolean shouldRefresh(String accessToken) {
        try {
            DecodedJWT jwt = JWT.decode(accessToken);
            Date exp = jwt.getExpiresAt();
            if (exp == null) {
                return false;
            }
            long now = System.currentTimeMillis() / 1000;
            long expSec = exp.getTime() / 1000;
            return now >= (expSec - REFRESH_SKEW_SECONDS);
        } catch (Exception e) {
            return true;
        }
    }

    private static String resolveAccountId(JSONObject tokens, String accessToken) {
        String fromFile = tokens.getString("account_id");
        if (StrUtil.isNotBlank(fromFile)) {
            return fromFile.trim();
        }
        try {
            DecodedJWT jwt = JWT.decode(accessToken);
            String claim = jwt.getClaim("chatgpt_account_id").asString();
            if (StrUtil.isNotBlank(claim)) {
                return claim.trim();
            }
        } catch (Exception ignored) {
            // optional header
        }
        return null;
    }
}
