package com.bx.implatform.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.config.props.AagamesProperties;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.util.BoximOutboundSigner;
import com.bx.implatform.vo.external.RedpacketClaimResultVO;
import com.bx.implatform.vo.external.RedpacketRecordsVO;
import com.bx.implatform.vo.external.UserDailyQuotaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 调用 aagames_api 业务接口（接口③/④/⑤）。
 * 与 {@link AagamesAuthClient} 不同：本类的请求都需要"双层鉴权" —— HMAC 签名 + accessToken（用户身份层）。
 *
 * 详见《红包与晒图对接接口规范-v1.1》§2.3.5、§4.3 / §4.4 / §4.5。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AagamesBusinessClient {

    private final AagamesProperties aagamesProps;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AagamesUserTokenResolver userTokenResolver;
    private final AagamesMerchantSupport merchantSupport;
    private final AagamesAuthClient aagamesAuthClient;

    private HttpClient httpClient;

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
                .build();
        }
        return httpClient;
    }

    // -------- 接口③：领取红包 --------

    /**
     * 调 aagames /external/redpacket/claim。
     *
     * @param userId        当前用户 box-im id（==j_users.id）
     * @param body          已组装好的请求 body Map（claimId / redpacketId / redpacketExternalId / groupId / userId / userName / userAvatar / extra ...）
     * @return 领取结果（含实际金额、剩余名额）
     * @throws GlobalException 业务码透传给上层（40901 已领完 / 40902 已领过 / 40903 资格不足 / 40904 余额异常 / 40006 token 失效）
     */
    public RedpacketClaimResultVO claim(Long userId, Map<String, Object> body) {
        String accessToken = requireUserToken(userId);
        body.put("accessToken", accessToken);

        String path = aagamesProps.getPaths().getRedpacketClaim();
        String json = JSON.toJSONString(body);
        JSONObject data = doRequest(userId, "POST", path, "", json, "redpacket-claim");

        RedpacketClaimResultVO vo = new RedpacketClaimResultVO();
        vo.setAmount(data.getBigDecimal("amount"));
        vo.setRemainCount(data.getInteger("remainCount"));
        vo.setClaimedAt(data.getLong("claimedAt"));
        vo.setBalance(data.getBigDecimal("balance"));
        return vo;
    }

    /**
     * 群成员发红包扣款或过期退回。direction=debit|credit，tradeNo 幂等。
     */
    public BigDecimal moveRedpacketFund(Long userId, String direction, BigDecimal amount, String tradeNo) {
        String accessToken = requireUserToken(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", accessToken);
        body.put("userId", String.valueOf(userId));
        body.put("direction", direction);
        body.put("amount", amount);
        body.put("tradeNo", tradeNo);
        String path = aagamesProps.getPaths().getRedpacketFund();
        JSONObject data = doRequest(userId, "POST", path, "", JSON.toJSONString(body), "redpacket-fund");
        return data.getBigDecimal("balance");
    }

    // -------- 接口④：领取详情 --------

    /**
     * 调 aagames /external/redpacket/{externalId}/records。
     */
    public RedpacketRecordsVO records(Long userId, String redpacketExternalId) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getRedpacketRecords();
        String path = pathTpl.replace("{externalId}", urlEncode(redpacketExternalId));
        String query = "accessToken=" + urlEncode(accessToken);

        JSONObject data = doRequest(userId, "GET", path, query, "", "redpacket-records");

        RedpacketRecordsVO vo = new RedpacketRecordsVO();
        vo.setRedpacketExternalId(data.getString("redpacketExternalId"));
        vo.setTotalAmount(data.getBigDecimal("totalAmount"));
        vo.setClaimedCount(data.getInteger("claimedCount"));

        List<RedpacketRecordsVO.Record> records = new ArrayList<>();
        JSONArray arr = data.getJSONArray("records");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                JSONObject r = arr.getJSONObject(i);
                RedpacketRecordsVO.Record rec = new RedpacketRecordsVO.Record();
                rec.setUserId(parseLongOrNull(r.getString("userId")));
                rec.setUserName(r.getString("userName"));
                rec.setUserAvatar(r.getString("userAvatar"));
                rec.setAmount(r.getBigDecimal("amount"));
                rec.setClaimedAt(r.getLong("claimedAt"));
                rec.setIsLuckiest(Boolean.TRUE.equals(r.getBoolean("isLuckiest")));
                records.add(rec);
            }
        }
        vo.setRecords(records);
        return vo;
    }

    // -------- 接口⑤：用户当日额度（v1.1 阶段2 桩，远端写死 888） --------

    /**
     * 调 aagames /external/user/{userId}/redpacket/quota。
     * 命中 Redis 缓存（默认 60 秒）则直接返回。
     */
    public UserDailyQuotaVO quota(Long userId, String date) {
        String cacheKey = RedisKey.IM_AAGAMES_USER_QUOTA + ":" + userId + ":" + (date == null ? "today" : date);
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof UserDailyQuotaVO) {
            return (UserDailyQuotaVO) cached;
        }
        if (cached != null) {
            redisTemplate.delete(cacheKey);
        }

        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getUserQuota();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        StringBuilder qs = new StringBuilder();
        qs.append("accessToken=").append(urlEncode(accessToken));
        if (date != null && !date.isEmpty()) {
            qs.append("&date=").append(urlEncode(date));
        }

        JSONObject data = doRequest(userId, "GET", path, qs.toString(), "", "redpacket-quota");

        UserDailyQuotaVO vo = new UserDailyQuotaVO();
        vo.setUserId(parseLongOrNull(data.getString("userId")));
        vo.setDate(data.getString("date"));
        vo.setDailyLimit(data.getBigDecimal("dailyLimit"));
        vo.setClaimedAmount(data.getBigDecimal("claimedAmount"));
        vo.setRemainAmount(data.getBigDecimal("remainAmount"));
        vo.setClaimedCount(data.getInteger("claimedCount"));
        vo.setCanClaim(Boolean.TRUE.equals(data.getBoolean("canClaim")));
        vo.setLimitFromMerchant(data.getString("limitFromMerchant"));
        vo.setRawPayload(data);

        int ttl = aagamesProps.getCache().getUserQuotaTtlSeconds();
        if (ttl > 0) {
            redisTemplate.opsForValue().set(cacheKey, vo, ttl, TimeUnit.SECONDS);
        }
        return vo;
    }

    /** 邀请好友推广汇总：注册人数、有效推广、下级列表。 */
    public JSONObject inviteSpread(Long userId) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getInviteSpread();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        String query = "accessToken=" + urlEncode(accessToken);
        return doRequest(userId, "GET", path, query, "", "invite-spread");
    }

    /** 佣金加倍 999 奖上奖资格与分档状态。 */
    public JSONObject commissionBonusStatus(Long userId) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getCommissionBonusStatus();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        String query = "accessToken=" + urlEncode(accessToken);
        return doRequest(userId, "GET", path, query, "", "commission-bonus-status");
    }

    /** 好友存款领赏金 4215 资格与分档状态。 */
    public JSONObject friendDepositBonusStatus(Long userId) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getFriendDepositBonusStatus();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        String query = "accessToken=" + urlEncode(accessToken);
        return doRequest(userId, "GET", path, query, "", "friend-deposit-bonus-status");
    }

    /** 体育周周领资格与档位状态。 */
    public JSONObject weeklySportsBonusStatus(Long userId, String weekStart) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getWeeklySportsBonusStatus();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        StringBuilder qs = new StringBuilder();
        qs.append("accessToken=").append(urlEncode(accessToken));
        if (weekStart != null && !weekStart.isBlank()) {
            qs.append("&week_start=").append(urlEncode(weekStart.trim()));
        }
        return doRequest(userId, "GET", path, qs.toString(), "", "weekly-sports-bonus-status");
    }

    /** 日投注闯关资格与档位状态。 */
    public JSONObject dailySportsBonusStatus(Long userId, String statDate) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getDailySportsBonusStatus();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        StringBuilder qs = new StringBuilder();
        qs.append("accessToken=").append(urlEncode(accessToken));
        if (statDate != null && !statDate.isBlank()) {
            qs.append("&stat_date=").append(urlEncode(statDate.trim()));
        }
        return doRequest(userId, "GET", path, qs.toString(), "", "daily-sports-bonus-status");
    }

    /** 体育连红挑战资格与连红进度。 */
    public JSONObject sportWinStreakStatus(Long userId) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getSportWinStreakStatus();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        String query = "accessToken=" + urlEncode(accessToken);
        return doRequest(userId, "GET", path, query, "", "sport-win-streak-status");
    }

    /** 体育连红挑战：自动过审并预占资格（不发彩金）。 */
    public JSONObject sportWinStreakApply(Long userId) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getSportWinStreakApply();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", accessToken);
        String json = JSON.toJSONString(body);
        return doRequest(userId, "POST", path, "", json, "sport-win-streak-apply");
    }

    /** 体育流水汇总（与体育活动 SportBetScope 统计口径一致）。 */
    public JSONObject sportTurnoverSummary(Long userId, String startDate, String endDate) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getSportTurnoverSummary();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        StringBuilder qs = new StringBuilder();
        qs.append("accessToken=").append(urlEncode(accessToken));
        if (startDate != null && !startDate.isBlank()) {
            qs.append("&start_date=").append(urlEncode(startDate.trim()));
        }
        if (endDate != null && !endDate.isBlank()) {
            qs.append("&end_date=").append(urlEncode(endDate.trim()));
        }
        return doRequest(userId, "GET", path, qs.toString(), "", "sport-turnover-summary");
    }

    /** 推广佣金钱包：待领 / 已领 / 累计。 */
    public JSONObject spreadCommissionStatus(Long userId) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getSpreadCommissionStatus();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        String query = "accessToken=" + urlEncode(accessToken);
        return doRequest(userId, "GET", path, query, "", "spread-commission-status");
    }

    /** 幸运排列五送彩票（132467）资格查询。 */
    public JSONObject lotteryGiftStatus(Long userId) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getLotteryGiftStatus();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        String query = "accessToken=" + urlEncode(accessToken);
        return doRequest(userId, "GET", path, query, "", "lottery-gift-status");
    }

    /** 幸运排列五送彩票（132467）领取额度并获取游戏 URL。 */
    public JSONObject lotteryGiftClaim(Long userId, String orderTradeNo) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getLotteryGiftClaim();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", accessToken);
        body.put("orderTradeNo", orderTradeNo);
        String json = JSON.toJSONString(body);
        return doRequest(userId, "POST", path, "", json, "lottery-gift-claim");
    }

    /** IM 客服：可参与活动总览（含 already_claimed / applied）。 */
    public JSONObject availableActivitiesOverview(Long userId) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getAvailableActivitiesOverview();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        String query = "accessToken=" + urlEncode(accessToken);
        return doRequest(userId, "GET", path, query, "", "available-activities-overview");
    }

    /** 游戏登录过期时按商户列活动名称，不查这个用户的领取进度。 */
    public JSONObject availableActivitiesListed(Long userId) {
        String merchantNo = merchantSupport.requireForUser(userId);
        String pathTpl = aagamesProps.getPaths().getAvailableActivitiesListed();
        String path = pathTpl.replace("{merchantNo}", urlEncode(merchantNo));
        return doRequest(userId, "GET", path, "", "", "available-activities-listed");
    }

    /** IM 申请活动红包后预占资格。 */
    public void reserveActivityBonus(Long userId, long activityId, String tradeNo, BigDecimal amount) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getActivityBonusReserve();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", accessToken);
        body.put("activityId", activityId);
        body.put("tradeNo", tradeNo);
        if (amount != null) {
            body.put("amount", amount);
        }
        String json = JSON.toJSONString(body);
        doRequest(userId, "POST", path, "", json, "activity-bonus-reserve");
    }

    /** 推广佣金明细分页。 */
    public JSONObject spreadCommissionLog(Long userId, Map<String, String> queryParams) {
        String accessToken = requireUserToken(userId);
        String pathTpl = aagamesProps.getPaths().getSpreadCommissionLog();
        String path = pathTpl.replace("{userId}", String.valueOf(userId));
        StringBuilder qs = new StringBuilder();
        qs.append("accessToken=").append(urlEncode(accessToken));
        if (queryParams != null) {
            for (Map.Entry<String, String> e : queryParams.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    qs.append("&").append(urlEncode(e.getKey())).append("=").append(urlEncode(e.getValue()));
                }
            }
        }
        return doRequest(userId, "GET", path, qs.toString(), "", "spread-commission-log");
    }

    // ---------- 内部方法 ----------

    private String requireUserToken(Long userId) {
        return userTokenResolver.resolveForBoxim(userId);
    }

    /**
     * 路径上的 im_user.id 可能和 token 所属的 j_users.id 不是同一个人。
     * aagames 会直接拒绝这种请求，客服就查不到活动。以 token 上的用户为准。
     */
    private Long tokenUserId(Long imUserId, String merchantNo, String query, String body) {
        String accessToken = extractAccessToken(query, body);
        if (accessToken == null || accessToken.isEmpty() || imUserId == null) {
            return imUserId;
        }
        try {
            var vo = aagamesAuthClient.validate(accessToken, merchantNo);
            if (vo != null && vo.getUserId() != null && vo.getUserId() > 0) {
                return vo.getUserId();
            }
        } catch (Exception e) {
            log.warn("aagames token user align skipped imUserId={} err={}", imUserId, e.getMessage());
        }
        return imUserId;
    }

    private static String extractAccessToken(String query, String body) {
        String fromQuery = queryParam(query, "accessToken");
        if (fromQuery != null) {
            return fromQuery;
        }
        fromQuery = queryParam(query, "access_token");
        if (fromQuery != null) {
            return fromQuery;
        }
        if (body != null && body.startsWith("{")) {
            JSONObject obj = JSON.parseObject(body);
            if (obj != null) {
                String token = obj.getString("accessToken");
                if (token == null || token.isEmpty()) {
                    token = obj.getString("access_token");
                }
                if (token != null && !token.isEmpty()) {
                    return token;
                }
            }
        }
        return null;
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty() || name == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (name.equals(part.substring(0, eq))) {
                String value = part.substring(eq + 1);
                return value.isEmpty() ? null : java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String replacePathUserId(String path, Long from, Long to) {
        if (path == null || from == null || to == null || from.equals(to)) {
            return path;
        }
        String marker = "/user/" + from;
        int at = path.indexOf(marker);
        if (at < 0) {
            return path;
        }
        int end = at + marker.length();
        if (end == path.length() || path.charAt(end) == '/' || path.charAt(end) == '?') {
            return path.substring(0, at) + "/user/" + to + path.substring(end);
        }
        return path;
    }

    private static String replaceBodyUserId(String body, Long from, Long to) {
        if (body == null || !body.startsWith("{") || from == null || to == null || from.equals(to)) {
            return body;
        }
        JSONObject obj = JSON.parseObject(body);
        if (obj == null || !obj.containsKey("userId")) {
            return body;
        }
        if (!String.valueOf(from).equals(String.valueOf(obj.get("userId")))) {
            return body;
        }
        obj.put("userId", to);
        return obj.toJSONString();
    }

    /**
     * 通用 HTTP 调用 + HMAC 签名 + 业务码统一转换。
     *
     * @param method     HTTP 方法
     * @param path       url path（不含 baseUrl，必须以 / 开头）
     * @param query      query string（不含问号）；空串表示无 query
     * @param body       请求体；空串表示无 body
     * @param scenarioTag 日志标签
     * @return aagames 业务侧 data 字段（永不为 null，失败抛异常）
     */
    private JSONObject doRequest(Long userId, String method, String path, String query, String body, String scenarioTag) {
        if (aagamesProps.getOutboundSecret() == null || aagamesProps.getOutboundSecret().isEmpty()) {
            throw new GlobalException(500, "aagames.outboundSecretMissing");
        }
        String merchantNo = merchantSupport.requireForUser(userId);
        Long tokenUserId = tokenUserId(userId, merchantNo, query, body);
        if (tokenUserId != null && !tokenUserId.equals(userId)) {
            log.warn("aagames {} align userId im={} token={}", scenarioTag, userId, tokenUserId);
            path = replacePathUserId(path, userId, tokenUserId);
            body = replaceBodyUserId(body, userId, tokenUserId);
        }
        if (query == null || query.isEmpty()) {
            query = "merchant_no=" + urlEncode(merchantNo);
        } else if (!query.contains("merchant_no=")) {
            query = query + "&merchant_no=" + urlEncode(merchantNo);
        }
        if (body != null && body.startsWith("{")) {
            JSONObject obj = JSON.parseObject(body);
            if (obj != null && !obj.containsKey("merchant_no")) {
                obj.put("merchant_no", merchantNo);
                body = obj.toJSONString();
            }
        }

        String pathWithQuery = (query == null || query.isEmpty()) ? path : (path + "?" + query);
        String url = aagamesProps.getBaseUrl() + pathWithQuery;
        log.info("aagames {} request {} {}", scenarioTag, method, redactSecrets(url));

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signString = BoximOutboundSigner.buildSignString(timestamp, nonce, method, pathWithQuery, body);
        String signature = BoximOutboundSigner.sign(signString, aagamesProps.getOutboundSecret());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(aagamesProps.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "box-im-platform/1.0")
            .header(BoximOutboundSigner.HEADER_TIMESTAMP, timestamp)
            .header(BoximOutboundSigner.HEADER_NONCE, nonce)
            .header(BoximOutboundSigner.HEADER_SIGNATURE, signature);

        switch (method.toUpperCase()) {
            case "GET":
                builder.GET();
                break;
            case "POST":
                builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                break;
            case "PUT":
                builder.PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                break;
            case "DELETE":
                builder.DELETE();
                break;
            default:
                throw new IllegalArgumentException("unsupported method " + method);
        }
        HttpRequest request = builder.build();

        HttpResponse<String> response;
        try {
            response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("aagames {} IO error url={}, err={}", scenarioTag, url, e.toString());
            throw new GlobalException(500, "aagames.unreachable");
        }

        int status = response.statusCode();
        String respBody = response.body();
        if (status == 401 || status == 403 || status == 409) {
            log.error("aagames {} auth-layer failed status={}, body={}", scenarioTag, status, respBody);
            throw new GlobalException(500, "aagames.systemAuthFailed");
        }
        if (status >= 500) {
            log.error("aagames {} 5xx status={}, body={}", scenarioTag, status, respBody);
            throw new GlobalException(500, "aagames.serverError");
        }
        if (status != 200) {
            log.error("aagames {} unexpected status={}, body={}", scenarioTag, status, respBody);
            throw new GlobalException(500, "aagames.unexpectedStatus");
        }

        JSONObject json;
        try {
            json = JSON.parseObject(respBody);
        } catch (Exception e) {
            log.error("aagames {} parse error body={}", scenarioTag, respBody);
            throw new GlobalException(500, "aagames.badResponse");
        }

        Integer code = json.getInteger("code");
        String message = json.getString("message");
        if (code == null) {
            throw new GlobalException(500, "aagames.badResponse");
        }
        if (code != 0) {
            log.warn("aagames {} biz-fail url={} code={} msg={}", scenarioTag, redactSecrets(url), code, message);
            // 40006 token 失效需要前端重新走 aagames 登录
            if (code == 40006) {
                throw new GlobalException(410, message != null ? message : "登录已失效，请重新登录");
            }
            // 红包域内业务码（409xx）原样抛
            throw new GlobalException(code, message != null ? message : "请求失败");
        }

        JSONObject data = json.getJSONObject("data");
        if (data == null) {
            data = new JSONObject();
        }
        log.info("aagames {} response url={} empty={} activityCount={} keys={}",
            scenarioTag, redactSecrets(url), data.isEmpty(), data.get("activity_count"), data.keySet());
        return data;
    }

    private static String redactSecrets(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.replaceAll("((?:accessToken|access_token)=)[^&]*", "$1***");
    }

    private static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** dummy import 抑制 IDE 警告（保持代码搬运的对称） */
    @SuppressWarnings("unused")
    private static BigDecimal _bd() {
        return BigDecimal.ZERO;
    }
}
