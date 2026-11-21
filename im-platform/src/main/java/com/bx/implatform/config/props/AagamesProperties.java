package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * aagames_api 对接配置（用户主权威源 + 红包/晒图业务）。
 * 详见《红包与晒图对接接口规范-v1.1》§6.1。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aagames")
public class AagamesProperties {

    /** aagames_api base url，例：http://127.0.0.1:8001 */
    private String baseUrl;

    /**
     * 用户域 baseUrl（routes/user_api.php 走这里，按 ApiSource 域名约束识别为 USER）。
     * 不配置则回退使用 baseUrl；本地 127.0.0.1 自动落到 USER 路由组，所以 dev 环境可不填。
     */
    private String userBaseUrl;

    /**
     * 职圈允许的商户号。每次调 aagames 必须带其中之一，不再使用单一默认商户。
     */
    private List<String> merchantNos = new ArrayList<>(List.of("10011", "10012"));

    /**
     * 注册接口必填 domain（aagames {@code User::register}）。
     * 不配则从当前 HTTP 请求的 Referer / Host 推导；本地开发建议显式配置。
     */
    private String registerDomain;

    /** 各接口路径 */
    private Paths paths = new Paths();

    /** 出站 secret：box-im 调 aagames 时 HMAC 签名用 */
    private String outboundSecret;

    /** HTTP 请求超时（毫秒） */
    private int requestTimeoutMs = 5000;

    /** Redis 缓存策略 */
    private Cache cache = new Cache();

    /** box-im 自有 JWT 过期时间上限（秒），不超过 jwt.accessToken.expireIn 也不超过 aagames token 剩余时长 */
    private int jwtCapSeconds = 1800;

    /** 7PG H5 主站 URL（IM 门户跳转 ?at=），例：https://h5.7pgb99.com/ */
    private String h5LoginUrl = "https://h5.7pgb99.com/";

    /** SSO 一次性 code TTL（秒），与 aagames BOXIM_SSO_CODE_TTL 对齐 */
    private int ssoCodeTtlSeconds = 120;

    @Data
    public static class Paths {
        /** 用户账密登录（aagames 端，nologin 路由组，无 HMAC） */
        private String userLogin = "/login";
        /** 用户注册（aagames 端，nologin 路由组；默认 /register，可按 aagames 实际路由覆盖） */
        private String userRegister = "/register";
        /** SSO code 兑换 access_token（box-im 服务端 HMAC 调 aagames） */
        private String ssoExchange = "/internal/sso/exchange";
        /** 登录时账号不存在，HMAC 补注册（跳过极验） */
        private String userProvision = "/internal/user/register";
        private String authValidate = "/internal/auth/validate";
        private String showImageNotify = "/external/show-image/notify";
        private String redpacketClaim = "/external/redpacket/claim";
        /** 群成员发红包扣款 / 过期退回 */
        private String redpacketFund = "/external/redpacket/fund";
        private String redpacketRecords = "/external/redpacket/{externalId}/records";
        private String userQuota = "/external/user/{userId}/redpacket/quota";
        private String inviteSpread = "/external/user/{userId}/invite-spread";
        private String commissionBonusStatus = "/external/user/{userId}/commission-bonus/status";
        private String friendDepositBonusStatus = "/external/user/{userId}/friend-deposit-bonus/status";
        private String weeklySportsBonusStatus = "/external/user/{userId}/weekly-sports-bonus/status";
        private String dailySportsBonusStatus = "/external/user/{userId}/daily-sports-bonus/status";
        private String sportWinStreakStatus = "/external/user/{userId}/sport-win-streak/status";
        private String sportWinStreakApply = "/external/user/{userId}/sport-win-streak/apply";
        private String sportTurnoverSummary = "/external/user/{userId}/sport-turnover/summary";
        private String spreadCommissionStatus = "/external/user/{userId}/spread-commission/status";
        private String spreadCommissionLog = "/external/user/{userId}/spread-commission/log";
        private String lotteryGiftStatus = "/external/user/{userId}/lottery-gift/status";
        private String lotteryGiftClaim = "/external/user/{userId}/lottery-gift/claim";
        private String availableActivitiesOverview = "/external/user/{userId}/available-activities/overview";
        /** 不带用户 token，只返回该商户正在进行的活动名称 */
        private String availableActivitiesListed = "/external/merchant/{merchantNo}/available-activities";
        private String activityBonusReserve = "/external/user/{userId}/activity-bonus/reserve";
        /** IM 移动端登录上报（任务中心） */
        private String imAppLogin = "/internal/user/{userId}/im-app-login";
        /** IM 已登录 → 签发 IM 专用 token（H5 user_api 直连） */
        private String imTokenIssue = "/internal/user/{userId}/im-token/issue";
    }

    @Data
    public static class Cache {
        private int authTokenTtlSeconds = 60;
        private int userQuotaTtlSeconds = 60;
    }
}
