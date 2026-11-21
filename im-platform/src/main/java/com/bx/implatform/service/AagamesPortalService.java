package com.bx.implatform.service;

import cn.hutool.core.util.StrUtil;
import com.bx.implatform.client.AagamesAuthClient;
import com.bx.implatform.client.AagamesUserTokenResolver;
import com.bx.implatform.client.SportBoundLoginClient;
import com.bx.implatform.config.props.AagamesProperties;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.entity.User;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.vo.AagamesPortalVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 方案 A：IM 已登录 → 暴露 aagames token → H5 用 ?at= 直连 user_api。
 */
@Service
@RequiredArgsConstructor
public class AagamesPortalService {

    private final AagamesUserTokenResolver tokenResolver;
    private final AagamesAuthClient aagamesAuthClient;
    private final AagamesProperties aagamesProps;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserMapper userMapper;
    private final SportBoundLoginClient sportBoundLogin;

    public AagamesPortalVO launch(Long userId, String program, String brand) {
        if (userId == null || userId <= 0) {
            throw new GlobalException(400, "用户未登录");
        }
        User user = userMapper.selectById(userId);
        if (user != null && "sport".equals(user.getMerchantNo())) {
            return sportPortal(user, program, brand);
        }

        String token = resolveToken(userId);
        String h5Base = StrUtil.blankToDefault(aagamesProps.getH5LoginUrl(), "https://h5.7pgb99.com/");
        h5Base = h5Base.endsWith("/") ? h5Base : h5Base + "/";
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String h5LoginUrl = h5Base + "?at=" + encoded;

        AagamesPortalVO vo = new AagamesPortalVO();
        vo.setAccessToken(token);
        vo.setH5LoginUrl(h5LoginUrl);
        return vo;
    }

    private AagamesPortalVO sportPortal(User user, String program, String brandHint) {
        String brand = resolveSportBrand(program, brandHint);
        String name = user.getUserName() == null ? "" : user.getUserName();
        String phone = user.getPhone() == null ? "" : user.getPhone();
        if (brand.isEmpty() || !name.matches("s[a-fA-F0-9]{32}") || !phone.matches("1[3-9]\\d{9}")) {
            throw new GlobalException(4001, "sport.portalUnavailable");
        }
        guardSportPortalRate(user.getId());
        String token = sportBoundLogin.accessToken(name.substring(1).toLowerCase(), phone, brand);
        AagamesPortalVO vo = new AagamesPortalVO();
        vo.setAccessToken(token);
        vo.setH5LoginUrl("");
        return vo;
    }

    /** program 优先。空 body 不能落成吉祥或直播。 */
    private static String resolveSportBrand(String program, String brand) {
        if ("sports-jixiang-1".equals(program)) {
            return "jxt";
        }
        if ("sports-ued-1".equals(program)) {
            return "ued";
        }
        if ("sports-live-1".equals(program)) {
            return "live";
        }
        if ("jxt".equals(brand) || "ued".equals(brand) || "live".equals(brand)) {
            return brand;
        }
        return "";
    }

    private void guardSportPortalRate(Long userId) {
        String key = RedisKey.IM_SPORT_PORTAL_RATE + ":" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        }
        if (count != null && count > 10) {
            throw new GlobalException(4001, "sport.portalUnavailable");
        }
    }

    private String resolveToken(Long userId) {
        try {
            return tokenResolver.resolveForUserApi(userId);
        } catch (GlobalException ignored) {
            // 缓存 miss 或过期：向 aagames 签发 fresh IM token
        }

        String issued = aagamesAuthClient.issueImToken(userId);
        cacheImToken(userId, issued);
        return issued;
    }

    private void cacheImToken(Long userId, String token) {
        int ttl = Math.max(3600, aagamesProps.getJwtCapSeconds());
        String key = RedisKey.IM_AAGAMES_USER_TOKEN + ":" + userId;
        redisTemplate.opsForValue().set(key, token, ttl, TimeUnit.SECONDS);
    }
}
