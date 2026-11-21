package com.bx.implatform.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

/**
 * box-im → aagames_api 出站 HMAC-SHA256 签名工具。
 * 参考《红包与晒图对接接口规范-v1.1》§2.3。
 */
public final class BoximOutboundSigner {

    public static final String HEADER_TIMESTAMP = "X-Boxim-Timestamp";
    public static final String HEADER_NONCE = "X-Boxim-Nonce";
    public static final String HEADER_SIGNATURE = "X-Boxim-Signature";

    private static final String HMAC_ALG = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BoximOutboundSigner() {
    }

    /**
     * 拼接待签名字符串：{timestamp}\n{nonce}\n{method}\n{pathWithQuery}\n{body}
     *
     * @param method        HTTP 方法（大写）
     * @param pathWithQuery 路径含 query string，不含域名（必须以 / 开头）
     * @param body          原始请求体；GET / 无 body 请求传空字符串
     */
    public static String buildSignString(String timestamp, String nonce, String method,
                                         String pathWithQuery, String body) {
        return timestamp + "\n" + nonce + "\n" + method + "\n" + pathWithQuery + "\n"
            + (body == null ? "" : body);
    }

    public static String sign(String signString, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] raw = mac.doFinal(signString.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 init failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
