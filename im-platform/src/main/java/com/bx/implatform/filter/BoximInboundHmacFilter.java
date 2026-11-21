package com.bx.implatform.filter;

import com.bx.implatform.config.props.BoximIntegrationProperties;
import com.bx.implatform.util.BoximInboundVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 入站 HMAC 校验：作用于 /external/* 路径下的所有请求。
 *
 * 校验流程（参见《红包与晒图对接接口规范-v1.1》§2.3.4）：
 *   1. timestamp 在容差窗口内（默认 ±300 秒）
 *   2. nonce 在 Redis 内不存在（重放保护）
 *   3. 计算 HMAC-SHA256 与 X-Boxim-Signature 比对
 *
 * 失败统一返回：
 *   401 + {"code": 10401, "message": "..."}   - 签名 / 头缺失 / 时间戳超窗
 *   409 + {"code": 10409, "message": "..."}   - 重放（nonce 已使用）
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class BoximInboundHmacFilter extends OncePerRequestFilter {

    private static final String NONCE_KEY_PREFIX = "boxim:inbound:nonce:";

    private final BoximIntegrationProperties props;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/external/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        // 缓存 body：HMAC 校验需要原始 bytes，下游 controller 仍要再读一次
        HttpServletRequest wrapped = (request instanceof CacheHttpServletRequestWrapper)
            ? request
            : new CacheHttpServletRequestWrapper(request);

        String secret = props.getInboundSecret();
        if (secret == null || secret.isEmpty()) {
            log.error("boxim.inbound-secret 未配置，拒绝外部入站请求");
            writeJson(response, 500, 10500, "boxim.inbound-secret not configured");
            return;
        }

        String timestamp = wrapped.getHeader(BoximInboundVerifier.HEADER_TIMESTAMP);
        String nonce = wrapped.getHeader(BoximInboundVerifier.HEADER_NONCE);
        String signature = wrapped.getHeader(BoximInboundVerifier.HEADER_SIGNATURE);

        if (timestamp == null || nonce == null || signature == null
            || timestamp.isEmpty() || nonce.isEmpty() || signature.isEmpty()) {
            writeJson(response, 401, 10401, "missing HMAC headers");
            return;
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            writeJson(response, 401, 10401, "invalid timestamp");
            return;
        }
        long now = System.currentTimeMillis();
        long toleranceMs = props.getTimestampToleranceSeconds() * 1000L;
        if (Math.abs(now - ts) > toleranceMs) {
            writeJson(response, 401, 10401, "timestamp out of window");
            return;
        }

        // 读取 body
        String body;
        try {
            body = StreamUtils.copyToString(wrapped.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            writeJson(response, 400, 10400, "cannot read request body");
            return;
        }

        // 拼路径含 query string
        String path = wrapped.getRequestURI();
        String query = wrapped.getQueryString();
        String pathWithQuery = (query == null || query.isEmpty()) ? path : (path + "?" + query);

        String expected = BoximInboundVerifier.sign(
            BoximInboundVerifier.buildSignString(timestamp, nonce, wrapped.getMethod(), pathWithQuery, body),
            secret);

        if (!BoximInboundVerifier.constantTimeEquals(expected, signature)) {
            log.warn("inbound HMAC mismatch path={} expected={} got={}", pathWithQuery,
                expected.substring(0, Math.min(8, expected.length())) + "...",
                signature.substring(0, Math.min(8, signature.length())) + "...");
            writeJson(response, 401, 10401, "signature mismatch");
            return;
        }

        // nonce 防重放：SETNX，存在则拒绝
        String nonceKey = NONCE_KEY_PREFIX + nonce;
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(nonceKey, "1",
            props.getNonceTtlSeconds(), TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(ok)) {
            writeJson(response, 409, 10409, "nonce replayed");
            return;
        }

        chain.doFilter(wrapped, response);
    }

    private void writeJson(HttpServletResponse response, int httpStatus, int code, String message) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        try (PrintWriter w = response.getWriter()) {
            w.write("{\"code\":" + code + ",\"message\":\"" + escape(message) + "\",\"data\":null}");
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
