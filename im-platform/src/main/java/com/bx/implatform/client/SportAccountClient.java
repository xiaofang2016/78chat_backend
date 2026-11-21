package com.bx.implatform.client;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.SportAccountProperties;
import com.bx.implatform.exception.GlobalException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 用说球帝登录令牌换账号资料。密码和令牌密钥留在说球帝，职圈只认核对结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SportAccountClient {

    private final SportAccountProperties props;

    private HttpClient httpClient;

    public SportProfile session(String token) {
        if (StrUtil.isBlank(props.getSessionUrl())) {
            throw new GlobalException(500, "说球帝登录未配置");
        }
        String body = JSON.toJSONString(new JSONObject().fluentPut("token", token == null ? "" : token.trim()));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(props.getSessionUrl().trim()))
            .timeout(Duration.ofMillis(Math.max(props.getTimeoutMs(), 1000)))
            .header("Content-Type", "application/json")
            .header("User-Agent", "zhiquan-im")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response;
        try {
            response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GlobalException(500, "sport.unavailable");
        } catch (Exception ex) {
            log.warn("说球帝登录核对失败: {}", ex.getClass().getSimpleName());
            throw new GlobalException(500, "sport.unavailable");
        }
        try {
            return SportProfile.parse(response.statusCode(), response.body(), props.getPublicBase());
        } catch (IllegalArgumentException ex) {
            throw new GlobalException(400, loginMessage(ex.getMessage()));
        }
    }

    /**
     * 把说球帝的固定文案换成职圈文案键，其它原因原样返回。
     *
     * @param message 说球帝或解析失败时的文案
     * @return 文案键或原文
     */
    private static String loginMessage(String message) {
        if ("登录已过期".equals(message)) {
            return "sport.loginExpired";
        }
        if (message == null || message.isBlank() || "请先登录".equals(message)) {
            return "sport.loginRequired";
        }
        return message;
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(props.getTimeoutMs(), 1000)))
                .build();
        }
        return httpClient;
    }

    @Getter
    public static final class SportProfile {
        private final String userId;
        private final String phone;
        private final String nickname;
        private final String avatar;

        SportProfile(String userId, String phone, String nickname, String avatar) {
            this.userId = userId;
            this.phone = phone;
            this.nickname = nickname;
            this.avatar = avatar;
        }

        static SportProfile parse(int status, String body, String publicBase) {
            JSONObject json;
            try {
                json = JSON.parseObject(body);
            } catch (Exception ex) {
                json = null;
            }
            if (json == null) {
                throw new IllegalArgumentException("请先登录");
            }
            if (status != 200 || !"0".equals(json.getString("code"))) {
                String msg = json.getString("msg");
                throw new IllegalArgumentException(StrUtil.blankToDefault(msg, "请先登录"));
            }
            JSONObject data = json.getJSONObject("data");
            if (data == null) {
                throw new IllegalArgumentException("请先登录");
            }
            String userId = StrUtil.blankToDefault(data.getString("userId"), "").trim();
            String phone = StrUtil.blankToDefault(data.getString("phone"), "").trim();
            if (!userId.matches("[a-f0-9]{32}") || !phone.matches("1[3-9]\\d{9}")) {
                throw new IllegalArgumentException("请先登录");
            }
            String nickname = StrUtil.blankToDefault(data.getString("nickname"), "").trim();
            if (nickname.isEmpty()) {
                nickname = phone.substring(0, 3) + "****" + phone.substring(7);
            }
            String avatar = StrUtil.blankToDefault(data.getString("avatar"), "").trim();
            if (avatar.startsWith("/")) {
                String base = StrUtil.blankToDefault(publicBase, "").trim();
                if (base.endsWith("/")) {
                    base = base.substring(0, base.length() - 1);
                }
                avatar = base + avatar;
            }
            return new SportProfile(userId, phone, nickname, avatar);
        }
    }
}
