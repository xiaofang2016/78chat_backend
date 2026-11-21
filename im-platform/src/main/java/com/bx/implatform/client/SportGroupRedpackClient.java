package com.bx.implatform.client;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.SportAccountProperties;
import com.bx.implatform.entity.User;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.util.BoximOutboundSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 职圈群红包的钱进出说球帝余额。同一单号再调不会扣两次、也不会入两次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SportGroupRedpackClient {

    private static final String PATH = "/prod-api/user/group-redpack";

    private final SportAccountProperties props;

    private HttpClient httpClient;

    public void debit(User user, BigDecimal yuan, String tradeNo) {
        move(user, "debit", yuan, tradeNo, "发红包");
    }

    public void credit(User user, BigDecimal yuan, String tradeNo, String title) {
        move(user, "credit", yuan, tradeNo, title);
    }

    private void move(User user, String direction, BigDecimal yuan, String tradeNo, String title) {
        String secret = props.getBoundSecret() == null ? "" : props.getBoundSecret().trim();
        if (user == null || secret.isEmpty() || StrUtil.isBlank(props.getGroupRedpackUrl())) {
            log.warn("说球帝群红包未配置");
            throw new GlobalException(500, "红包暂时无法入账");
        }
        String name = user.getUserName() == null ? "" : user.getUserName();
        String phone = user.getPhone() == null ? "" : user.getPhone();
        if (!name.matches("s[a-fA-F0-9]{32}") || !phone.matches("1[3-9]\\d{9}")) {
            throw new GlobalException(400, "请先登录");
        }
        String sportUserId = name.substring(1).toLowerCase();
        String cents = String.valueOf(yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = nonce();
        String signed = sportUserId + "\n" + phone + "\n" + direction + "\n" + cents + "\n" + tradeNo + "\n" + title;
        String signature = BoximOutboundSigner.sign(
            BoximOutboundSigner.buildSignString(timestamp, nonce, "POST", PATH, signed), secret);
        JSONObject body = new JSONObject();
        body.put("userId", sportUserId);
        body.put("phone", phone);
        body.put("direction", direction);
        body.put("cents", cents);
        body.put("tradeNo", tradeNo);
        body.put("title", title);
        body.put("timestamp", timestamp);
        body.put("nonce", nonce);
        body.put("signature", signature);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(props.getGroupRedpackUrl().trim()))
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
            throw new GlobalException(500, "红包暂时无法入账");
        } catch (Exception ex) {
            log.warn("说球帝群红包失败: {}", ex.getClass().getSimpleName());
            throw new GlobalException(500, "红包暂时无法入账");
        }
        JSONObject json;
        try {
            json = JSON.parseObject(response.body());
        } catch (Exception ex) {
            json = null;
        }
        if (json != null && response.statusCode() == 200 && "0".equals(json.getString("code"))) {
            return;
        }
        String msg = json == null ? "" : json.getString("msg");
        if ("余额不足".equals(msg)) {
            throw new GlobalException(400, "余额不足");
        }
        throw new GlobalException(500, "红包暂时无法入账");
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
