package com.bx.implatform.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.exception.GlobalException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 从共用 Redis 解析 aagames 用户 token。
 *
 * <p>SSO 后 IM 侧缓存的是 IM 专用 token；过期后 boxim / user_api 仍可用游戏端 access_token（多会话或按 uid 缓存）。</p>
 */
@Component
@RequiredArgsConstructor
public class AagamesUserTokenResolver {

    /** 使用 String 序列化读取，兼容 aagames(PHP) 纯文本与 IM(Jackson 引号包裹) 两种存法 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * boxim 外部路由（HMAC + boxim.user）：优先有效 IM token，否则回退游戏 token。
     */
    public String resolveForBoxim(Long userId) {
        String imToken = resolveActiveImToken(userId);
        if (imToken != null) {
            return imToken;
        }
        String gameToken = resolveGameToken(userId);
        if (gameToken != null) {
            return gameToken;
        }
        // login/external 写入 im:aagames:user:token 的往往是游戏 token（非 IM token）
        String imCached = readImCachedToken(userId);
        if (imCached != null && !isImToken(imCached) && tokenOwnerMatches(imCached, userId)) {
            return imCached;
        }
        throw loginExpired();
    }

    /**
     * user_api 路由：优先有效 IM token，否则游戏 access_token。
     */
    public String resolveForUserApi(Long userId) {
        String imToken = resolveActiveImToken(userId);
        if (imToken != null) {
            return imToken;
        }
        String gameToken = resolveGameToken(userId);
        if (gameToken != null) {
            return gameToken;
        }
        String imCached = readImCachedToken(userId);
        if (imCached != null && !isImToken(imCached) && tokenOwnerMatches(imCached, userId)) {
            return imCached;
        }
        throw loginExpired();
    }

    /** 有效 IM 专用 token：im 侧缓存 → aagames:im:user:{uid} */
    private String resolveActiveImToken(Long userId) {
        String imCached = readImCachedToken(userId);
        if (isValidImToken(imCached, userId)) {
            return imCached;
        }
        String byImUser = readTokenString(RedisKey.AAGAMES_IM_USER + userId);
        if (isValidImToken(byImUser, userId)) {
            return byImUser;
        }
        return null;
    }

    private String readImCachedToken(Long userId) {
        String lt = readTokenString(RedisKey.IM_AAGAMES_USER_TOKEN_LT + ":" + userId);
        if (lt != null) {
            return lt;
        }
        return readTokenString(RedisKey.IM_AAGAMES_USER_TOKEN + ":" + userId);
    }

    private String resolveGameToken(Long userId) {
        String byUid = readTokenString(RedisKey.AAGAMES_USER_TOKEN_BY_UID + ":" + userId);
        // 非 IM token 即视为游戏 access_token（可能是 j_users 主 token 或多会话之一）
        if (byUid != null && !isImToken(byUid) && tokenOwnerMatches(byUid, userId)) {
            return byUid;
        }
        Set<String> members = stringRedisTemplate.opsForSet().members(RedisKey.AAGAMES_SESSION_INDEX + userId);
        if (members == null || members.isEmpty()) {
            return null;
        }
        for (String token : members) {
            if (token == null || token.isEmpty() || isImToken(token)) {
                continue;
            }
            if (isValidGameSessionToken(token, userId)) {
                return token;
            }
        }
        return null;
    }

    private String readTokenString(String key) {
        String raw = stringRedisTemplate.opsForValue().get(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        raw = raw.trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw.isEmpty() ? null : raw;
    }

    private boolean isValidImToken(String token, Long userId) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        String json = stringRedisTemplate.opsForValue().get(RedisKey.AAGAMES_IM_ACCESS + token);
        if (json == null || json.isEmpty()) {
            return false;
        }
        try {
            JSONObject data = JSON.parseObject(json);
            int expireAt = data.getIntValue("expire_at");
            long payloadUserId = data.getLongValue("user_id");
            return payloadUserId == userId
                && expireAt > 0
                && expireAt >= (int) (System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isImToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKey.AAGAMES_IM_ACCESS + token));
    }

    private boolean isValidGameSessionToken(String token, Long userId) {
        if (token == null || token.isEmpty() || userId == null) {
            return false;
        }
        String uid = stringRedisTemplate.opsForValue().get(RedisKey.AAGAMES_SESSION_TOKEN + token);
        return uid != null && String.valueOf(userId).equals(uid.trim());
    }

    /** 会话索引里能查到归属时，必须就是当前 userId。查不到则交给 aagames 自己校验。 */
    private boolean tokenOwnerMatches(String token, Long userId) {
        if (token == null || token.isEmpty() || userId == null) {
            return false;
        }
        String uid = stringRedisTemplate.opsForValue().get(RedisKey.AAGAMES_SESSION_TOKEN + token);
        if (uid == null || uid.isEmpty()) {
            return true;
        }
        return String.valueOf(userId).equals(uid.trim());
    }

    private static GlobalException loginExpired() {
        return new GlobalException(410, "user.externalLoginExpired");
    }
}
