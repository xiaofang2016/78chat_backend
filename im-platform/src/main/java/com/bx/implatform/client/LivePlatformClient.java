package com.bx.implatform.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.LiveProperties;
import com.bx.implatform.contant.RedisKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调用 live-api（主播陌生人私聊资格查询等）。
 * 普通用户申请成为主播并通过审核后，live-api 会调用 im-platform 同步缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LivePlatformClient {

    private final LiveProperties liveProps;
    private final RedisTemplate<String, Object> redisTemplate;

    private HttpClient httpClient;

    /**
     * recvId 是否为允许陌生人私聊的主播（认证主播或直播间 anchor_user_id）。
     */
    public boolean allowsStrangerPrivateMessage(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }
        if (liveProps.getOfficialAnchorUserIds() != null
            && liveProps.getOfficialAnchorUserIds().contains(userId)) {
            return true;
        }
        String cacheKey = RedisKey.IM_CACHE_LIVE_ANCHOR_PM + ":" + userId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof Boolean b) {
            return b;
        }
        if (cached != null) {
            return Boolean.parseBoolean(String.valueOf(cached));
        }
        boolean allowed = fetchAllowsStrangerPm(userId);
        long ttl = Math.max(60L, liveProps.getAnchorPmCacheSeconds());
        redisTemplate.opsForValue().set(cacheKey, allowed, ttl, TimeUnit.SECONDS);
        return allowed;
    }

    /**
     * 由 live-api 在主播审核通过/撤销时主动写入，避免缓存仍为 false 的窗口期。
     */
    public void putAnchorPmAllowed(Long userId, boolean allowed) {
        if (userId == null || userId <= 0) {
            return;
        }
        String cacheKey = RedisKey.IM_CACHE_LIVE_ANCHOR_PM + ":" + userId;
        long ttl = Math.max(60L, liveProps.getAnchorPmCacheSeconds());
        redisTemplate.opsForValue().set(cacheKey, allowed, ttl, TimeUnit.SECONDS);
    }

    private boolean fetchAllowsStrangerPm(Long userId) {
        String baseUrl = liveProps.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            log.warn("live.base-url 未配置，无法校验主播陌生人私聊资格 userId={}", userId);
            return false;
        }
        String base = baseUrl.replaceAll("/$", "");
        for (String path : List.of(
            "/internal/live/anchor/" + userId + "/allows-stranger-pm",
            "/live/anchor/" + userId + "/allows-stranger-pm")) {
            Boolean allowed = requestAllowsStrangerPm(base + path, userId);
            if (allowed != null) {
                return allowed;
            }
        }
        return false;
    }

    private Boolean requestAllowsStrangerPm(String url, Long userId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(liveProps.getRequestTimeoutMs()))
                .GET()
                .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("live-api anchor pm check failed status={} userId={} url={}",
                    response.statusCode(), userId, url);
                return null;
            }
            JSONObject body = JSON.parseObject(response.body());
            if (body == null || body.getIntValue("code") != 200) {
                return null;
            }
            JSONObject data = body.getJSONObject("data");
            return data != null && data.getBooleanValue("allowed");
        } catch (Exception e) {
            log.warn("live-api anchor pm check error userId={} url={}", userId, url, e);
            return null;
        }
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(liveProps.getRequestTimeoutMs()))
                .build();
        }
        return httpClient;
    }
}
