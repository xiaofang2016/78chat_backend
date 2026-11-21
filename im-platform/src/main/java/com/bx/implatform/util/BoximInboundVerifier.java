package com.bx.implatform.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * box-im 入站验签工具：用于校验 aagames → box-im 的 HMAC-SHA256 签名。
 * 算法与字符串拼接规则与 BoximOutboundSigner 完全一致（双向通用）。
 */
public final class BoximInboundVerifier {

    public static final String HEADER_TIMESTAMP = "X-Boxim-Timestamp";
    public static final String HEADER_NONCE = "X-Boxim-Nonce";
    public static final String HEADER_SIGNATURE = "X-Boxim-Signature";

    private static final String HMAC_ALG = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BoximInboundVerifier() {
    }

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
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 init failed", e);
        }
    }

    /**
     * 安全比较：避免时序攻击。
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
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
