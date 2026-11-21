package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.client.SportGroupRedpackClient;
import com.bx.implatform.dto.RedpacketSendDTO;
import com.bx.implatform.entity.Group;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.entity.Redpacket;
import com.bx.implatform.entity.RedpacketRecord;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.UserType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.RedpacketMapper;
import com.bx.implatform.mapper.RedpacketRecordMapper;
import com.bx.implatform.service.GroupMemberService;
import com.bx.implatform.service.GroupMessageService;
import com.bx.implatform.service.GroupService;
import com.bx.implatform.service.InviteFriendsBonusService;
import com.bx.implatform.service.RedpacketBotService;
import com.bx.implatform.service.UserRedpacketService;
import com.bx.implatform.service.UserService;
import com.bx.implatform.service.ZhiquanChatStore;
import com.bx.implatform.vo.GroupVO;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.session.UserSession;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.external.RedpacketRecordsVO;
import com.bx.implatform.vo.external.UserDailyQuotaVO;
import com.bx.implatform.vo.redpacket.RedpacketClaimResultVO;
import com.bx.implatform.vo.redpacket.RedpacketDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * UserRedpacketService 实现：
 *   1. claim：先在 box-im 落 pending 流水（uk redpacket_id+user_id 防同人重领），再调 aagames 接口③
 *   2. records：调 aagames 接口④ + box-im im_redpacket 主表合并
 *   3. quota：透传 aagames 接口⑤（v1.1 阶段2 写死 888）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRedpacketServiceImpl implements UserRedpacketService {

    /** aagames 业务码 */
    private static final int AA_CODE_SOLD_OUT = 40901;
    private static final int AA_CODE_ALREADY_CLAIMED = 40902;
    private static final int AA_CODE_NOT_ELIGIBLE = 40903;
    private static final int AA_CODE_BALANCE_FAIL = 40904;

    /** 本地流水状态 */
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_FAILED = 2;

    /** redpacket 主表状态 */
    private static final int RP_STATUS_CLAIMING = 0;
    private static final int RP_STATUS_FINISHED = 1;
    private static final int RP_STATUS_EXPIRED = 2;

    private final RedpacketMapper redpacketMapper;
    private final RedpacketRecordMapper redpacketRecordMapper;
    private final GroupMemberService groupMemberService;
    private final GroupService groupService;
    private final UserService userService;
    private final RedpacketBotService redpacketBotService;
    private final AagamesBusinessClient aagamesBusinessClient;
    private final InviteFriendsBonusService inviteFriendsBonusService;
    private final SportGroupRedpackClient sportGroupRedpack;
    private final ZhiquanChatStore zhiquanChatStore;
    private final GroupMessageService groupMessageService;

    private static final BigDecimal MAX_PACKET_AMOUNT = new BigDecimal("200");
    private static final BigDecimal MAX_PACKET_TOTAL = new BigDecimal("2000");
    private static final int MEMBER_EXPIRE_MINUTES = 1440;

    // ============ send ============

    @Override
    public GroupMessageVO send(Long userId, RedpacketSendDTO dto) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new GlobalException(400, "用户不存在");
        }
        if (UserType.GUEST.getValue().equals(user.getType())) {
            throw new GlobalException(400, "访客账号不能发红包");
        }
        if (user.getMerchantNo() == null || user.getMerchantNo().isBlank()) {
            throw new GlobalException(4001, "sport.bindJixiang");
        }

        boolean shared = zhiquanChatStore.contains(dto.getGroupId());
        Group stored = groupService.getById(dto.getGroupId());
        Group group;
        if (shared && (stored == null || Boolean.TRUE.equals(stored.getDissolve()))) {
            GroupVO sharedGroup = zhiquanChatStore.group(dto.getGroupId());
            group = new Group();
            group.setId(dto.getGroupId());
            group.setName(sharedGroup == null || sharedGroup.getName() == null ? "群聊" : sharedGroup.getName());
            group.setOwnerId(sharedGroup == null ? null : sharedGroup.getOwnerId());
            group.setIsAllMuted(false);
            group.setIsBanned(false);
        } else {
            group = groupService.getAndCheckById(dto.getGroupId());
        }
        if (Boolean.TRUE.equals(group.getIsBanned())) {
            throw new GlobalException(400, "群已被封禁");
        }
        boolean isOwner = group.getOwnerId() != null && userId.equals(group.getOwnerId());
        GroupMember member = stored == null ? null : groupMemberService.findByGroupAndUserId(dto.getGroupId(), userId);
        if (stored != null && isOwner && (member == null || Boolean.TRUE.equals(member.getQuit()))) {
            groupService.ensureLiveGroupOwnerMember(group.getId(), userId, null);
            member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), userId);
        }
        if (Boolean.TRUE.equals(group.getIsAllMuted()) && !isOwner
            && (member == null || !Boolean.TRUE.equals(member.getIsManager()))) {
            throw new GlobalException("groupMsg.muteAll");
        }
        if ((member == null || Boolean.TRUE.equals(member.getQuit())) && !shared) {
            throw new GlobalException("groupMsg.notInGroup");
        }
        if (member != null && Boolean.TRUE.equals(member.getIsMuted())) {
            throw new GlobalException("groupMsg.selfMuted");
        }

        BigDecimal amount = dto.getAmount().setScale(2, RoundingMode.HALF_UP);
        int count = dto.getTotalCount();
        if (amount.compareTo(new BigDecimal("0.01")) < 0 || amount.compareTo(MAX_PACKET_AMOUNT) > 0) {
            throw new GlobalException(400, "单个金额需在 0.01 到 200 之间");
        }
        if (count < 1 || count > 100) {
            throw new GlobalException(400, "个数需在 1 到 100 之间");
        }
        BigDecimal total = amount.multiply(BigDecimal.valueOf(count)).setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(MAX_PACKET_TOTAL) > 0) {
            throw new GlobalException(400, "红包总额最多 2000");
        }

        String nick = member == null ? "" : member.getShowNickName();
        if (nick == null || nick.isBlank()) {
            nick = user.getNickName() == null ? "" : user.getNickName();
        }
        String externalId = "m" + userId + "-" + UUID.randomUUID().toString().replace("-", "");
        JSONObject extra = new JSONObject();
        extra.put("source", "member");
        extra.put("senderUserId", userId);
        extra.put("senderName", nick);
        extra.put("merchantNo", user.getMerchantNo());

        String debitTradeNo = "rp-send-" + externalId;
        if ("sport".equals(user.getMerchantNo())) {
            sportGroupRedpack.debit(user, total, debitTradeNo);
        } else {
            aagamesBusinessClient.moveRedpacketFund(userId, "debit", total, debitTradeNo);
        }

        Redpacket rp = new Redpacket();
        rp.setExternalId(externalId);
        rp.setGroupId(group.getId());
        rp.setBotUserId(userId);
        rp.setAmount(amount);
        rp.setTotalCount(count);
        rp.setClaimedCount(0);
        String title = dto.getTitle() == null ? "" : dto.getTitle().trim();
        rp.setTitle(title.isEmpty() ? "恭喜发财，大吉大利" : title);
        rp.setEligibilityRule("any");
        rp.setExtra(extra.toJSONString());
        rp.setStatus(RP_STATUS_CLAIMING);
        long now = System.currentTimeMillis();
        rp.setExpireAt(new Date(now + MEMBER_EXPIRE_MINUTES * 60_000L));
        rp.setCreatedAt(new Date(now));
        rp.setUpdatedAt(new Date(now));
        redpacketMapper.insert(rp);

        UserSession session = SessionContext.getSession();
        Integer terminal = session == null ? null : session.getTerminal();
        try {
            GroupMessageVO msg = redpacketBotService.dispatchRedpacket(group, rp, userId, nick, terminal);
            rp.setMessageId(msg.getId());
            rp.setUpdatedAt(new Date());
            redpacketMapper.updateById(rp);
            groupMessageService.publishToShared(msg);
            return msg;
        } catch (RuntimeException e) {
            refundMemberRemainder(rp);
            rp.setStatus(3);
            rp.setUpdatedAt(new Date());
            redpacketMapper.updateById(rp);
            log.error("[user-redpacket] send card failed redpacketId={} err={}", rp.getId(), e.toString());
            throw new GlobalException(500, "红包发送失败，款项已退回");
        }
    }

    // ============ claim ============

    @Override
    public RedpacketClaimResultVO claim(Long userId, Long redpacketId) {
        Redpacket rp = requireRedpacket(redpacketId);

        // 1. 领取资格（群成员 / 私聊指定用户）
        ensureCanClaim(userId, rp);

        // 2. 状态/过期校验
        if (Objects.equals(rp.getStatus(), RP_STATUS_FINISHED)) {
            return failResult(rp, "sold_out", "红包已被领完");
        }
        if (Objects.equals(rp.getStatus(), RP_STATUS_EXPIRED) || isExpired(rp)) {
            // 顺手把状态置为 expired（首次发现时），并刷新卡片
            if (!Objects.equals(rp.getStatus(), RP_STATUS_EXPIRED)) {
                markExpired(rp);
            }
            return failResult(rp, "expired", "红包已过期");
        }

        // 3. 已领幂等（邀请彩礼若金额与档位不符，视为假成功并重试入账）
        RedpacketRecord existing = redpacketRecordMapper.selectOne(new LambdaQueryWrapper<RedpacketRecord>()
            .eq(RedpacketRecord::getRedpacketId, rp.getId())
            .eq(RedpacketRecord::getUserId, userId));
        if (existing != null && Objects.equals(existing.getStatus(), STATUS_SUCCESS)) {
            if (!isInvitePhantomSuccess(rp, existing, userId)) {
                return successResult(rp, existing.getAmount(), existing.getClaimedAt(),
                    Math.max(0, rp.getTotalCount() - rp.getClaimedCount()), "already_claimed");
            }
            log.warn("[user-redpacket] retry phantom invite claim redpacketId={} userId={} recordedAmount={}",
                rp.getId(), userId, existing.getAmount());
            existing.setStatus(STATUS_PENDING);
            existing.setAmount(BigDecimal.ZERO);
            existing.setFailReason("");
            redpacketRecordMapper.updateById(existing);
        }

        // 4. 落 pending 流水（依赖 (redpacket_id, user_id) 唯一键防并发同人重领）
        User claimer = userService.getById(userId);
        if (claimer != null && "sport".equals(claimer.getMerchantNo()) && !sportPacket(rp)) {
            throw new GlobalException(40003, "只能领取同一商户的红包");
        }
        String claimId = existing != null ? existing.getClaimId() : "claim-" + UUID.randomUUID();
        if (existing == null) {
            RedpacketRecord pending = new RedpacketRecord();
            pending.setRedpacketId(rp.getId());
            pending.setExternalId(rp.getExternalId());
            pending.setClaimId(claimId);
            pending.setUserId(userId);
            User u = userService.getById(userId);
            pending.setUserName(u == null ? "" : u.getNickName());
            pending.setUserAvatar(u == null ? "" : u.getHeadImage());
            pending.setAmount(BigDecimal.ZERO);
            pending.setStatus(STATUS_PENDING);
            pending.setCreatedAt(new Date());
            try {
                redpacketRecordMapper.insert(pending);
                existing = pending;
            } catch (DuplicateKeyException e) {
                // 并发：另一线程已抢到 → 回到幂等分支
                existing = redpacketRecordMapper.selectOne(new LambdaQueryWrapper<RedpacketRecord>()
                    .eq(RedpacketRecord::getRedpacketId, rp.getId())
                    .eq(RedpacketRecord::getUserId, userId));
                if (existing == null) {
                    throw new GlobalException(500, "redpacket.claim.race");
                }
                if (Objects.equals(existing.getStatus(), STATUS_SUCCESS)) {
                    if (!isInvitePhantomSuccess(rp, existing, userId)) {
                        return successResult(rp, existing.getAmount(), existing.getClaimedAt(),
                            Math.max(0, rp.getTotalCount() - rp.getClaimedCount()), "already_claimed");
                    }
                    log.warn("[user-redpacket] retry phantom invite claim (race) redpacketId={} userId={}",
                        rp.getId(), userId);
                    existing.setStatus(STATUS_PENDING);
                    existing.setAmount(BigDecimal.ZERO);
                    existing.setFailReason("");
                    redpacketRecordMapper.updateById(existing);
                }
                claimId = existing.getClaimId();
            }
        }

        // 5. 调 aagames 接口③。入账商户是当前登录用户自己的 10011/10012，不另记职圈本地余额。
        Map<String, Object> body = new HashMap<>();
        body.put("claimId", claimId);
        body.put("redpacketId", String.valueOf(rp.getId()));
        body.put("redpacketExternalId", rp.getExternalId());
        body.put("groupId", String.valueOf(rp.getGroupId() == null ? 0 : rp.getGroupId()));
        body.put("userId", String.valueOf(userId));
        body.put("userName", existing.getUserName());
        body.put("userAvatar", existing.getUserAvatar());
        // v1.2: 透传单包金额，aagames 凭此真实入账（钱包+流水+benefitlist）
        // 邀请好友彩礼：以 aagames 当前档位金额为准，避免红包主表残留旧档位金额（如 100→5）。
        BigDecimal perAmount = resolveClaimAmount(rp, userId);
        if (sportPacket(rp)) {
            sportGroupRedpack.credit(claimer, perAmount, claimId, "群红包");
            return finishLocalClaim(rp, existing, perAmount);
        }
        body.put("amount", perAmount);
        body.put("title", rp.getTitle() == null ? "" : rp.getTitle());
        Map<String, Object> extra = new HashMap<>();
        extra.put("totalCountHint", rp.getTotalCount());
        JSONObject rpExtra = parseExtraJson(rp.getExtra());
        if (rpExtra != null) {
            if (rpExtra.getLong("activityId") != null) {
                body.put("activityId", rpExtra.getLong("activityId"));
            }
            if (rpExtra.getString("transactionId") != null && !rpExtra.getString("transactionId").isEmpty()) {
                extra.put("transactionId", rpExtra.getString("transactionId"));
            }
            if (rpExtra.getInteger("flowMultiplier") != null && rpExtra.getInteger("flowMultiplier") > 0) {
                extra.put("flowMultiplier", rpExtra.getInteger("flowMultiplier"));
            }
        }
        body.put("extra", extra);

        com.bx.implatform.vo.external.RedpacketClaimResultVO remote;
        try {
            remote = aagamesBusinessClient.claim(userId, body);
        } catch (GlobalException ge) {
            return handleAagamesFailure(rp, existing, ge);
        }

        // 6. 写回流水成功 + 主表 claimed_count
        existing.setStatus(STATUS_SUCCESS);
        BigDecimal credited = remote.getAmount() == null ? BigDecimal.ZERO
            : remote.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (perAmount.compareTo(BigDecimal.ZERO) > 0 && credited.compareTo(perAmount) > 0) {
            log.warn("[user-redpacket] cap remote claim amount redpacketId={} remote={} perAmount={}",
                rp.getId(), credited, perAmount);
            credited = perAmount;
        }
        existing.setAmount(credited);
        existing.setClaimedAt(remote.getClaimedAt() == null ? new Date() : new Date(remote.getClaimedAt()));
        existing.setFailReason("");
        redpacketRecordMapper.updateById(existing);

        int newClaimed = (rp.getClaimedCount() == null ? 0 : rp.getClaimedCount()) + 1;
        rp.setClaimedCount(newClaimed);
        rp.setUpdatedAt(new Date());
        boolean finished = newClaimed >= rp.getTotalCount();
        if (finished) {
            rp.setStatus(RP_STATUS_FINISHED);
        }
        redpacketMapper.updateById(rp);

        // 7. 领完 → 重发卡片刷新状态
        if (finished) {
            tryRepublish(rp);
        }
        noteSharedClaim(rp, existing);

        Long claimedAtMs = existing.getClaimedAt() == null ? null : existing.getClaimedAt().getTime();
        Integer remainCount = remote.getRemainCount() != null
            ? remote.getRemainCount() : Math.max(0, rp.getTotalCount() - newClaimed);
        return successResult(rp, existing.getAmount(), claimedAtMs, remainCount, "success");
    }

    private RedpacketClaimResultVO handleAagamesFailure(Redpacket rp, RedpacketRecord pending, GlobalException ge) {
        String reason = ge.getMessage() == null ? "请求失败" : ge.getMessage();
        pending.setStatus(STATUS_FAILED);
        pending.setFailReason(reason);
        redpacketRecordMapper.updateById(pending);

        switch (ge.getCode()) {
            case AA_CODE_SOLD_OUT:
                if (!Objects.equals(rp.getStatus(), RP_STATUS_FINISHED)) {
                    rp.setStatus(RP_STATUS_FINISHED);
                    rp.setUpdatedAt(new Date());
                    redpacketMapper.updateById(rp);
                    tryRepublish(rp);
                }
                return failResult(rp, "sold_out", reason);
            case AA_CODE_ALREADY_CLAIMED:
                return failResult(rp, "already_claimed", reason);
            case AA_CODE_NOT_ELIGIBLE:
                return failResult(rp, "not_eligible", reason);
            case AA_CODE_BALANCE_FAIL:
                return failResult(rp, "failed", reason);
            case 410:
                throw ge; // 让前端去重新登录
            default:
                log.error("[user-redpacket] aagames claim unexpected code={} msg={}", ge.getCode(), reason);
                return failResult(rp, "failed", reason);
        }
    }

    private RedpacketClaimResultVO successResult(Redpacket rp, BigDecimal amount, Object claimedAt,
                                                 int remainCount, String result) {
        RedpacketClaimResultVO vo = new RedpacketClaimResultVO();
        vo.setRedpacketId(rp.getId());
        vo.setExternalId(rp.getExternalId());
        vo.setResult(result);
        vo.setAmount(amount == null ? BigDecimal.ZERO : amount);
        vo.setRemainCount(remainCount);
        if (claimedAt instanceof Long) {
            vo.setClaimedAt((Long) claimedAt);
        } else if (claimedAt instanceof Date) {
            vo.setClaimedAt(((Date) claimedAt).getTime());
        }
        return vo;
    }

    private RedpacketClaimResultVO failResult(Redpacket rp, String result, String reason) {
        RedpacketClaimResultVO vo = new RedpacketClaimResultVO();
        vo.setRedpacketId(rp.getId());
        vo.setExternalId(rp.getExternalId());
        vo.setResult(result);
        vo.setRemainCount(Math.max(0, rp.getTotalCount() - (rp.getClaimedCount() == null ? 0 : rp.getClaimedCount())));
        vo.setFailReason(reason);
        return vo;
    }

    // ============ records ============

    @Override
    public RedpacketDetailVO records(Long userId, Long redpacketId) {
        Redpacket rp = requireRedpacket(redpacketId);
        if (!isPrivateRedpacket(rp)) {
            ensureGroupMember(userId, rp.getGroupId());
        } else {
            ensurePrivateRecipient(userId, rp);
        }

        if (sportPacket(rp)) {
            return toDetail(redpacketId, localRecords(rp));
        }
        RedpacketRecordsVO remote;
        try {
            remote = aagamesBusinessClient.records(userId, rp.getExternalId());
        } catch (GlobalException ge) {
            log.warn("[user-redpacket] records aagames failed code={} msg={}", ge.getCode(), ge.getMessage());
            // aagames 不可达时，降级用本地流水构造一个空详情
            remote = new RedpacketRecordsVO();
            remote.setRedpacketExternalId(rp.getExternalId());
            remote.setTotalAmount(BigDecimal.ZERO);
            remote.setClaimedCount(0);
            remote.setRecords(new ArrayList<>());
        }
        return toDetail(redpacketId, remote);
    }

    @Override
    public RedpacketDetailVO toDetail(Long redpacketId, RedpacketRecordsVO remote) {
        Redpacket rp = requireRedpacket(redpacketId);
        Long currentUserId = SessionContext.getSession() != null ? SessionContext.getSession().getUserId() : null;

        RedpacketDetailVO vo = new RedpacketDetailVO();
        vo.setRedpacketId(rp.getId());
        vo.setExternalId(rp.getExternalId());
        vo.setGroupId(rp.getGroupId());
        vo.setTitle(rp.getTitle());
        vo.setAmount(rp.getAmount());
        vo.setTotalCount(rp.getTotalCount());
        vo.setClaimedCount(rp.getClaimedCount());
        vo.setClaimedAmount(remote != null && remote.getTotalAmount() != null ? remote.getTotalAmount() : BigDecimal.ZERO);
        vo.setStatus(toStatusString(rp.getStatus()));
        vo.setExpireAt(rp.getExpireAt() != null ? rp.getExpireAt().getTime() : null);
        vo.setCreatedAt(rp.getCreatedAt() != null ? rp.getCreatedAt().getTime() : null);

        List<RedpacketDetailVO.Item> items = new ArrayList<>();
        boolean myClaimed = false;
        BigDecimal myAmount = null;
        if (remote != null && remote.getRecords() != null) {
            for (RedpacketRecordsVO.Record r : remote.getRecords()) {
                RedpacketDetailVO.Item item = new RedpacketDetailVO.Item();
                item.setUserId(r.getUserId());
                item.setUserName(r.getUserName());
                item.setUserAvatar(r.getUserAvatar());
                item.setAmount(r.getAmount());
                item.setClaimedAt(r.getClaimedAt());
                item.setIsLuckiest(r.getIsLuckiest());
                items.add(item);
                if (currentUserId != null && currentUserId.equals(r.getUserId())) {
                    myClaimed = true;
                    myAmount = r.getAmount();
                }
            }
        }
        vo.setItems(items);
        vo.setMyClaimed(myClaimed);
        vo.setMyAmount(myAmount);
        return vo;
    }

    // ============ quota ============

    @Override
    public UserDailyQuotaVO quota(Long userId, String date) {
        User user = userService.getById(userId);
        if (user != null && "sport".equals(user.getMerchantNo())) {
            UserDailyQuotaVO vo = new UserDailyQuotaVO();
            vo.setUserId(userId);
            vo.setDate(date);
            vo.setDailyLimit(MAX_PACKET_TOTAL);
            vo.setClaimedAmount(BigDecimal.ZERO);
            vo.setRemainAmount(MAX_PACKET_TOTAL);
            vo.setClaimedCount(0);
            vo.setCanClaim(true);
            vo.setLimitFromMerchant("sport");
            return vo;
        }
        return aagamesBusinessClient.quota(userId, date);
    }

    // ============ helpers ============

    private Redpacket requireRedpacket(Long redpacketId) {
        if (redpacketId == null) {
            throw new GlobalException(40004, "redpacketId 不能为空");
        }
        Redpacket rp = redpacketMapper.selectById(redpacketId);
        if (rp == null) {
            throw new GlobalException(40004, "红包不存在");
        }
        return rp;
    }

    private boolean isPrivateRedpacket(Redpacket rp) {
        if (rp.getGroupId() != null && rp.getGroupId() > 0) {
            return false;
        }
        JSONObject ex = parseExtraJson(rp.getExtra());
        return ex != null && "private".equalsIgnoreCase(ex.getString("scope"));
    }

    private void ensureCanClaim(Long userId, Redpacket rp) {
        if (isPrivateRedpacket(rp)) {
            ensurePrivateRecipient(userId, rp);
        } else {
            ensureGroupMember(userId, rp.getGroupId());
        }
        ensureSameMerchant(userId, rp);
    }

    private void ensurePrivateRecipient(Long userId, Redpacket rp) {
        JSONObject ex = parseExtraJson(rp.getExtra());
        Long target = ex == null ? null : ex.getLong("targetUserId");
        if (target == null || !target.equals(userId)) {
            throw new GlobalException(40003, "该红包不是发给您的");
        }
    }

    private static JSONObject parseExtraJson(String extra) {
        if (extra == null || extra.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(extra);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 邀请好友彩礼：以 aagames quota 档位金额为准；quota 不可用时退回红包主表金额。
     */
    private BigDecimal resolveClaimAmount(Redpacket rp, Long userId) {
        BigDecimal stored = rp.getAmount() == null ? BigDecimal.ZERO
            : rp.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (!"invite_friends_bonus".equals(rp.getEligibilityRule())) {
            return stored;
        }
        JSONObject rpExtra = parseExtraJson(rp.getExtra());
        if (rpExtra == null) {
            return stored;
        }
        Integer level = rpExtra.getInteger("level");
        if (level == null || level <= 0) {
            return stored;
        }
        try {
            BigDecimal tierBonus = lookupInviteTierBonus(userId, level);
            if (tierBonus != null && tierBonus.compareTo(BigDecimal.ZERO) > 0) {
                if (tierBonus.compareTo(stored) != 0) {
                    log.warn("[user-redpacket] invite tier bonus mismatch redpacketId={} level={} stored={} quotaBonus={} — use quota",
                        rp.getId(), level, stored, tierBonus);
                }
                return tierBonus;
            }
        } catch (Exception e) {
            log.warn("[user-redpacket] invite tier bonus verify failed redpacketId={} err={}",
                rp.getId(), e.toString());
        }
        return stored;
    }

    /** aagames 曾按全局 trade_no 误复用他人账变时，本地 status=1 但金额与档位不符。 */
    private boolean isInvitePhantomSuccess(Redpacket rp, RedpacketRecord record, Long userId) {
        if (!"invite_friends_bonus".equals(rp.getEligibilityRule())) {
            return false;
        }
        BigDecimal expected = resolveClaimAmount(rp, userId);
        if (expected.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal claimed = record.getAmount() == null ? BigDecimal.ZERO
            : record.getAmount().setScale(2, RoundingMode.HALF_UP);
        return claimed.compareTo(expected) != 0;
    }

    private BigDecimal lookupInviteTierBonus(Long userId, int level) {
        JSONObject quota = inviteFriendsBonusService.fetchQuota(userId);
        JSONObject invite = quota == null ? null : quota.getJSONObject("invite_friends");
        JSONArray tiers = invite == null ? null : invite.getJSONArray("tiers");
        if (tiers == null) {
            return null;
        }
        for (int i = 0; i < tiers.size(); i++) {
            JSONObject tier = tiers.getJSONObject(i);
            if (tier == null || level != tier.getIntValue("level")) {
                continue;
            }
            BigDecimal bonus = tier.getBigDecimal("bonus");
            return bonus == null ? null : bonus.setScale(2, RoundingMode.HALF_UP);
        }
        return null;
    }

    private void noteSharedClaim(Redpacket rp, RedpacketRecord record) {
        if (rp == null || record == null || isPrivateRedpacket(rp)) {
            return;
        }
        try {
            groupMessageService.recordRedpacketClaim(rp.getGroupId(), record.getUserId(), record.getUserName());
        } catch (RuntimeException ex) {
            log.warn("[user-redpacket] shared claim record failed redpacketId={} err={}", rp.getId(), ex.toString());
        }
    }

    private void ensureGroupMember(Long userId, Long groupId) {
        if (zhiquanChatStore.contains(groupId)) {
            return;
        }
        Group group = groupService.getById(groupId);
        if (group == null || Boolean.TRUE.equals(group.getDissolve())) {
            throw new GlobalException(40004, "群已不存在");
        }
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, userId);
        if (member == null || Boolean.TRUE.equals(member.getQuit())) {
            throw new GlobalException(40003, "您不在该群");
        }
    }

    private boolean isExpired(Redpacket rp) {
        return rp.getExpireAt() != null && rp.getExpireAt().getTime() < System.currentTimeMillis();
    }

    @Transactional
    protected void markExpired(Redpacket rp) {
        refundMemberRemainder(rp);
        rp.setStatus(RP_STATUS_EXPIRED);
        rp.setUpdatedAt(new Date());
        redpacketMapper.updateById(rp);
        tryRepublish(rp);
    }

    /**
     * 成员红包未领完的金额退回发红包的人。tradeNo 幂等，重复调用不会退两次。
     */
    private void refundMemberRemainder(Redpacket rp) {
        JSONObject ex = parseExtraJson(rp.getExtra());
        if (ex == null || !"member".equals(ex.getString("source"))) {
            return;
        }
        Long senderId = ex.getLong("senderUserId");
        if (senderId == null || senderId <= 0 || rp.getAmount() == null || rp.getExternalId() == null) {
            return;
        }
        int claimed = rp.getClaimedCount() == null ? 0 : rp.getClaimedCount();
        int left = Math.max(0, (rp.getTotalCount() == null ? 0 : rp.getTotalCount()) - claimed);
        if (left <= 0) {
            return;
        }
        BigDecimal refund = rp.getAmount().multiply(BigDecimal.valueOf(left)).setScale(2, RoundingMode.HALF_UP);
        if (refund.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if ("sport".equals(ex.getString("merchantNo"))) {
            User sender = userService.getById(senderId);
            sportGroupRedpack.credit(sender, refund, "rp-refund-" + rp.getExternalId(), "红包退回");
            return;
        }
        aagamesBusinessClient.moveRedpacketFund(senderId, "credit", refund, "rp-refund-" + rp.getExternalId());
    }

    private boolean sportPacket(Redpacket rp) {
        JSONObject ex = parseExtraJson(rp.getExtra());
        return ex != null && "member".equals(ex.getString("source")) && "sport".equals(ex.getString("merchantNo"));
    }

    private RedpacketClaimResultVO finishLocalClaim(Redpacket rp, RedpacketRecord existing, BigDecimal amount) {
        existing.setStatus(STATUS_SUCCESS);
        existing.setAmount(amount);
        existing.setClaimedAt(new Date());
        existing.setFailReason("");
        redpacketRecordMapper.updateById(existing);
        int newClaimed = (rp.getClaimedCount() == null ? 0 : rp.getClaimedCount()) + 1;
        rp.setClaimedCount(newClaimed);
        rp.setUpdatedAt(new Date());
        boolean finished = newClaimed >= rp.getTotalCount();
        if (finished) {
            rp.setStatus(RP_STATUS_FINISHED);
        }
        redpacketMapper.updateById(rp);
        if (finished) {
            tryRepublish(rp);
        }
        noteSharedClaim(rp, existing);
        return successResult(rp, amount, existing.getClaimedAt(), Math.max(0, rp.getTotalCount() - newClaimed), "success");
    }

    private RedpacketRecordsVO localRecords(Redpacket rp) {
        List<RedpacketRecord> rows = redpacketRecordMapper.selectList(new LambdaQueryWrapper<RedpacketRecord>()
            .eq(RedpacketRecord::getRedpacketId, rp.getId())
            .eq(RedpacketRecord::getStatus, STATUS_SUCCESS)
            .orderByAsc(RedpacketRecord::getClaimedAt));
        RedpacketRecordsVO vo = new RedpacketRecordsVO();
        vo.setRedpacketExternalId(rp.getExternalId());
        BigDecimal total = BigDecimal.ZERO;
        List<RedpacketRecordsVO.Record> records = new ArrayList<>();
        for (RedpacketRecord row : rows) {
            RedpacketRecordsVO.Record item = new RedpacketRecordsVO.Record();
            item.setUserId(row.getUserId());
            item.setUserName(row.getUserName());
            item.setUserAvatar(row.getUserAvatar());
            BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
            item.setAmount(amount);
            item.setClaimedAt(row.getClaimedAt() == null ? null : row.getClaimedAt().getTime());
            item.setIsLuckiest(false);
            records.add(item);
            total = total.add(amount);
        }
        vo.setTotalAmount(total);
        vo.setClaimedCount(records.size());
        vo.setRecords(records);
        return vo;
    }

    private void ensureSameMerchant(Long userId, Redpacket rp) {
        JSONObject ex = parseExtraJson(rp.getExtra());
        if (ex == null || !"member".equals(ex.getString("source"))) {
            return;
        }
        String packetMerchant = ex.getString("merchantNo");
        User claimer = userService.getById(userId);
        if (claimer == null || UserType.GUEST.getValue().equals(claimer.getType())) {
            throw new GlobalException(40003, "当前账号不能领这个红包");
        }
        String mine = claimer.getMerchantNo() == null ? "" : claimer.getMerchantNo().trim();
        if (packetMerchant == null || packetMerchant.isBlank() || !packetMerchant.equals(mine)) {
            throw new GlobalException(40003, "只能领取同一商户的红包");
        }
    }

    private void tryRepublish(Redpacket rp) {
        if (isPrivateRedpacket(rp)) {
            return;
        }
        try {
            Group g = groupService.getById(rp.getGroupId());
            if (g != null) {
                redpacketBotService.republishCardStatus(g, rp);
            }
        } catch (Exception e) {
            log.warn("[user-redpacket] republish status fail redpacketId={} err={}", rp.getId(), e.toString());
        }
    }

    private static String toStatusString(Integer status) {
        if (status == null) return "claiming";
        switch (status) {
            case RP_STATUS_FINISHED: return "finished";
            case RP_STATUS_EXPIRED: return "expired";
            case 3: return "rolled_back";
            default: return "claiming";
        }
    }

    /** 占位：避免 IDE 误报 unused import */
    @SuppressWarnings("unused")
    private static JSONObject _emptyExtra() {
        return new JSONObject();
    }
}
