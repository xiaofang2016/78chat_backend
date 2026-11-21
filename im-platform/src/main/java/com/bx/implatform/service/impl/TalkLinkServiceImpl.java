package com.bx.implatform.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bx.implatform.dto.UserTalkSettingsDTO;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.UserStatus;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.service.FriendService;
import com.bx.implatform.service.TalkLinkService;
import com.bx.implatform.vo.TalkProfileVO;
import com.bx.implatform.vo.UserTalkSettingsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TalkLinkServiceImpl implements TalkLinkService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,32}$");
    private static final Set<String> RESERVED_SLUGS = Set.of(
        "admin", "api", "talk", "login", "home", "system", "external", "register", "user", "friend", "group", "message"
    );

    private final UserMapper userMapper;
    private final FriendService friendService;

    @Override
    public User findEnabledUserBySlug(String slug) {
        User user = findUserByTalkSlug(slug);
        if (user == null || !Boolean.TRUE.equals(user.getTalkEnabled())) {
            return null;
        }
        return user;
    }

    @Override
    public User findUserByTalkSlug(String slug) {
        String normalized = normalizeSlug(slug);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(User::getTalkSlug, normalized);
        wrapper.eq(User::getIsBanned, false);
        wrapper.eq(User::getStatus, UserStatus.NORMAL.getValue());
        return userMapper.selectOne(wrapper);
    }

    @Override
    public TalkProfileVO resolveProfile(String slug, Long viewerId) {
        User user = findUserByTalkSlug(slug);
        if (user == null) {
            throw new GlobalException("talk.notFound");
        }
        TalkProfileVO vo = new TalkProfileVO();
        vo.setUserId(user.getId());
        vo.setTalkSlug(user.getTalkSlug());
        vo.setNickName(user.getNickName());
        vo.setHeadImageThumb(user.getHeadImageThumb());
        vo.setSignature(user.getSignature());
        vo.setTalkEnabled(Boolean.TRUE.equals(user.getTalkEnabled()));
        if (viewerId == null) {
            vo.setSelf(false);
            vo.setIsFriend(false);
            vo.setCanMessage(false);
            return vo;
        }
        boolean self = Objects.equals(viewerId, user.getId());
        vo.setSelf(self);
        boolean isFriend = friendService.isFriend(viewerId, user.getId());
        vo.setIsFriend(isFriend);
        vo.setCanMessage(!self && (isFriend || canSendWithoutFriend(viewerId, user.getId())));
        return vo;
    }

    @Override
    public UserTalkSettingsVO getSettings(Long userId) {
        User user = requireUser(userId);
        UserTalkSettingsVO vo = new UserTalkSettingsVO();
        vo.setTalkEnabled(Boolean.TRUE.equals(user.getTalkEnabled()));
        vo.setTalkSlug(user.getTalkSlug());
        vo.setTalkUrl(buildTalkPath(user.getTalkSlug()));
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateSettings(Long userId, UserTalkSettingsDTO dto) {
        if (dto == null || dto.getTalkEnabled() == null) {
            throw new GlobalException("talk.invalidSettings");
        }
        User user = requireUser(userId);
        if (!Boolean.TRUE.equals(dto.getTalkEnabled())) {
            user.setTalkEnabled(false);
            userMapper.updateById(user);
            return;
        }
        String slug = normalizeSlug(dto.getTalkSlug());
        if (StrUtil.isBlank(slug)) {
            slug = defaultSlugFromUserName(user.getUserName());
        }
        validateSlug(slug);
        ensureSlugAvailable(slug, userId);
        user.setTalkEnabled(true);
        user.setTalkSlug(slug);
        userMapper.updateById(user);
    }

    @Override
    public boolean canSendWithoutFriend(Long senderId, Long recvId) {
        if (senderId == null || recvId == null || Objects.equals(senderId, recvId)) {
            return false;
        }
        User recv = userMapper.selectById(recvId);
        return recv != null && !Boolean.TRUE.equals(recv.getIsBanned())
            && Objects.equals(recv.getStatus(), UserStatus.NORMAL.getValue());
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new GlobalException("user.notFound");
        }
        return user;
    }

    private void validateSlug(String slug) {
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new GlobalException("talk.invalidSlug");
        }
        if (RESERVED_SLUGS.contains(slug)) {
            throw new GlobalException("talk.slugReserved");
        }
    }

    private void ensureSlugAvailable(String slug, Long ownerId) {
        LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(User::getTalkSlug, slug);
        wrapper.ne(User::getId, ownerId);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new GlobalException("talk.slugTaken");
        }
    }

    private String defaultSlugFromUserName(String userName) {
        if (StrUtil.isBlank(userName)) {
            throw new GlobalException("talk.slugRequired");
        }
        String slug = normalizeSlug(userName);
        validateSlug(slug);
        return slug;
    }

    private String normalizeSlug(String slug) {
        return StrUtil.isBlank(slug) ? null : slug.trim().toLowerCase(Locale.ROOT);
    }

    private String buildTalkPath(String slug) {
        if (StrUtil.isBlank(slug)) {
            return null;
        }
        return "/talk/" + slug;
    }
}
