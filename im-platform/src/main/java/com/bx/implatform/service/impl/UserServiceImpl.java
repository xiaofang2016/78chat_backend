package com.bx.implatform.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.servlet.JakartaServletUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bx.imclient.IMClient;
import com.bx.imcommon.model.IMSystemMessage;
import com.bx.imcommon.util.JwtUtil;
import com.bx.implatform.client.AagamesAuthClient;
import com.bx.implatform.client.SportAccountClient;
import com.bx.implatform.config.props.AagamesProperties;
import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.config.props.JwtProperties;
import com.bx.implatform.config.props.NotifyProperties;
import com.bx.implatform.config.props.RegistrationProperties;
import com.bx.implatform.contant.OfficialCsNames;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.dto.*;
import com.bx.implatform.entity.Friend;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.*;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.service.*;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.session.UserSession;
import com.bx.implatform.util.*;
import com.bx.implatform.vo.AagamesSsoExchangeVO;
import com.bx.implatform.vo.AagamesUserVO;
import com.bx.implatform.vo.LoginVO;
import com.bx.implatform.vo.SystemMessageVO;
import com.bx.implatform.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.Lists;
import org.apache.logging.log4j.util.Strings;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final GroupService groupService;
    private final GroupMemberService groupMemberService;
    private final UserBlacklistService userBlacklistService;
    private final FriendService friendService;
    private final JwtProperties jwtProps;
    private final NotifyProperties notifyProps;
    private final RegistrationProperties registrationProps;
    private final AagamesProperties aagamesProps;
    private final AagamesAuthClient aagamesAuthClient;
    private final SportAccountClient sportAccountClient;
    private final IMClient imClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CaptchaService captchaService;
    private final SensitiveFilterUtil sensitiveFilterUtil;
    private final HttpServletRequest request;
    private final AiCustomerServiceProperties aiCustomerServiceProperties;

    /** 访客用户 id 起点。与 aagames 自增 id、机器人 900001 段分开。 */
    private static final long GUEST_ID_BASE = 7_000_000_000L;
    /** 说球帝聊天用户 id 起点。和访客、吉祥、UED 的用户 id 分开。 */
    private static final long SPORT_ID_BASE = 8_000_000_000L;
    private static final String SPORT_MERCHANT = "sport";
    private static final int GUEST_CREATE_LIMIT_PER_HOUR = 30;

    /**
     * 本地账号登录已禁用。所有登录均通过外部 aagames_api access_token 走 {@link #loginExternal}。
     */
    @Deprecated
    @Override
    public LoginVO login(LoginDTO dto) {
        throw new GlobalException(410, "本地账号登录已停用，请通过 aagames 入口登录");
    }

    @Override
    public LoginVO loginImported(LoginDTO dto) {
        if (dto == null || StrUtil.isBlank(dto.getUserName()) || StrUtil.isBlank(dto.getMerchantNo())) {
            return null;
        }
        User user = this.getOne(Wrappers.<User>lambdaQuery()
            .eq(User::getMerchantNo, dto.getMerchantNo().trim())
            .eq(User::getUserName, dto.getUserName().trim())
            .last("limit 1"), false);
        if (user == null || user.getPassword() == null || !user.getPassword().matches("(?i)[0-9a-f]{32}")) {
            return null;
        }
        String digest = SecureUtil.md5(dto.getPassword() == null ? "" : dto.getPassword());
        if (!digest.equalsIgnoreCase(user.getPassword())) {
            throw new GlobalException(400, "用户名或密码错误");
        }
        if (Boolean.TRUE.equals(user.getIsBanned())) {
            throw new GlobalException("账号已被封禁");
        }
        if (UserStatus.UN_REG.getValue().equals(user.getStatus())) {
            throw new GlobalException("账号已注销");
        }

        String ip = JakartaServletUtil.getClientIP(request);
        user.setLastLoginIp(ip);
        user.setLastLoginTime(new Date());
        this.updateById(user);
        friendService.initDndCache(user.getId());
        groupService.initDndCache(user.getId());

        int accessTokenExpireIn = jwtProps.getAccessTokenExpireIn();
        UserSession session = BeanUtils.copyProperties(user, UserSession.class);
        session.setUserId(user.getId());
        session.setTerminal(dto.getTerminal());
        stampSession(session);
        String strJson = JSON.toJSONString(session);
        String accessToken = JwtUtil.sign(user.getId(), strJson, accessTokenExpireIn, jwtProps.getAccessTokenSecret());
        String refreshToken = JwtUtil.sign(user.getId(), strJson, jwtProps.getRefreshTokenExpireIn(),
            jwtProps.getRefreshTokenSecret());
        LoginVO vo = new LoginVO();
        vo.setAccessToken(accessToken);
        vo.setAccessTokenExpiresIn(accessTokenExpireIn);
        vo.setRefreshToken(refreshToken);
        vo.setRefreshTokenExpiresIn(jwtProps.getRefreshTokenExpireIn());
        log.info("导入用户登录成功 userId={} userName={} merchant={}", user.getId(), user.getUserName(), user.getMerchantNo());
        return vo;
    }

    @Override
    public LoginVO loginExternal(ExternalLoginDTO dto) {
        AagamesUserVO aaUser = aagamesAuthClient.validate(dto.getAccessToken(), dto.getMerchantNo());
        return completeExternalLogin(aaUser, dto.getAccessToken(), dto.getTerminal(), "external");
    }

    @Override
    public LoginVO loginExternalSso(AagamesSsoExchangeVO exchange, Integer terminal) {
        if (exchange == null || exchange.getUser() == null) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录响应异常");
        }
        if (StrUtil.isBlank(exchange.getImAccessToken())) {
            throw new GlobalException(ResultCode.PROGRAM_ERROR, "SSO 登录响应异常");
        }
        return completeExternalLogin(exchange.getUser(), exchange.getImAccessToken(), terminal, "sso");
    }

    @Override
    @Transactional
    public LoginVO loginGuest(GuestLoginDTO dto) {
        String merchantNo = dto.getMerchantNo() == null ? "" : dto.getMerchantNo().trim();
        boolean opened = aagamesProps.getMerchantNos() != null
            && aagamesProps.getMerchantNos().contains(merchantNo);
        if (merchantNo.isEmpty() || (!opened && OfficialCsNames.botIdForMerchant(merchantNo) == null)) {
            throw new GlobalException(400, "商户号无效，请选择已开通的商户");
        }
        String userName = "g" + merchantNo + SecureUtil.sha256(dto.getDeviceId().trim()).substring(0, 16);
        User user = this.findUserByUserName(userName);
        if (user == null) {
            user = createGuestUser(merchantNo, userName);
        } else if (!UserType.GUEST.getValue().equals(user.getType())) {
            throw new GlobalException(400, "客服暂时无法连接");
        }
        if (Boolean.TRUE.equals(user.getIsBanned())) {
            throw new GlobalException("账号已被封禁");
        }
        if (UserStatus.UN_REG.getValue().equals(user.getStatus())) {
            throw new GlobalException("账号已注销");
        }

        String ip = JakartaServletUtil.getClientIP(request);
        user.setLastLoginIp(ip);
        user.setLastLoginTime(new Date());
        this.updateById(user);
        friendService.initDndCache(user.getId());
        groupService.initDndCache(user.getId());
        log.info("访客登录成功 userId={} merchant={} terminal={}", user.getId(), merchantNo, dto.getTerminal());
        return issueLocalSession(user, dto.getTerminal());
    }

    @Override
    @Transactional
    public LoginVO loginSport(SportLoginDTO dto) {
        SportAccountClient.SportProfile profile = sportAccountClient.session(dto.getToken());
        String userName = "s" + profile.getUserId();
        User user = this.getOne(Wrappers.<User>lambdaQuery()
            .eq(User::getMerchantNo, SPORT_MERCHANT)
            .eq(User::getUserName, userName)
            .last("limit 1"), false);
        if (user == null) {
            user = createSportUser(profile, userName);
        } else if (!UserType.SPORT.getValue().equals(user.getType())) {
            throw new GlobalException(400, "sport.chatUnavailable");
        }
        if (Boolean.TRUE.equals(user.getIsBanned())) {
            throw new GlobalException("sport.banned");
        }
        if (UserStatus.UN_REG.getValue().equals(user.getStatus())) {
            throw new GlobalException("sport.cancelled");
        }
        if (overwriteSportNick(user.getNickName(), user.getPhone(), profile.getNickname(), profile.getPhone())) {
            user.setNickName(profile.getNickname());
        }
        user.setPhone(profile.getPhone());
        user.setHeadImage(profile.getAvatar());
        user.setHeadImageThumb(profile.getAvatar());
        user.setLastLoginIp(JakartaServletUtil.getClientIP(request));
        user.setLastLoginTime(new Date());
        this.updateById(user);
        friendService.initDndCache(user.getId());
        groupService.initDndCache(user.getId());
        log.info("说球帝登录成功 userId={} sportUser={}", user.getId(), profile.getUserId());
        Integer terminal = dto.getTerminal() == null ? 0 : dto.getTerminal();
        return issueLocalSession(user, terminal);
    }

    /**
     * 只在职圈昵称还是空的、或仍是打码手机号时，才用说球帝昵称覆盖。
     * 用户在职圈改过的昵称，重新登录不再被盖回去。
     *
     * @param current 职圈当前昵称
     * @param oldPhone 职圈当前手机号
     * @param incoming 说球帝昵称
     * @param newPhone 说球帝手机号
     * @return 是否用说球帝昵称覆盖
     */
    private static boolean overwriteSportNick(String current, String oldPhone, String incoming, String newPhone) {
        if (current == null || current.isBlank()) {
            return true;
        }
        String incomingMask = maskPhone(newPhone);
        String oldMask = maskPhone(oldPhone);
        // 已是自定义昵称，保留。
        if (!current.equals(incomingMask) && !current.equals(oldMask)) {
            return false;
        }
        return incoming != null && !incoming.isBlank();
    }

    /**
     * 大陆手机号打码，和说球帝默认昵称同一规则。
     *
     * @param phone 手机号
     * @return 打码结果，格式不对时为空串
     */
    private static String maskPhone(String phone) {
        if (phone == null || !phone.matches("1[3-9]\\d{9}")) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private User createSportUser(SportAccountClient.SportProfile profile, String userName) {
        User user = new User();
        user.setId(nextSportId());
        user.setUserName(userName);
        user.setNickName(profile.getNickname());
        user.setPhone(profile.getPhone());
        user.setHeadImage(profile.getAvatar());
        user.setHeadImageThumb(profile.getAvatar());
        user.setMerchantNo(SPORT_MERCHANT);
        user.setPassword("__SPORT__");
        user.setSex(0);
        user.setIsBanned(false);
        user.setIsManualApprove(false);
        user.setStatus(UserStatus.NORMAL.getValue());
        user.setType(UserType.SPORT.getValue());
        user.setCreatedTime(new Date());
        try {
            this.save(user);
        } catch (DuplicateKeyException ex) {
            User existing = this.getOne(Wrappers.<User>lambdaQuery()
                .eq(User::getMerchantNo, SPORT_MERCHANT)
                .eq(User::getUserName, userName)
                .last("limit 1"), false);
            if (existing != null) {
                return existing;
            }
            throw ex;
        }
        log.info("说球帝用户首次落库 userId={} sportUser={}", user.getId(), profile.getUserId());
        return user;
    }

    private User createGuestUser(String merchantNo, String userName) {
        String ip = JakartaServletUtil.getClientIP(request);
        String rateKey = "im:guest:create:" + (StrUtil.isBlank(ip) ? "unknown" : ip);
        Long created = redisTemplate.opsForValue().increment(rateKey);
        if (created != null && created == 1L) {
            redisTemplate.expire(rateKey, 1, TimeUnit.HOURS);
        }
        if (created != null && created > GUEST_CREATE_LIMIT_PER_HOUR) {
            throw new GlobalException(400, "访问过于频繁，请稍后再试");
        }

        User user = new User();
        user.setId(nextGuestId());
        user.setUserName(userName);
        user.setNickName("访客");
        user.setHeadImage("");
        user.setHeadImageThumb("");
        user.setMerchantNo(merchantNo);
        user.setPassword("__GUEST__");
        user.setSex(0);
        user.setIsBanned(false);
        user.setIsManualApprove(false);
        user.setStatus(UserStatus.NORMAL.getValue());
        user.setType(UserType.GUEST.getValue());
        user.setCreatedTime(new Date());
        try {
            this.save(user);
        } catch (DuplicateKeyException ex) {
            User existing = this.findUserByUserName(userName);
            if (existing != null) {
                return existing;
            }
            throw ex;
        }
        log.info("访客首次落库 userId={} userName={} merchant={}", user.getId(), userName, merchantNo);
        return user;
    }

    private long nextGuestId() {
        Long seq = redisTemplate.opsForValue().increment("im:guest:id:seq");
        if (seq == null || seq < 1) {
            throw new GlobalException(500, "客服暂时无法连接");
        }
        long id = GUEST_ID_BASE + seq;
        for (int i = 0; i < 5 && this.getById(id) != null; i++) {
            seq = redisTemplate.opsForValue().increment("im:guest:id:seq");
            if (seq == null) {
                throw new GlobalException(500, "客服暂时无法连接");
            }
            id = GUEST_ID_BASE + seq;
        }
        if (this.getById(id) != null) {
            throw new GlobalException(500, "客服暂时无法连接");
        }
        return id;
    }

    private long nextSportId() {
        Long seq = redisTemplate.opsForValue().increment("im:sport:id:seq");
        if (seq == null || seq < 1) {
            throw new GlobalException(500, "聊天暂时无法登录");
        }
        long id = SPORT_ID_BASE + seq;
        for (int i = 0; i < 5 && this.getById(id) != null; i++) {
            seq = redisTemplate.opsForValue().increment("im:sport:id:seq");
            if (seq == null) {
                throw new GlobalException(500, "聊天暂时无法登录");
            }
            id = SPORT_ID_BASE + seq;
        }
        if (this.getById(id) != null) {
            throw new GlobalException(500, "聊天暂时无法登录");
        }
        return id;
    }

    private void stampSession(UserSession session) {
        session.setSessionEpoch(jwtProps.getSessionEpoch());
    }

    private LoginVO issueLocalSession(User user, Integer terminal) {
        int accessTokenExpireIn = jwtProps.getAccessTokenExpireIn();
        UserSession session = BeanUtils.copyProperties(user, UserSession.class);
        session.setUserId(user.getId());
        session.setTerminal(terminal);
        stampSession(session);
        String strJson = JSON.toJSONString(session);
        String accessToken = JwtUtil.sign(user.getId(), strJson, accessTokenExpireIn, jwtProps.getAccessTokenSecret());
        String refreshToken = JwtUtil.sign(user.getId(), strJson, jwtProps.getRefreshTokenExpireIn(),
            jwtProps.getRefreshTokenSecret());
        LoginVO vo = new LoginVO();
        vo.setAccessToken(accessToken);
        vo.setAccessTokenExpiresIn(accessTokenExpireIn);
        vo.setRefreshToken(refreshToken);
        vo.setRefreshTokenExpiresIn(jwtProps.getRefreshTokenExpireIn());
        return vo;
    }

    /**
     * 外部 / SSO 登录共用：同步 im_user、缓存 aagames token、签发 box-im JWT。
     *
     * @param aagamesTokenForCache IM 专用 token（SSO）或游戏 access_token（/login/external）
     */
    private LoginVO completeExternalLogin(AagamesUserVO aaUser, String aagamesTokenForCache, Integer terminal,
                                          String loginChannel) {
        User user = upsertFromAagames(aaUser);

        String ip = JakartaServletUtil.getClientIP(request);
        user.setLastLoginIp(ip);
        user.setLastLoginTime(new Date());
        this.updateById(user);

        friendService.initDndCache(user.getId());
        groupService.initDndCache(user.getId());

        int accessTokenExpireIn = computeAccessTokenExpireIn(aaUser);

        String aagamesTokenKey = RedisKey.IM_AAGAMES_USER_TOKEN + ":" + user.getId();
        redisTemplate.opsForValue().set(aagamesTokenKey, aagamesTokenForCache,
            accessTokenExpireIn, TimeUnit.SECONDS);

        long lt = computeAagamesTokenLongLifeSeconds(aaUser);
        if (lt > 0) {
            String aagamesTokenLtKey = RedisKey.IM_AAGAMES_USER_TOKEN_LT + ":" + user.getId();
            redisTemplate.opsForValue().set(aagamesTokenLtKey, aagamesTokenForCache, lt, TimeUnit.SECONDS);
        }

        UserSession session = BeanUtils.copyProperties(user, UserSession.class);
        session.setUserId(user.getId());
        session.setTerminal(terminal);
        stampSession(session);
        String strJson = JSON.toJSONString(session);
        String accessToken = JwtUtil.sign(user.getId(), strJson, accessTokenExpireIn, jwtProps.getAccessTokenSecret());
        String refreshToken = JwtUtil.sign(user.getId(), strJson, jwtProps.getRefreshTokenExpireIn(),
            jwtProps.getRefreshTokenSecret());

        LoginVO vo = new LoginVO();
        vo.setAccessToken(accessToken);
        vo.setAccessTokenExpiresIn(accessTokenExpireIn);
        vo.setRefreshToken(refreshToken);
        vo.setRefreshTokenExpiresIn(jwtProps.getRefreshTokenExpireIn());
        log.info("{}登录成功 userId={} userName={} terminal={}", loginChannel, user.getId(), user.getUserName(), terminal);
        aagamesAuthClient.recordImAppLoginFromRequest(user.getId(), request);
        return vo;
    }

    /**
     * 把 aagames 返回的用户落库到 im_user。只按 j_users.id 匹配。
     * 同一用户名在 10011 和 10012 是两个账号，不能把这次登录的 token 挂到另一条 im_user 上。
     */
    private User upsertFromAagames(AagamesUserVO aaUser) {
        String merchantNo = aaUser.getMerchantNo();
        if (merchantNo == null || merchantNo.isBlank()
            || aagamesProps.getMerchantNos() == null
            || !aagamesProps.getMerchantNos().contains(merchantNo.trim())) {
            throw new GlobalException(400, "商户号无效，请选择已开通的商户");
        }
        merchantNo = merchantNo.trim();
        aaUser.setMerchantNo(merchantNo);
        if (aaUser.getUserId() == null || aaUser.getUserId() <= 0) {
            throw new GlobalException(500, "登录服务响应异常");
        }

        User user = this.getById(aaUser.getUserId());
        boolean isNew = (user == null);
        if (isNew) {
            user = newAagamesUser(aaUser, merchantNo);
            releaseSameMerchantUserName(aaUser.getUserName(), merchantNo, aaUser.getUserId());
            try {
                this.save(user);
            } catch (DuplicateKeyException ex) {
                user.setUserName(merchantQualifiedUserName(aaUser.getUserName(), merchantNo));
                this.save(user);
            }
            log.info("外部用户首次落库 userId={} userName={} merchant={}", user.getId(), user.getUserName(), merchantNo);
        } else {
            // 同步昵称/头像更新（若 aagames 端有变化）；用户名不可变
            boolean dirty = false;
            String newNick = StrUtil.isEmpty(aaUser.getNickName()) ? aaUser.getUserName() : aaUser.getNickName();
            if (!Objects.equals(user.getNickName(), newNick)) {
                user.setNickName(newNick);
                dirty = true;
            }
            if (!StrUtil.isEmpty(aaUser.getUserAvatar())
                && !Objects.equals(user.getHeadImage(), aaUser.getUserAvatar())) {
                user.setHeadImage(aaUser.getUserAvatar());
                user.setHeadImageThumb(aaUser.getUserAvatar());
                dirty = true;
            }
            if (!Objects.equals(user.getMerchantNo(), merchantNo)) {
                user.setMerchantNo(merchantNo);
                dirty = true;
            }
            if (UserStatus.UN_REG.getValue().equals(user.getStatus())) {
                // aagames 端已 active，自动解除注销态
                user.setStatus(UserStatus.NORMAL.getValue());
                dirty = true;
            }
            if (dirty) {
                this.updateById(user);
            }
        }
        return user;
    }

    private User newAagamesUser(AagamesUserVO aaUser, String merchantNo) {
        User user = new User();
        user.setId(aaUser.getUserId());
        user.setUserName(aaUser.getUserName());
        user.setNickName(StrUtil.isEmpty(aaUser.getNickName()) ? aaUser.getUserName() : aaUser.getNickName());
        user.setHeadImage(StrUtil.isEmpty(aaUser.getUserAvatar()) ? "" : aaUser.getUserAvatar());
        user.setHeadImageThumb(user.getHeadImage());
        user.setMerchantNo(merchantNo);
        user.setPassword("__EXTERNAL_AUTH__");
        user.setSex(0);
        user.setIsBanned(false);
        user.setIsManualApprove(false);
        user.setStatus(UserStatus.NORMAL.getValue());
        user.setType(UserType.NORMAL.getValue());
        user.setCreatedTime(new Date());
        return user;
    }

    /**
     * 同商户下用户名已被另一条 im_user 占用时，给旧行改名。
     * 否则 10011 这次登录插不进真正的 j_users.id，token 会继续挂在错误 id 上。
     */
    private void releaseSameMerchantUserName(String userName, String merchantNo, Long keepId) {
        if (StrUtil.isBlank(userName) || keepId == null) {
            return;
        }
        List<User> occupied = this.list(Wrappers.<User>lambdaQuery()
            .eq(User::getUserName, userName)
            .ne(User::getId, keepId)
            .and(w -> w.eq(User::getMerchantNo, merchantNo)
                .or().isNull(User::getMerchantNo)
                .or().eq(User::getMerchantNo, "")));
        for (User row : occupied) {
            String renamed = userName + "#" + row.getId();
            log.warn("用户名被其他账号占用，已让出 userName={} oldId={} merchant={} keepId={}",
                userName, row.getId(), row.getMerchantNo(), keepId);
            row.setUserName(renamed.length() <= 255 ? renamed : renamed.substring(0, 255));
            this.updateById(row);
        }
    }

    private static String merchantQualifiedUserName(String userName, String merchantNo) {
        String base = StrUtil.blankToDefault(userName, "user");
        String qualified = base + "@" + merchantNo;
        return qualified.length() <= 255 ? qualified : qualified.substring(0, 255);
    }

    private int computeAccessTokenExpireIn(AagamesUserVO aaUser) {
        int cfg = jwtProps.getAccessTokenExpireIn();
        int cap = aagamesProps.getJwtCapSeconds() > 0 ? aagamesProps.getJwtCapSeconds() : cfg;
        int base = Math.min(cfg, cap);
        Long expireAt = aaUser.getTokenExpireAt();
        if (expireAt == null || expireAt <= 0) {
            return base;
        }
        long remainMs = expireAt - System.currentTimeMillis();
        if (remainMs <= 0) {
            // 边界：刚好过期，前端用一次后就需要重登
            return Math.min(base, 60);
        }
        int remainSec = (int) Math.min(Integer.MAX_VALUE, remainMs / 1000);
        return Math.min(base, remainSec);
    }

    /**
     * 计算"AI 客服专用 aagames token 长寿命缓存"的 TTL（秒）：
     * 优先用 aagames 颁发的 token 自身剩余时间；缺失时给 24 小时保守值（vs aagames 服务异常情况）。
     * 不受 jwtCapSeconds / box-im JWT 过期时间约束。
     */
    private long computeAagamesTokenLongLifeSeconds(AagamesUserVO aaUser) {
        Long expireAt = aaUser == null ? null : aaUser.getTokenExpireAt();
        if (expireAt == null || expireAt <= 0) {
            return 24 * 60 * 60L; // 兜底 24 小时
        }
        long remainMs = expireAt - System.currentTimeMillis();
        if (remainMs <= 60_000L) {
            // token 已过期或即将过期：不要写新 key，避免脏数据
            return 0L;
        }
        // 给 30 秒安全边距，避免缓存比 token 实际过期晚一点
        return Math.max(60L, (remainMs - 30_000L) / 1000L);
    }

    @Override
    public LoginVO refreshToken(String refreshToken) {
        //验证 token
        if (!JwtUtil.checkSign(refreshToken, jwtProps.getRefreshTokenSecret())) {
            throw new GlobalException("user.loginExpired");
        }
        String strJson = JwtUtil.getInfo(refreshToken);
        UserSession current = JSON.parseObject(strJson, UserSession.class);
        if (current == null || !Objects.equals(current.getSessionEpoch(), jwtProps.getSessionEpoch())) {
            throw new GlobalException("user.loginExpired");
        }
        Long userId = JwtUtil.getUserId(refreshToken);
        User user = this.getById(userId);
        if (Objects.isNull(user)) {
            log.warn("用户不存在,id:{}", userId);
            throw new GlobalException("user.notFound");
        }
        // 检查是否已经被封禁
        checkIsBan(user);
        // 更新用户登录ip、时间
        String ip = JakartaServletUtil.getClientIP(request);
        user.setLastLoginIp(ip);
        user.setLastLoginTime(new Date());
        this.updateById(user);
        String accessToken =
            JwtUtil.sign(userId, strJson, jwtProps.getAccessTokenExpireIn(), jwtProps.getAccessTokenSecret());
        String newRefreshToken =
            JwtUtil.sign(userId, strJson, jwtProps.getRefreshTokenExpireIn(), jwtProps.getRefreshTokenSecret());
        LoginVO vo = new LoginVO();
        vo.setAccessToken(accessToken);
        vo.setAccessTokenExpiresIn(jwtProps.getAccessTokenExpireIn());
        vo.setRefreshToken(newRefreshToken);
        vo.setRefreshTokenExpiresIn(jwtProps.getRefreshTokenExpireIn());
        return vo;
    }

    @Override
    public void register(RegisterDTO dto) {
        // 演示环境仅在晚上8:00-8:30开放注册
        LocalTime now = LocalTime.now();
        Boolean notTimeWindow =  now.isAfter(LocalTime.of(20, 0)) && now.isBefore(LocalTime.of(20, 30));
        if (redisTemplate.hasKey(RedisKey.IM_REG_DISABLE) && !notTimeWindow) {
            throw new GlobalException("注册通道暂时关闭");
        }
        // 昵称默认和用户名保持一致
        if (StrUtil.isEmpty(dto.getNickName())) {
            dto.setNickName(dto.getUserName());
        }
        User user = this.findUserByUserName(dto.getUserName());
        if (!Objects.isNull(user)) {
            throw new GlobalException(ResultCode.USERNAME_ALREADY_REGISTER);
        }
        // 校验用户名
        if (RegexUtil.isPhone(dto.getUserName()) || RegexUtil.isEmail(dto.getUserName())) {
            throw new GlobalException("user.usernameInvalid");
        }
        if (!dto.getUserName().equals(sensitiveFilterUtil.filter(dto.getUserName()))) {
            throw new GlobalException("user.usernameSensitive");
        }
        if (!dto.getNickName().equals(sensitiveFilterUtil.filter(dto.getNickName()))) {
            throw new GlobalException("user.nicknameSensitive");
        }
        user = new User();
        // 手机、验证码校验
        if (RegisterMode.PHONE.getCode().equals(dto.getMode())) {
            if (!RegexUtil.isPhone(dto.getPhone())) {
                throw new GlobalException("user.phoneInvalid");
            }
            if (isExistPhone(dto.getPhone())) {
                throw new GlobalException("user.phoneRegistered");
            }
            if (!captchaService.vertify(CaptchaType.SMS, dto.getPhone(), dto.getCode())) {
                throw new GlobalException("user.captchaError");
            }
            user.setPhone(dto.getPhone());
        }
        // 邮箱、验证码校验
        if (RegisterMode.EMAIL.getCode().equals(dto.getMode())) {
            if (!RegexUtil.isEmail(dto.getEmail())) {
                throw new GlobalException("user.emailInvalid");
            }
            if (isExistEmail(dto.getEmail())) {
                throw new GlobalException("user.emailRegistered");
            }
            if (!captchaService.vertify(CaptchaType.MAIL, dto.getEmail(), dto.getCode())) {
                throw new GlobalException("user.captchaError");
            }
            user.setEmail(dto.getEmail());
        }
        // 保存用户信息
        String ip = JakartaServletUtil.getClientIP(request);
        if (StrUtil.isEmpty(ip)) {
            throw new GlobalException("user.ipAbnormal");
        }
        // IP注册限制检查
        checkIpRegisterLimit(ip);
        user.setLastLoginIp(ip);
        user.setLastLoginTime(new Date());
        user.setUserName(dto.getUserName());
        user.setNickName(dto.getNickName());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        this.save(user);
        log.info("注册用户，用户id:{},用户名:{},昵称:{},IP:{}", user.getId(), dto.getUserName(), dto.getNickName(), ip);
    }

    @Transactional
    @Override
    public void unregister() {
        UserSession session = SessionContext.getSession();
        // 修改用户状态
        User user = this.getById(session.getUserId());
        if (user.getType().equals(UserType.OPEN_ACCOUNT.getValue())) {
            throw new GlobalException("user.demoNoDeregister");
        }
        LambdaUpdateWrapper<User> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(User::getId, user.getId());
        wrapper.set(User::getCid, Strings.EMPTY);
        wrapper.set(User::getStatus, UserStatus.UN_REG.getValue());
        // 释放手机号和邮箱，否则会无法重新注册
        wrapper.set(User::getPhone, null);
        wrapper.set(User::getEmail, null);
        this.update(wrapper);
        // 清理redis中的cid
        String key1 = StrUtil.join(":", RedisKey.IM_USER_CID, user.getId());
        redisTemplate.delete(key1);
        // 推送消息让用户下线
        SystemMessageVO msgInfo = new SystemMessageVO();
        msgInfo.setType(MessageType.USER_UNREG.code());
        msgInfo.setSendTime(new Date());
        IMSystemMessage<SystemMessageVO> sendMessage = new IMSystemMessage<>();
        sendMessage.setRecvIds(Collections.singletonList(session.getUserId()));
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        imClient.sendSystemMessage(sendMessage);
    }

    @Override
    public void modifyPassword(ModifyPwdDTO dto) {
        UserSession session = SessionContext.getSession();
        User user = this.getById(session.getUserId());
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new GlobalException("user.oldPasswordError");
        }
        if (user.getType().equals(UserType.OPEN_ACCOUNT.getValue())) {
            throw new GlobalException("user.demoNoModifyPwd");
        }
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        this.updateById(user);
        log.info("用户修改密码，用户id:{},用户名:{},昵称:{}", user.getId(), user.getUserName(), user.getNickName());
    }

    @Override
    public void resetPassword(ResetPwdDTO dto) {
        if (RegisterMode.PHONE.getCode().equals(dto.getMode())) {
            // 手机验证码校验
            resetPwdByPhone(dto.getPhone(), dto.getCode(), dto.getPassword());
        } else if (RegisterMode.EMAIL.getCode().equals(dto.getMode())) {
            // 邮箱验证码校验
            resetPwdByEmail(dto.getEmail(), dto.getCode(), dto.getPassword());
        } else {
            throw new GlobalException("user.resetPwdNotSupport");
        }
    }

    private void resetPwdByPhone(String phone, String code, String password) {
        // 手机验证码校验
        if (!captchaService.vertify(CaptchaType.SMS, phone, code)) {
            throw new GlobalException("user.captchaError");
        }
        User user = findUserByPhone(phone);
        if (Objects.isNull(user)) {
            throw new GlobalException("user.phoneNotRegistered");
        }
        user.setPassword(passwordEncoder.encode(password));
        this.updateById(user);
        log.info("通过手机重置用户密码，用户id:{},用户名:{}", user.getId(), user.getUserName());
    }

    private void resetPwdByEmail(String email, String code, String password) {
        // 手机验证码校验
        if (!captchaService.vertify(CaptchaType.MAIL, email, code)) {
            throw new GlobalException("user.captchaError");
        }
        User user = findUserByEmail(email);
        if (Objects.isNull(user)) {
            throw new GlobalException("user.emailNotRegistered");
        }
        user.setPassword(passwordEncoder.encode(password));
        this.updateById(user);
        log.info("通过邮箱用户密码，用户id:{},用户名:{}", user.getId(), user.getUserName());
    }

    @Override
    public User findUserByUserName(String username) {
        LambdaQueryWrapper<User> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(User::getUserName, username);
        return this.getOne(queryWrapper);
    }

    @Override
    public User findUserByPhone(String phone) {
        LambdaQueryWrapper<User> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(User::getPhone, phone);
        return this.getOne(queryWrapper);
    }

    @Override
    public User findUserByEmail(String email) {
        LambdaQueryWrapper<User> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(User::getEmail, email);
        return this.getOne(queryWrapper);
    }

    @Override
    public User findUserByLoginName(String loginName) {
        // 优先用户名登陆
        User user = findUserByUserName(loginName);
        // 手机号登陆
        if (Objects.isNull(user) && RegexUtil.isPhone(loginName)) {
            user = findUserByPhone(loginName);
        }
        // 邮箱登陆
        if (Objects.isNull(user) && RegexUtil.isEmail(loginName)) {
            user = findUserByEmail(loginName);
        }
        return user;
    }

    @Override
    public Boolean isExistPhone(String phone) {
        LambdaQueryWrapper<User> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(User::getPhone, phone);
        return this.exists(queryWrapper);
    }

    @Override
    public Boolean isExistEmail(String email) {
        LambdaQueryWrapper<User> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(User::getEmail, email);
        return this.exists(queryWrapper);
    }

    @Transactional
    @Override
    public void update(UserVO vo) {
        UserSession session = SessionContext.getSession();
        if (!vo.getNickName().equals(sensitiveFilterUtil.filter(vo.getNickName()))) {
            throw new GlobalException("user.nicknameSensitive");
        }
        if (!session.getUserId().equals(vo.getId())) {
            throw new GlobalException("user.noModifyOther");
        }
        User user = this.getById(vo.getId());
        if (Objects.isNull(user)) {
            log.warn("用户不存在,id:{}", vo.getId());
            throw new GlobalException("user.notFound");
        }
        if (user.getType().equals(UserType.OPEN_ACCOUNT.getValue())) {
            throw new GlobalException("user.demoNoModifyProfile");
        }
        if (!user.getNickName().equals(vo.getNickName()) || !user.getHeadImageThumb().equals(vo.getHeadImageThumb())) {
            // 更新好友昵称和头像
            LambdaUpdateWrapper<Friend> wrapper1 = Wrappers.lambdaUpdate();
            wrapper1.eq(Friend::getFriendId, session.getUserId());
            wrapper1.set(Friend::getFriendNickName, vo.getNickName());
            wrapper1.set(Friend::getFriendHeadImage, vo.getHeadImageThumb());
            wrapper1.set(Friend::getVersion, friendService.getNextVersion());
            friendService.update(wrapper1);
            // 更新群聊中的昵称和头像
            LambdaUpdateWrapper<GroupMember> wrapper2 = Wrappers.lambdaUpdate();
            wrapper2.eq(GroupMember::getUserId, session.getUserId());
            wrapper2.set(GroupMember::getHeadImage, vo.getHeadImageThumb());
            wrapper2.set(GroupMember::getUserNickName, vo.getNickName());
            wrapper2.set(GroupMember::getVersion,groupMemberService.getNextVersion());
            groupMemberService.update(wrapper2);
        }
        // 更新用户信息
        user.setNickName(vo.getNickName());
        user.setSex(vo.getSex());
        user.setSignature(vo.getSignature());
        user.setHeadImage(vo.getHeadImage());
        user.setHeadImageThumb(vo.getHeadImageThumb());
        this.updateById(user);
        log.info("用户信息更新，用户:{}}", user);
    }

    @Override
    public UserVO findUserById(Long id) {
        UserSession session = SessionContext.getSession();
        if (KnowledgeIngestBotVisibility.hideIngestBotFromViewer(aiCustomerServiceProperties, session.getUserId(),
            id)) {
            log.warn("[kb-bot] deny user.profile id:{} viewer:{}", id, session.getUserId());
            throw new GlobalException("user.notFound");
        }
        User user = this.getById(id);
        if (Objects.isNull(user)) {
            log.warn("用户不存在,id:{}", id);
            throw new GlobalException("user.notFound");
        }
        UserVO vo = convert(user);
        String official = OfficialCsNames.displayName(id);
        if (official != null) {
            vo.setNickName(official);
        }
        vo.setOnline(imClient.isOnline(id));
        vo.setIsInBlacklist(userBlacklistService.isInBlacklist(session.getUserId(), id));
        return vo;
    }

    @Override
    public UserVO findSelfInfo() {
        UserSession session = SessionContext.getSession();
        User user = this.getById(session.getUserId());
        UserVO vo = BeanUtils.copyProperties(user, UserVO.class);
        vo.setIsAudioTip((user.getAudioTip() & 1 << session.getTerminal()) > 0);
        return vo;
    }

    @Override
    public List<UserVO> findUserByName(String name) {
        LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(User::getIsBanned, false);
        wrapper.eq(User::getStatus, UserStatus.NORMAL.getValue());
        wrapper.and(wp -> wp.like(User::getUserName, name).or().like(User::getNickName, name));
        wrapper.last("limit 20");
        List<User> users = this.list(wrapper);
        users = stripInvisibleKbBotUsers(users);
        return convert(users);
    }

    /** 非管理员检索结果中不包含「AI知识库」录入机器人账号 */
    private List<User> stripInvisibleKbBotUsers(List<User> users) {
        if (users == null || users.isEmpty()) {
            return users;
        }
        UserSession session = SessionContext.getSession();
        Long viewerId = session == null ? null : session.getUserId();
        return users.stream()
            .filter(u -> !KnowledgeIngestBotVisibility.hideIngestBotFromViewer(aiCustomerServiceProperties, viewerId,
                u.getId()))
            .collect(Collectors.toList());
    }

    @Override
    public List<UserVO> search(String name) {
        String keyword = name == null ? "" : name.trim();
        UserSession session = SessionContext.getSession();
        Long viewerId = session == null ? null : session.getUserId();
        if (RegexUtil.isPhone(keyword)) {
            User user = findUserByPhone(keyword);
            if (!Objects.isNull(user)) {
                if (KnowledgeIngestBotVisibility.hideIngestBotFromViewer(aiCustomerServiceProperties, viewerId,
                    user.getId())) {
                    return Lists.newArrayList();
                }
                return convert(List.of(user));
            }
        } else if (RegexUtil.isEmail(keyword)) {
            User user = findUserByEmail(keyword);
            if (!Objects.isNull(user)) {
                if (KnowledgeIngestBotVisibility.hideIngestBotFromViewer(aiCustomerServiceProperties, viewerId,
                    user.getId())) {
                    return Lists.newArrayList();
                }
                return convert(List.of(user));
            }
        } else {
            List<UserVO> found = new ArrayList<>(findUserByName(keyword));
            User byId = findSearchableByExactId(keyword);
            if (byId != null && found.stream().noneMatch(vo -> byId.getId().equals(vo.getId()))
                && !KnowledgeIngestBotVisibility.hideIngestBotFromViewer(aiCustomerServiceProperties, viewerId,
                    byId.getId())) {
                found.addAll(0, convert(List.of(byId)));
            }
            return found;
        }
        return Lists.newArrayList();
    }

    /**
     * 纯数字按聊天用户 id 精确查。说球帝账号的 id 从 8000000000 起，用户名是内部的 s 加 32 位编号，页面上搜不到。
     *
     * @param keyword 搜索词
     * @return 未封禁且未注销的用户，对不上时为空
     */
    private User findSearchableByExactId(String keyword) {
        Long id = exactUserId(keyword);
        if (id == null) {
            return null;
        }
        User user = this.getById(id);
        if (user == null || Boolean.TRUE.equals(user.getIsBanned())) {
            return null;
        }
        if (!UserStatus.NORMAL.getValue().equals(user.getStatus())) {
            return null;
        }
        return user;
    }

    /**
     * 1 到 18 位、不以 0 开头的数字才是用户 id。手机号由调用方先按手机号查，不进这里。
     *
     * @param keyword 搜索词
     * @return 用户 id，格式不对时为空
     */
    static Long exactUserId(String keyword) {
        if (keyword == null || !keyword.matches("[1-9]\\d{0,17}")) {
            return null;
        }
        try {
            return Long.valueOf(keyword);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Transactional
    @Override
    public void reportCid(String cid) {
        UserSession session = SessionContext.getSession();
        // 清理该设备以前登录过的cid
        LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(User::getCid, cid);
        wrapper.ne(User::getId, session.getUserId());
        List<User> users = this.list(wrapper);
        users.forEach(user -> {
            // 清理redis中的cid
            String key1 = StrUtil.join(":", RedisKey.IM_USER_CID, user.getId());
            redisTemplate.delete(key1);
            // 清空通知会话信息
            String key2 = StrUtil.join(":", RedisKey.IM_NOTIFY_OFFLINE_SESSION, user.getId());
            redisTemplate.delete(key2);
            user.setCid(Strings.EMPTY);
            this.updateById(user);
        });
        // 保存当前用户的cid
        User user = this.getById(session.getUserId());
        user.setCid(cid);
        this.updateById(user);
        // 缓存cid到redis
        String key = StrUtil.join(":", RedisKey.IM_USER_CID, user.getId());
        redisTemplate.opsForValue().set(key, cid, notifyProps.getActiveDays(), TimeUnit.DAYS);
        // 清空通知会话信息
        String key2 = StrUtil.join(":", RedisKey.IM_NOTIFY_OFFLINE_SESSION, user.getId());
        redisTemplate.delete(key2);
    }

    @Transactional
    @Override
    public void removeCid() {
        UserSession session = SessionContext.getSession();
        User user = this.getById(session.getUserId());
        user.setCid(Strings.EMPTY);
        this.updateById(user);
        // 清理redis中的cid
        String key1 = StrUtil.join(":", RedisKey.IM_USER_CID, user.getId());
        redisTemplate.delete(key1);
    }

    @Override
    public void setManualApprove(Boolean enabled) {
        UserSession session = SessionContext.getSession();
        LambdaUpdateWrapper<User> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(User::getId, session.getUserId());
        wrapper.set(User::getIsManualApprove, enabled);
        this.update(wrapper);
    }

    @Transactional
    @Override
    public void setAudioTip(Boolean enabled) {
        UserSession session = SessionContext.getSession();
        User user = this.getById(session.getUserId());
        if (enabled) {
            int mask = 1 << session.getTerminal();
            user.setAudioTip(user.getAudioTip() | mask);
        } else {
            int mask = ~(1 << session.getTerminal());
            user.setAudioTip(user.getAudioTip() & mask);
        }
        this.updateById(user);
    }

    @Transactional
    @Override
    public void bindPhone(BindPhoneDTO dto) {
        UserSession session = SessionContext.getSession();
        User user = getById(session.getUserId());
        if (StrUtil.isNotEmpty(user.getPhone())) {
            throw new GlobalException("user.phoneAlreadyBound");
        }
        if (!RegexUtil.isPhone(dto.getPhone())) {
            throw new GlobalException("user.phoneInvalid");
        }
        if (isExistPhone(dto.getPhone())) {
            throw new GlobalException("user.phoneRegistered");
        }
        if (!captchaService.vertify(CaptchaType.SMS, dto.getPhone(), dto.getCode())) {
            throw new GlobalException("user.captchaError");
        }
        user.setPhone(dto.getPhone());
        this.updateById(user);
    }

    @Transactional
    @Override
    public void bindEmail(BindEmailDTO dto) {
        UserSession session = SessionContext.getSession();
        User user = getById(session.getUserId());
        if (StrUtil.isNotEmpty(user.getEmail())) {
            throw new GlobalException("user.emailAlreadyBound");
        }
        if (!RegexUtil.isEmail(dto.getEmail())) {
            throw new GlobalException("user.emailInvalid");
        }
        if (isExistEmail(dto.getEmail())) {
            throw new GlobalException("user.emailRegistered");
        }
        if (!captchaService.vertify(CaptchaType.MAIL, dto.getEmail(), dto.getCode())) {
            throw new GlobalException("user.captchaError");
        }
        user.setEmail(dto.getEmail());
        this.updateById(user);
    }

    @Override
    public void updateAuthStatus(RealnameAuthStatus status) {
        UserSession session = SessionContext.getSession();
        LambdaUpdateWrapper<User> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(User::getId, session.getUserId());
        wrapper.set(User::getAuthStatus, status.getCode());
        this.update(wrapper);
    }

    @Override
    public void setLocale(String locale) {
        UserSession session = SessionContext.getSession();
        String key = String.join(":", RedisKey.IM_USER_LOCALE, session.getUserId().toString(),
            session.getTerminal().toString());
        redisTemplate.opsForValue().set(key,locale.toString(),60,TimeUnit.DAYS);
    }

    List<UserVO> convert(List<User> users) {
        List<Long> userIds = users.stream().map(User::getId).collect(Collectors.toList());
        List<Long> onlineUserIds = imClient.getOnlineUser(userIds);
        return users.stream().map(u -> {
            UserVO vo = convert(u);
            vo.setOnline(onlineUserIds.contains(u.getId()));
            return vo;
        }).collect(Collectors.toList());
    }

    private UserVO convert(User user) {
        UserVO vo = BeanUtils.copyProperties(user, UserVO.class);
        // 邮箱和手机号做脱敏处理
        vo.setPhone(DesensitizeUtil.desensitizePhone(vo.getPhone()));
        vo.setEmail(DesensitizeUtil.desensitizeEmail(vo.getEmail()));
        return vo;
    }

    private void checkIsBan(User user) {
        if (user.getIsBanned()) {
            String tip = String.format("您的账号因'%s'被封禁,", user.getReason());
            if (Objects.isNull(user.getUnbanTime())) {
                tip += "封禁时长: 永久";
            } else {
                tip += "恢复时间: " + DateTimeUtils.getFormatDate(user.getUnbanTime(), DateTimeUtils.FULL_DATE_FORMAT);
            }
            throw new GlobalException(tip);
        }
    }

    private void checkIpRegisterLimit(String ip) {
        RegistrationProperties.IpLimit ipLimit = registrationProps.getIpLimit();
        if (!ipLimit.getEnabled() || isLocalAddress(ip)) {
            return;
        }
        LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(User::getLastLoginIp, ip);
        wrapper.eq(User::getStatus, UserStatus.NORMAL.getValue());
        if (this.count(wrapper) >= ipLimit.getMaxCount()) {
            log.warn("IP注册限制：IP {} 已达到注册上限 {} 个用户", ip, ipLimit.getMaxCount());
            throw new GlobalException("user.registerLimit", ipLimit.getMaxCount());
        }
    }

    private Boolean isLocalAddress(String ip) {
        return ip.equals("127.0.0.1") || ip.equals("localhost");
    }
}
