package com.bx.implatform.client;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.SportAccountProperties;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.util.BoximOutboundSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 用说球帝已绑定的吉祥或 UED 令牌打开对应站点。浏览器拿不到签名密钥。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SportBoundLoginClient {

    private static final String PATH = "/prod-api/user/bound-login";

    private final SportAccountProperties props;

    private HttpClient httpClient;

    public String accessToken(String userId, String phone, String brand) {
        String secret = props.getBoundSecret() == null ? "" : props.getBoundSecret().trim();
        if (secret.isEmpty() || StrUtil.isBlank(props.getBoundUrl())) {
            log.warn("说球帝绑定登录未配置");
            throw new GlobalException(4001, "sport.portalUnavailable");
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = nonce();
        String signed = userId + "\n" + phone + "\n" + brand;
        String signature = BoximOutboundSigner.sign(
            BoximOutboundSigner.buildSignString(timestamp, nonce, "POST", PATH, signed), secret);
        JSONObject body = new JSONObject();
        body.put("userId", userId);
        body.put("phone", phone);
        body.put("brand", brand);
        body.put("timestamp", timestamp);
        body.put("nonce", nonce);
        body.put("signature", signature);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(props.getBoundUrl().trim()))
            .timeout(Duration.ofMillis(Math.max(props.getTimeoutMs(), 1000)))
            .header("Content-Type", "application/json")
            .header("User-Agent", "zhiquan-im")
            .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response;
        try {
            response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GlobalException(4001, "sport.unavailable");
        } catch (Exception ex) {
            log.warn("说球帝绑定登录失败: {}", ex.getClass().getSimpleName());
            throw new GlobalException(4001, "sport.unavailable");
        }
        return readToken(response.statusCode(), response.body(), brand);
    }

    private static String readToken(int status, String raw, String brand) {
        JSONObject json;
        try {
            json = JSON.parseObject(raw);
        } catch (Exception ex) {
            json = null;
        }
        if (json == null || status != 200 || !"0".equals(json.getString("code"))) {
            String msg = json == null ? "" : json.getString("msg");
            throw new GlobalException(4001, messageKey(brand, msg));
        }
        JSONObject data = json.getJSONObject("data");
        String token = data == null ? "" : StrUtil.blankToDefault(data.getString("accessToken"), "");
        if (token.isBlank()) {
            throw new GlobalException(4001, "sport.portalUnavailable");
        }
        return token;
    }

    private static String messageKey(String brand, String msg) {
        if ("请重新验证吉祥账号".equals(msg)) {
            return "sport.jxtReverify";
        }
        if ("请重新验证UED账号".equals(msg)) {
            return "sport.uedReverify";
        }
        if ("请先绑定吉祥账号".equals(msg)) {
            return "sport.bindJixiang";
        }
        if ("请先绑定UED账号".equals(msg)) {
            return "sport.bindUed";
        }
        return "sport.portalUnavailable";
    }

    private static String nonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(props.getTimeoutMs(), 1000)))
                .build();
        }
        return httpClient;
    }
}
