package com.bx.implatform.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.dto.GroupJoinDTO;
import com.bx.implatform.entity.Group;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.BizTokenType;
import com.bx.implatform.enums.UserStatus;
import com.bx.implatform.enums.UserType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.service.GroupMemberService;
import com.bx.implatform.service.GroupMessageService;
import com.bx.implatform.service.GroupService;
import com.bx.implatform.service.SportPublicChatService;
import com.bx.implatform.session.UserSession;
import com.bx.implatform.vo.BizTokenVO;
import com.bx.implatform.vo.SportChatSessionVO;
import com.bx.implatform.vo.external.LiveGroupCreateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SportPublicChatServiceImpl implements SportPublicChatService {

    private static final String GROUP_NAME = "说球帝";

    private final GroupService groupService;
    private final GroupMemberService groupMemberService;
    private final GroupMessageService groupMessageService;
    private final UserMapper userMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    @Override
    public SportChatSessionVO enter(UserSession session, Long matchId) {
        User user = userMapper.selectById(session.getUserId());
        if (user == null) {
            throw new GlobalException(400, "用户不存在");
        }
        presentGuest(user, session);
        long groupId = ensureGroup(matchId);
        join(groupId, user);
        SportChatSessionVO vo = new SportChatSessionVO();
        vo.setGroupId(groupId);
        vo.setUserId(user.getId());
        vo.setNickName(user.getNickName());
        vo.setMessages(groupMessageService.loadGuestMessages(groupId, 50));
        return vo;
    }

    private void presentGuest(User user, UserSession session) {
        if (!UserType.GUEST.getValue().equals(user.getType())) {
            return;
        }
        if (StrUtil.isNotBlank(user.getNickName()) && user.getNickName().startsWith("观众")) {
            session.setNickName(user.getNickName());
            return;
        }
        String nick = "观众" + String.format("%04d", Math.floorMod(user.getId(), 10000));
        user.setNickName(nick);
        userMapper.updateById(user);
        session.setNickName(nick);
    }

    private void join(long groupId, User user) {
        String token = joinToken(groupId);
        GroupJoinDTO dto = new GroupJoinDTO();
        dto.setGroupId(groupId);
        BizTokenVO biz = new BizTokenVO();
        biz.setToken(token);
        biz.setType(BizTokenType.GROUP_LIVE_PUBLIC.getCode());
        dto.setToken(biz);
        groupService.joinAsUser(user.getId(), dto);
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, user.getId());
        if (member != null && !user.getNickName().equals(member.getUserNickName())) {
            member.setUserNickName(user.getNickName());
            groupMemberService.updateById(member);
        }
    }

    private long ensureGroup(Long matchId) {
        String redisKey = groupRedisKey(matchId);
        String lockKey = matchId == null || matchId <= 0
            ? "im:lock:sport:public:group"
            : "im:lock:sport:public:group:" + matchId;
        Long existing = readGroupId(redisKey);
        if (existing != null && alive(existing)) {
            return existing;
        }
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock(10, TimeUnit.SECONDS);
        try {
            existing = readGroupId(redisKey);
            if (existing != null && alive(existing)) {
                return existing;
            }
            User owner = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getStatus, UserStatus.NORMAL.getValue())
                .eq(User::getType, UserType.NORMAL.getValue())
                .eq(User::getIsBanned, false)
                .orderByAsc(User::getId)
                .last("limit 1"));
            if (owner == null) {
                throw new GlobalException(500, "还没有可建群的职圈用户");
            }
            String name = matchId == null || matchId <= 0 ? GROUP_NAME : GROUP_NAME + "-" + matchId;
            LiveGroupCreateVO created = groupService.createLivePublicGroup(owner.getId(), name);
            redisTemplate.opsForValue().set(redisKey, String.valueOf(created.getGroupId()));
            log.info("创建说球帝公开群 groupId={} ownerId={} matchId={}", created.getGroupId(), owner.getId(), matchId);
            return created.getGroupId();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private static String groupRedisKey(Long matchId) {
        if (matchId == null || matchId <= 0) {
            return RedisKey.IM_SPORT_PUBLIC_GROUP;
        }
        return RedisKey.IM_SPORT_PUBLIC_GROUP + ":" + matchId;
    }

    private boolean alive(long groupId) {
        Group group = groupService.getById(groupId);
        return group != null && !Boolean.TRUE.equals(group.getDissolve());
    }

    private Long readGroupId(String redisKey) {
        Object raw = redisTemplate.opsForValue().get(redisKey);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().replace("\"", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String joinToken(long groupId) {
        String key = StrUtil.join(":", RedisKey.IM_GROUP_LIVE_JOIN, groupId);
        Object stored = redisTemplate.opsForValue().get(key);
        if (stored != null && StrUtil.isNotBlank(stored.toString())) {
            return stored.toString();
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(key, token);
        return token;
    }
}
