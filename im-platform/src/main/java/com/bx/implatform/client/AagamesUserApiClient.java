package com.bx.implatform.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.AagamesProperties;
import com.bx.implatform.exception.GlobalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用 aagames_api 用户域路由（routes/user_api.php）。
 * 鉴权方式：把 box-im 缓存的"用户自己的 aagames access_token"作为 Bearer 转发。
 *
 * 与 {@link AagamesBusinessClient}（boxim 路由 + HMAC）不同，本类直接复用 aagames 现有的"前端用户接口"，
 * 不需要 aagames 端新增任何 internal 端点。
 *
 * 仅供 AI 客服 function calling 使用：调用方必须保证 userId == 当前对话发起方，工具实现层不接受 AI 传 user_id。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AagamesUserApiClient {

    private final AagamesProperties aagamesProps;
    private final AagamesUserTokenResolver userTokenResolver;
    private final AagamesMerchantSupport merchantSupport;

    private volatile HttpClient httpClient;

    private HttpClient httpClient() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                c = httpClient;
                if (c == null) {
                    int timeoutMs = aagamesProps.getRequestTimeoutMs() > 0
                        ? aagamesProps.getRequestTimeoutMs() : 5000;
                    c = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeoutMs))
                        .build();
                    httpClient = c;
                }
            }
        }
        return c;
    }

    /** 游戏 user_api 仅接受游戏 access_token（IM token 过期时从共用 Redis 回退）。 */
    private String requireUserToken(Long userId) {
        return userTokenResolver.resolveForUserApi(userId);
    }

    private String userBaseUrl() {
        // 先读 user-base-url，缺省回退 base-url
        String url = aagamesProps.getUserBaseUrl();
        if (url == null || url.isBlank()) {
            url = aagamesProps.getBaseUrl();
        }
        if (url != null && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * GET 调 user_api.php 接口。
     *
     * <p>aagames 用户域接口的鉴权风格：access_token 走 <strong>query 参数</strong>（非 Bearer header），
     * 同时强制要求 merchant_no/lang/language 三个 query 字段。
     */
    public JSONObject getJson(Long userId, String path, Map<String, String> query, String scenarioTag) {
        String token = requireUserToken(userId);
        String base = userBaseUrl();
        // 合并业务 query + 鉴权 query（access_token 放 query；自动附加 merchant_no 与语言）
        Map<String, String> mergedQuery = new LinkedHashMap<>();
        if (query != null) {
            mergedQuery.putAll(query);
        }
        mergedQuery.put("access_token", token);
        mergedQuery.put("merchant_no", merchantSupport.requireForUser(userId));
        mergedQuery.putIfAbsent("lang", "zh");
        mergedQuery.putIfAbsent("language", "zh");

        String pathWithQuery = buildPath(path, mergedQuery);
        // 日志里把 access_token 脱敏（防止落库泄露）
        String pathForLog = pathWithQuery.replaceAll("(access_token=)[^&]*", "$1***");
        String url = base + pathWithQuery;
        long started = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs() > 0
                    ? aagamesProps.getRequestTimeoutMs() : 5000))
                .header("Accept", "application/json")
                .header("User-Agent", "box-im/ai-cs")
                .GET()
                .build();
            HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            String body = resp.body() == null ? "" : resp.body();
            long cost = System.currentTimeMillis() - started;
            if (status == 401 || status == 403) {
                log.warn("[ai-cs.user-api] {} {} {}: {}ms status={} (token expired)", scenarioTag, "GET", pathForLog, cost, status);
                throw new GlobalException(410, "user.externalLoginExpired");
            }
            if (status == 404) {
                log.warn("[ai-cs.user-api] {} {} {}: {}ms status=404 (route missing?)", scenarioTag, "GET", pathForLog, cost);
                throw new GlobalException(404, "aagames route not found: " + path);
            }
            if (status >= 500) {
                log.warn("[ai-cs.user-api] {} {} {}: {}ms status={} body={}", scenarioTag, "GET", pathForLog, cost, status, truncate(body));
                throw new GlobalException(500, "aagames upstream error");
            }
            JSONObject json;
            try {
                json = JSON.parseObject(body);
            } catch (Exception parseErr) {
                log.warn("[ai-cs.user-api] {} {} {}: response not json, body={}", scenarioTag, "GET", pathForLog, truncate(body));
                throw new GlobalException(500, "aagames response not json");
            }
            // aagames 标准业务体：{ code: 0, msg: 'ok'|'success', data: {...} }；code != 0 视为业务失败
            Integer code = json.getInteger("code");
            if (code != null && code != 0 && code != 200) {
                String msg = json.getString("msg");
                log.info("[ai-cs.user-api] {} {} {}: business fail code={} msg={}", scenarioTag, "GET", pathForLog, code, msg);
                JSONObject err = new JSONObject();
                err.put("code", code);
                err.put("msg", msg == null ? "fail" : msg);
                return err; // 业务失败也返回，由 AI 自行解释（避免抛异常打断对话）
            }
            JSONObject data = json.getJSONObject("data");
            if (data == null) {
                // 某些接口 data 可能是数组或裸值；统一包一下
                JSONObject wrapper = new JSONObject();
                wrapper.put("data", json.get("data"));
                return wrapper;
            }
            log.info("[ai-cs.user-api] {} {} {}: {}ms ok", scenarioTag, "GET", pathForLog, cost);
            return data;
        } catch (GlobalException ge) {
            throw ge;
        } catch (Exception e) {
            log.warn("[ai-cs.user-api] {} {} {} failed: {}", scenarioTag, "GET", pathForLog, e.getMessage(), e);
            throw new GlobalException(500, "aagames request failed");
        }
    }

    private String buildPath(String path, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path);
        sb.append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
