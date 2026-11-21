package com.bx.implatform.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.AagamesProperties;
import com.bx.implatform.dto.RegisterDTO;
import com.bx.implatform.enums.ResultCode;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.util.BoximOutboundSigner;
import com.bx.implatform.util.ClientDeviceHeaders;
import com.bx.implatform.vo.AagamesSsoExchangeVO;
import com.bx.implatform.vo.AagamesUserVO;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.JakartaServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 调用 aagames_api 的 /internal/auth/validate（接口⑥）。
 *
 * 失败时抛 GlobalException，调用方按需要处理。
 * 成功时直接落入 Redis 缓存（默认 60 秒），key 使用 token 的 MD5。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AagamesAuthClient {

    private static final String CACHE_KEY_PREFIX = "boxim:aagames:token:";

    private final AagamesProperties aagamesProps;
    private final AagamesMerchantSupport merchantSupport;
    private final RedisTemplate<String, Object> redisTemplate;
    private final HttpServletRequest request;

    private HttpClient httpClient;

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
                .build();
        }
        return httpClient;
    }

    /**
     * 校验 aagames access_token，命中 Redis 缓存则直接返回；否则调远端接口⑥。
     *
     * @param accessToken aagames 颁发的 access_token
     * @return AagamesUserVO（含 status/userId 等），永不返回 null（失败抛异常）
     * @throws GlobalException code=410 表示 token 无效 / 用户被冻结，前端需引导重新登录
     */
    /**
     * 用账号密码登录 aagames，换取 access_token。
     *
     * 注意：aagames 端 /login 是 nologin 路由组，无需 HMAC 签名；只需正确的 merchant_no 即可。
     * 失败时抛 {@link GlobalException}：
     *   - code=400  账号 / 密码 / 商户号错误（透传 aagames msg）
     *   - code=500  网络 / 服务异常 / 配置缺失
     *
     * @param userName aagames 端用户名（也支持手机号匹配）
     * @param password 明文密码（aagames 端用 md5 比对）
     * @return aagames 颁发的 access_token
     */
    public String login(String userName, String password, String merchantNo) {
        if (userName == null || userName.isEmpty()) {
            throw new GlobalException(ResultCode.PASSWOR_ERROR, "请输入账号");
        }
        if (password == null || password.isEmpty()) {
            throw new GlobalException(ResultCode.PASSWOR_ERROR, "请输入密码");
        }
        merchantNo = merchantSupport.requireAllowed(merchantNo);

        String path = aagamesProps.getPaths().getUserLogin();
        String url = aagamesProps.getBaseUrl() + path;

        JSONObject reqBody = new JSONObject();
        reqBody.put("username", userName);
        reqBody.put("password", password);
        reqBody.put("merchant_no", merchantNo);
        String body = reqBody.toJSONString();

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "box-im-platform/1.0")
            .header("X-Forwarded-For", JakartaServletUtil.getClientIP(request))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("aagames /login IO error url={}, err={}", url, e.toString());
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "登录服务暂不可用，请稍后再试");
        }

        int status = response.statusCode();
        String respBody = response.body();
        if (status != 200) {
            log.warn("aagames /login unexpected http status={}, body={}", status, respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "登录服务异常");
        }

        JSONObject json;
        try {
            json = JSON.parseObject(respBody);
        } catch (Exception e) {
            log.error("aagames /login parse error body={}", respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "登录服务响应异常");
        }

        // aagames 用 code=0/msg='success' 表示成功；非 0 时 msg 是中文错误提示
        Integer code = json.getInteger("code");
        String msg = json.getString("msg");
        if (code == null) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "登录服务响应异常");
        }
        if (code != 0) {
            log.info("aagames /login biz-fail code={} msg={} user={}", code, msg, userName);
            // 用 PASSWOR_ERROR(10001) 而不是 400/401，避免被前端拦截器误判为"会话失效"触发跳登录
            throw new GlobalException(ResultCode.PASSWOR_ERROR,
                msg != null && !msg.isEmpty() ? msg : "账号或密码错误");
        }

        JSONObject data = json.getJSONObject("data");
        if (data == null) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "登录服务响应异常");
        }
        String accessToken = data.getString("access_token");
        if (accessToken == null || accessToken.isEmpty()) {
            log.error("aagames /login success but no access_token, body={}", respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "登录服务响应异常");
        }
        return accessToken;
    }

    /**
     * 账号密码登录。aagames 返回「该用户名不存在」时，按同一商户、同一密码补注册，再登录一次。
     * 密码错误、账号禁用等其它失败原样抛出，不会拿去注册。
     */
    public String loginOrRegister(String userName, String password, String merchantNo) {
        try {
            return login(userName, password, merchantNo);
        } catch (GlobalException ex) {
            if (!isMissingAccount(ex.getMessage())) {
                throw ex;
            }
        }
        log.info("aagames login user missing, provision then login user={} merchant={}", userName, merchantNo);
        provisionForLogin(userName, password, merchantNo);
        return login(userName, password, merchantNo);
    }

    /**
     * 代调 aagames 用户注册接口。
     *
     * <p>注意：box-im 侧不落库任何登录凭据；注册成功后由调用方按需继续走 {@link #login(String, String)}
     * + /loginExternal 触发用户同步。</p>
     */
    public void register(RegisterDTO dto) {
        if (dto == null) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册参数缺失");
        }
        String merchantNo = merchantSupport.requireAllowed(dto.getMerchantNo());
        String path = aagamesProps.getPaths().getUserRegister();
        if (path == null || path.isEmpty()) {
            path = "/register";
        }
        String url = aagamesProps.getBaseUrl() + path;

        // aagames User::register 必填：username, password, repassword, merchant_no, domain
        String repassword = StrUtil.isNotBlank(dto.getConfirmPassword())
            ? dto.getConfirmPassword() : dto.getPassword();
        String domain = resolveRegisterDomain();
        if (StrUtil.isBlank(domain)) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR,
                "注册域名未配置，请在 aagames.register-domain 中设置或确保请求带 Referer/Host");
        }

        JSONObject reqBody = new JSONObject();
        reqBody.put("merchant_no", merchantNo);
        reqBody.put("username", dto.getUserName());
        reqBody.put("password", dto.getPassword());
        reqBody.put("repassword", repassword);
        reqBody.put("domain", domain);
        if (StrUtil.isNotBlank(dto.getPhone())) {
            reqBody.put("mobile", dto.getPhone());
        }
        if (StrUtil.isNotBlank(dto.getCode())) {
            reqBody.put("code", dto.getCode());
        }
        reqBody.put("lot_number", dto.getLotNumber());
        reqBody.put("captcha_output", dto.getCaptchaOutput());
        reqBody.put("pass_token", dto.getPassToken());
        reqBody.put("gen_time", dto.getGenTime());
        String body = reqBody.toJSONString();

        String requestId = UUID.randomUUID().toString().replace("-", "");

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "box-im-platform/1.0")
            .header("X-Request-Id", requestId)
            .header("X-Forwarded-For", JakartaServletUtil.getClientIP(request))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("aagames /register IO error url={}, err={}", url, e.toString());
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册服务暂不可用，请稍后再试");
        }

        int status = response.statusCode();
        String respBody = response.body();
        if (status != 200) {
            log.warn("aagames /register unexpected http status={}, body={}", status, respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册服务异常");
        }

        JSONObject json;
        try {
            json = JSON.parseObject(respBody);
        } catch (Exception e) {
            log.error("aagames /register parse error body={}", respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册服务响应异常");
        }

        Integer code = json.getInteger("code");
        String msg = json.getString("msg");
        if (code == null) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册服务响应异常");
        }
        if (code != 0) {
            log.info("aagames /register biz-fail code={} msg={} user={} requestId={}", code, msg, dto.getUserName(), requestId);
            throw new GlobalException(ResultCode.PROGRAM_ERROR,
                (msg != null && !msg.isEmpty()) ? msg : "注册失败",
                mapGeetestErrorCode(msg));
        }
    }

    private static String mapGeetestErrorCode(String msg) {
        if (msg == null || msg.isEmpty()) {
            return null;
        }
        if (msg.contains("请先完成人机验证") || msg.contains("验证码所需参数不能为空")) {
            return "GEETEST_REQUIRED";
        }
        if (msg.contains("人机验证未通过")) {
            return "GEETEST_FAILED";
        }
        if (msg.contains("验证已失效")) {
            return "GEETEST_EXPIRED";
        }
        if (msg.contains("人机验证服务暂不可用")) {
            return "GEETEST_UNAVAILABLE";
        }
        return null;
    }

    private void provisionForLogin(String userName, String password, String merchantNo) {
        String merchant = merchantSupport.requireAllowed(merchantNo);
        String domain = resolveRegisterDomain();
        if (StrUtil.isBlank(domain)) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR,
                "注册域名未配置，请在 aagames.register-domain 中设置或确保请求带 Referer/Host");
        }
        JSONObject reqBody = new JSONObject();
        reqBody.put("merchant_no", merchant);
        reqBody.put("username", userName);
        reqBody.put("password", password);
        reqBody.put("repassword", password);
        reqBody.put("domain", domain);
        String body = reqBody.toJSONString();

        String path = aagamesProps.getPaths().getUserProvision();
        if (path == null || path.isBlank()) {
            path = "/internal/user/register";
        }
        HttpResponse<String> response = signedPost(path, body, "aagames provision");
        if (response.statusCode() == 404) {
            RegisterDTO dto = new RegisterDTO();
            dto.setMode("username");
            dto.setMerchantNo(merchant);
            dto.setUserName(userName);
            dto.setPassword(password);
            dto.setConfirmPassword(password);
            try {
                register(dto);
            } catch (GlobalException ex) {
                if (!isAlreadyRegistered(ex.getMessage())) {
                    throw ex;
                }
            }
            return;
        }
        assertProvisionOk(response, userName);
    }

    private HttpResponse<String> signedPost(String path, String body, String action) {
        if (aagamesProps.getOutboundSecret() == null || aagamesProps.getOutboundSecret().isEmpty()) {
            throw new GlobalException(500, "aagames.outboundSecretMissing");
        }
        String url = aagamesProps.getBaseUrl() + path;
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signString = BoximOutboundSigner.buildSignString(timestamp, nonce, "POST", path, body);
        String signature = BoximOutboundSigner.sign(signString, aagamesProps.getOutboundSecret());
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "box-im-platform/1.0")
            .header(BoximOutboundSigner.HEADER_TIMESTAMP, timestamp)
            .header(BoximOutboundSigner.HEADER_NONCE, nonce)
            .header(BoximOutboundSigner.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        try {
            return httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("{} IO error url={}, err={}", action, url, e.toString());
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册服务暂不可用，请稍后再试");
        }
    }

    private void assertProvisionOk(HttpResponse<String> response, String userName) {
        int status = response.statusCode();
        String respBody = response.body();
        if (status == 401 || status == 403 || status == 409) {
            log.error("aagames provision auth-layer failed status={}, body={}", status, respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册服务配置异常");
        }
        if (status != 200) {
            log.warn("aagames provision unexpected status={}, body={}", status, respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册服务异常");
        }
        JSONObject json;
        try {
            json = JSON.parseObject(respBody);
        } catch (Exception e) {
            log.error("aagames provision parse error body={}", respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册服务响应异常");
        }
        Integer code = json.getInteger("code");
        String msg = firstNonBlank(json.getString("msg"), json.getString("message"));
        if (code == null) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "注册服务响应异常");
        }
        if (code == 0 || isAlreadyRegistered(msg)) {
            return;
        }
        log.info("aagames provision biz-fail code={} msg={} user={}", code, msg, userName);
        throw new GlobalException(ResultCode.PROGRAM_ERROR,
            (msg != null && !msg.isEmpty()) ? msg : "注册失败");
    }

    private static boolean isMissingAccount(String message) {
        return message != null && (message.contains("该用户名不存在") || message.contains("用户名不存在"));
    }

    private static boolean isAlreadyRegistered(String message) {
        return message != null && message.contains("用户名已存在");
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    /**
     * 与 aagames {@code Helper::getRootDomain} 对齐：用于注册 domain 字段。
     */
    private String resolveRegisterDomain() {
        if (StrUtil.isNotBlank(aagamesProps.getRegisterDomain())) {
            return toRootDomain(aagamesProps.getRegisterDomain().trim());
        }
        String referer = request.getHeader("Referer");
        if (StrUtil.isNotBlank(referer)) {
            try {
                String host = URI.create(referer).getHost();
                if (StrUtil.isNotBlank(host)) {
                    return toRootDomain(host);
                }
            } catch (Exception ignored) {
                // ignore malformed referer
            }
        }
        String host = request.getHeader("Host");
        if (StrUtil.isBlank(host)) {
            host = request.getServerName();
        }
        if (StrUtil.isNotBlank(host)) {
            int colon = host.indexOf(':');
            if (colon > 0) {
                host = host.substring(0, colon);
            }
            return toRootDomain(host);
        }
        return "";
    }

    private static String toRootDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return "";
        }
        domain = domain.trim().toLowerCase();
        long dotCount = domain.chars().filter(ch -> ch == '.').count();
        if (dotCount <= 1) {
            return domain;
        }
        int pos = domain.indexOf('.');
        return pos >= 0 ? domain.substring(pos + 1) : domain;
    }

    /**
     * 用 aagames SSO 一次性 code 兑换：账号信息 + IM 专用 token（不触发 aagames /login，不轮换游戏 token）。
     */
    public AagamesSsoExchangeVO exchangeSsoCode(String ssoCode, String merchantNo) {
        if (ssoCode == null || ssoCode.isBlank()) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "ssoCode 不能为空");
        }
        if (aagamesProps.getOutboundSecret() == null || aagamesProps.getOutboundSecret().isEmpty()) {
            throw new GlobalException(500, "aagames.outboundSecretMissing");
        }

        String path = aagamesProps.getPaths().getSsoExchange();
        if (path == null || path.isBlank()) {
            path = "/internal/sso/exchange";
        }
        String url = aagamesProps.getBaseUrl() + path;

        JSONObject reqBody = new JSONObject();
        reqBody.put("ssoCode", ssoCode.trim());
        reqBody.put("merchant_no", merchantSupport.requireAllowed(merchantNo));
        String body = reqBody.toJSONString();

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signString = BoximOutboundSigner.buildSignString(timestamp, nonce, "POST", path, body);
        String signature = BoximOutboundSigner.sign(signString, aagamesProps.getOutboundSecret());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "box-im-platform/1.0")
            .header(BoximOutboundSigner.HEADER_TIMESTAMP, timestamp)
            .header(BoximOutboundSigner.HEADER_NONCE, nonce)
            .header(BoximOutboundSigner.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("aagames sso/exchange IO error url={}, err={}", url, e.toString());
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录服务暂不可用");
        }

        int status = response.statusCode();
        String respBody = response.body();
        if (status == 401 || status == 403 || status == 409) {
            log.error("aagames sso/exchange auth-layer failed status={}, body={}", status, respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录配置异常");
        }
        if (status != 200) {
            log.warn("aagames sso/exchange unexpected status={}, body={}", status, respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录失败");
        }

        JSONObject json;
        try {
            json = JSON.parseObject(respBody);
        } catch (Exception e) {
            log.error("aagames sso/exchange parse error body={}", respBody);
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录响应异常");
        }

        Integer code = json.getInteger("code");
        String message = json.getString("message");
        if (code == null) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录响应异常");
        }
        if (code != 0) {
            log.info("aagames sso/exchange biz-fail code={} msg={}", code, message);
            throw new GlobalException(410, message != null && !message.isEmpty() ? message : "SSO 登录已失效");
        }

        JSONObject data = json.getJSONObject("data");
        if (data == null) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录响应异常");
        }

        String imAccessToken = data.getString("imAccessToken");
        if (StrUtil.isBlank(imAccessToken)) {
            imAccessToken = data.getString("accessToken");
        }
        if (StrUtil.isBlank(imAccessToken)) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录响应异常");
        }

        AagamesUserVO user = new AagamesUserVO();
        String userIdStr = data.getString("userId");
        if (StrUtil.isBlank(userIdStr)) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录响应异常");
        }
        try {
            user.setUserId(Long.parseLong(userIdStr));
        } catch (NumberFormatException e) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录响应异常");
        }
        user.setUserName(data.getString("userName"));
        user.setNickName(data.getString("nickName"));
        user.setUserAvatar(data.getString("userAvatar"));
        user.setMerchantNo(data.getString("merchantNo"));
        user.setStatus(data.getString("status"));
        user.setTokenExpireAt(data.getLong("tokenExpireAt"));

        if (!"active".equals(user.getStatus())) {
            log.warn("aagames sso user not active userId={} status={}", user.getUserId(), user.getStatus());
            throw new GlobalException(410, "账户已被冻结");
        }

        AagamesSsoExchangeVO result = new AagamesSsoExchangeVO();
        result.setImAccessToken(imAccessToken);
        result.setUser(user);
        return result;
    }

    public AagamesUserVO validate(String accessToken, String merchantNo) {
        if (accessToken == null || accessToken.isEmpty()) {
            throw new GlobalException(410, "user.externalLoginExpired");
        }
        merchantNo = merchantSupport.requireAllowed(merchantNo);

        String cacheKey = CACHE_KEY_PREFIX + merchantNo + ":" + md5(accessToken);
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof AagamesUserVO) {
            return (AagamesUserVO) cached;
        }
        if (cached != null) {
            // 类型不匹配，丢弃
            redisTemplate.delete(cacheKey);
        }

        AagamesUserVO vo = doValidate(accessToken, merchantNo);

        int ttl = aagamesProps.getCache().getAuthTokenTtlSeconds();
        if (ttl > 0) {
            redisTemplate.opsForValue().set(cacheKey, vo, ttl, TimeUnit.SECONDS);
        }
        return vo;
    }

    private AagamesUserVO doValidate(String accessToken, String merchantNo) {
        if (aagamesProps.getOutboundSecret() == null || aagamesProps.getOutboundSecret().isEmpty()) {
            throw new GlobalException(500, "aagames.outboundSecretMissing");
        }

        String path = aagamesProps.getPaths().getAuthValidate();
        String url = aagamesProps.getBaseUrl() + path;

        JSONObject reqBody = new JSONObject();
        reqBody.put("accessToken", accessToken);
        reqBody.put("merchant_no", merchantSupport.requireAllowed(merchantNo));
        String body = reqBody.toJSONString();

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signString = BoximOutboundSigner.buildSignString(timestamp, nonce, "POST", path, body);
        String signature = BoximOutboundSigner.sign(signString, aagamesProps.getOutboundSecret());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "box-im-platform/1.0")
            .header(BoximOutboundSigner.HEADER_TIMESTAMP, timestamp)
            .header(BoximOutboundSigner.HEADER_NONCE, nonce)
            .header(BoximOutboundSigner.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("aagames auth/validate IO error url={}, err={}", url, e.toString());
            throw new GlobalException(500, "aagames.unreachable");
        }

        int status = response.statusCode();
        String respBody = response.body();
        // HTTP 401/403/409 等都是 HMAC / IP 层失败，本地配置异常
        if (status == 401 || status == 403 || status == 409) {
            log.error("aagames auth/validate auth-layer failed status={}, body={}", status, respBody);
            throw new GlobalException(500, "aagames.systemAuthFailed");
        }
        if (status >= 500) {
            log.error("aagames auth/validate 5xx status={}, body={}", status, respBody);
            throw new GlobalException(500, "aagames.serverError");
        }
        if (status != 200) {
            log.error("aagames auth/validate unexpected status={}, body={}", status, respBody);
            throw new GlobalException(500, "aagames.unexpectedStatus");
        }

        JSONObject json;
        try {
            json = JSON.parseObject(respBody);
        } catch (Exception e) {
            log.error("aagames auth/validate parse error body={}", respBody);
            throw new GlobalException(500, "aagames.badResponse");
        }

        Integer code = json.getInteger("code");
        String message = json.getString("message");
        if (code == null) {
            throw new GlobalException(500, "aagames.badResponse");
        }
        if (code != 0) {
            // 业务错误 —— 主要是 40006 token 失效 / 冻结
            log.warn("aagames auth/validate biz-fail code={} msg={}", code, message);
            throw new GlobalException(410, message != null ? message : "登录已失效，请重新登录");
        }

        JSONObject data = json.getJSONObject("data");
        if (data == null) {
            throw new GlobalException(500, "aagames.badResponse");
        }

        AagamesUserVO vo = new AagamesUserVO();
        String userIdStr = data.getString("userId");
        if (userIdStr == null || userIdStr.isEmpty()) {
            throw new GlobalException(500, "aagames.badResponse");
        }
        try {
            vo.setUserId(Long.parseLong(userIdStr));
        } catch (NumberFormatException e) {
            throw new GlobalException(500, "aagames.badResponse");
        }
        vo.setUserName(data.getString("userName"));
        vo.setNickName(data.getString("nickName"));
        vo.setUserAvatar(data.getString("userAvatar"));
        String tokenMerchant = data.getString("merchantNo");
        if (tokenMerchant == null || tokenMerchant.isBlank()) {
            vo.setMerchantNo(merchantNo);
        } else {
            if (!merchantNo.equals(tokenMerchant.trim())) {
                log.warn("aagames token merchant mismatch request={} tokenMerchant={} userId={}",
                    merchantNo, tokenMerchant, userIdStr);
            }
            vo.setMerchantNo(tokenMerchant.trim());
        }
        vo.setStatus(data.getString("status"));
        vo.setTokenExpireAt(data.getLong("tokenExpireAt"));

        if (!"active".equals(vo.getStatus())) {
            log.warn("aagames user not active userId={} status={}", vo.getUserId(), vo.getStatus());
            throw new GlobalException(410, "账户已被冻结");
        }

        return vo;
    }

    /**
     * 根据当前 HTTP 请求头判断是否为打包 App 登录，是则上报 aagames（任务中心进度）。
     * 仅读 header，不依赖 body.terminal；失败不阻断登录。
     */
    public void recordImAppLoginFromRequest(Long userId, HttpServletRequest httpRequest) {
        if (userId == null || userId <= 0 || httpRequest == null) {
            return;
        }
        var packaged = ClientDeviceHeaders.parsePackagedAppLogin(httpRequest);
        if (packaged.isEmpty()) {
            return;
        }
        ClientDeviceHeaders.PackagedAppLogin headers = packaged.get();
        log.info("packaged app IM login userId={} device={} version={}",
            userId, headers.deviceType(), headers.versionCode());
        recordImAppLogin(userId, headers.deviceType(), headers.versionCode());
    }

    /**
     * 上报用户从 Android/iOS 打包 App 登录 IM（任务中心 template_id=1 进度）。
     * 失败仅记日志，不阻断登录主流程。
     */
    private void recordImAppLogin(Long userId, String deviceType, String versionCode) {
        if (userId == null || userId <= 0) {
            return;
        }
        if (deviceType == null || deviceType.isBlank()) {
            return;
        }
        String normalized = deviceType.trim().toUpperCase();
        if (!"ANDROID".equals(normalized) && !"IOS".equals(normalized)) {
            return;
        }
        if (versionCode == null || versionCode.isBlank()) {
            return;
        }
        if (aagamesProps.getOutboundSecret() == null || aagamesProps.getOutboundSecret().isEmpty()) {
            log.warn("skip im-app-login report: outboundSecret missing userId={}", userId);
            return;
        }

        String pathTpl = aagamesProps.getPaths().getImAppLogin();
        if (pathTpl == null || pathTpl.isBlank()) {
            pathTpl = "/internal/user/{userId}/im-app-login";
        }
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        String url = aagamesProps.getBaseUrl() + path;

        JSONObject reqBody = new JSONObject();
        reqBody.put("merchant_no", merchantSupport.requireForUser(userId));
        reqBody.put("deviceType", normalized);
        reqBody.put("versionCode", versionCode != null ? versionCode : "");
        String body = reqBody.toJSONString();

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signString = BoximOutboundSigner.buildSignString(timestamp, nonce, "POST", path, body);
        String signature = BoximOutboundSigner.sign(signString, aagamesProps.getOutboundSecret());

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "box-im-platform/1.0")
            .header(BoximOutboundSigner.HEADER_TIMESTAMP, timestamp)
            .header(BoximOutboundSigner.HEADER_NONCE, nonce)
            .header(BoximOutboundSigner.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("im-app-login report http-fail userId={} status={} body={}", userId, response.statusCode(), response.body());
                return;
            }
            JSONObject json = JSON.parseObject(response.body());
            Integer code = json != null ? json.getInteger("code") : null;
            if (code == null || code != 0) {
                log.warn("im-app-login report biz-fail userId={} code={} msg={}", userId, code,
                    json != null ? json.getString("message") : null);
            }
        } catch (Exception e) {
            log.warn("im-app-login report error userId={} err={}", userId, e.toString());
        }
    }

    /**
     * 向 aagames 签发 IM 专用 token（缓存 miss 时供 H5 user_api 使用）。
     */
    public String issueImToken(Long userId) {
        if (userId == null || userId <= 0) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "userId 无效");
        }
        if (aagamesProps.getOutboundSecret() == null || aagamesProps.getOutboundSecret().isEmpty()) {
            throw new GlobalException(500, "aagames.outboundSecretMissing");
        }

        String pathTpl = aagamesProps.getPaths().getImTokenIssue();
        if (pathTpl == null || pathTpl.isBlank()) {
            pathTpl = "/internal/user/{userId}/im-token/issue";
        }
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        String url = aagamesProps.getBaseUrl() + path;

        JSONObject reqBody = new JSONObject();
        reqBody.put("merchant_no", merchantSupport.requireForUser(userId));
        String body = reqBody.toJSONString();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signString = BoximOutboundSigner.buildSignString(timestamp, nonce, "POST", path, body);
        String signature = BoximOutboundSigner.sign(signString, aagamesProps.getOutboundSecret());

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "box-im-platform/1.0")
            .header(BoximOutboundSigner.HEADER_TIMESTAMP, timestamp)
            .header(BoximOutboundSigner.HEADER_NONCE, nonce)
            .header(BoximOutboundSigner.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("aagames im-token/issue IO error url={}, err={}", url, e.toString());
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "游戏登录服务暂不可用");
        }

        if (response.statusCode() != 200) {
            log.warn("aagames im-token/issue unexpected status={}, body={}", response.statusCode(), response.body());
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "游戏登录失败");
        }

        JSONObject json;
        try {
            json = JSON.parseObject(response.body());
        } catch (Exception e) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "游戏登录响应异常");
        }

        Integer code = json.getInteger("code");
        String message = json.getString("message");
        if (code == null || code != 0) {
            throw new GlobalException(410, message != null && !message.isEmpty() ? message : "游戏登录已失效");
        }

        JSONObject data = json.getJSONObject("data");
        if (data == null) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "游戏登录响应异常");
        }

        String token = data.getString("imAccessToken");
        if (StrUtil.isBlank(token)) {
            token = data.getString("token");
        }
        if (StrUtil.isBlank(token)) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "游戏登录响应异常");
        }

        return token;
    }

    private static String md5(String s) {
        try {
            byte[] raw = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
