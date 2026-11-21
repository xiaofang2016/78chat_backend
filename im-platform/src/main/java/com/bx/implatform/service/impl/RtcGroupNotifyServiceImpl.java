package com.bx.implatform.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bx.imclient.IMClient;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.implatform.annotation.NotifyCheck;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.entity.Group;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.service.GroupService;
import com.bx.implatform.service.RtcGroupNotifyService;
import com.bx.implatform.session.WebrtcUserInfo;
import com.bx.implatform.thirdparty.UniPushService;
import com.bx.implatform.util.MessageUtils;
import com.bx.implatform.vo.GroupMessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author: Blue
 * @date: 2024-08-25
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RtcGroupNotifyServiceImpl implements RtcGroupNotifyService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final GroupService groupService;
    private final UniPushService uniPushService;
    private final IMClient imClient;

    @NotifyCheck
    @Override
    public void setUp(Group group, WebrtcUserInfo inviter, List<Long> recvIds) {
        recvIds = filterOfflineApp(recvIds);
        Map<Long, String> cidMap = getCid(recvIds);
        if (cidMap.isEmpty()) {
            return;
        }
        Map<Long, String> localeMap = getLocale(recvIds);
        String notifyId = getNotifyId(group.getId());
        String title = group.getName();
        String logo = group.getHeadImageThumb();
        GroupMessageVO messageInfo = new GroupMessageVO();
        messageInfo.setType(MessageType.RTC_GROUP_SETUP.code());
        messageInfo.setGroupId(group.getId());
        messageInfo.setSendId(inviter.getId());
        cidMap.forEach((recvId, cid) -> {
            // 国际化转换
            String locale = localeMap.get(recvId);
            String code = "notify.rtcGroupInvite";
            String body = prefix(locale) + MessageUtils.getMessage(locale, code, inviter.getNickName());
            uniPushService.asyncSend(cid, title, body, logo, notifyId, messageInfo);
        });
    }

    @NotifyCheck
    @Override
    public void stop(Long groupId, List<Long> recvIds, String messageKey) {
        recvIds = filterOfflineApp(recvIds);
        Map<Long, String> cidMap = getCid(recvIds);
        if (cidMap.isEmpty()) {
            return;
        }
        Map<Long, String> localeMap = getLocale(recvIds);
        Group group = getGroupInfo(groupId);
        String notifyId = getNotifyId(groupId);
        String title = group.getName();
        String logo = group.getHeadImageThumb();
        cidMap.forEach((recvId, cid) -> {
            String locale = localeMap.get(recvId);
            String body = prefix(locale) + MessageUtils.getMessage(locale, messageKey);
            uniPushService.asyncSend(cid, title, body, logo, notifyId, null);
        });
    }

    @NotifyCheck
    @Override
    public void stop(Long groupId, Long recvId, String messageKey) {
        if (imClient.isOnline(recvId, IMTerminalType.APP)) {
            return;
        }
        String cid = getCid(recvId);
        if (StrUtil.isEmpty(cid)) {
            return;
        }
        String locale = getLocale(recvId);
        Group group = getGroupInfo(groupId);
        String notifyId = getNotifyId(groupId);
        String title = group.getName();
        String body = prefix(locale) + MessageUtils.getMessage(locale, messageKey);
        String logo = group.getHeadImageThumb();
        uniPushService.asyncSend(cid, title, body, logo, notifyId, null);
    }

    /**
     * 过滤出app端不在线的用户
     *
     * @param userIds 用户id
     */
    List<Long> filterOfflineApp(List<Long> userIds) {
        return userIds.stream().filter(userId -> !imClient.isOnline(userId, IMTerminalType.APP))
            .collect(Collectors.toList());
    }

    /**
     * 批量获取用户cid
     */
    private Map<Long, String> getCid(List<Long> userIds) {
        if (CollectionUtil.isEmpty(userIds)) {
            return new HashMap<>();
        }
        List<String> keys = userIds.stream().map(this::buildCidKey).collect(Collectors.toList());
        List<Object> cids = redisTemplate.opsForValue().multiGet(keys);
        Map<Long, String> cidMap = new HashMap<>();
        int idx = 0;
        for (Long userId : userIds) {
            Object cid = cids.get(idx++);
            if (!Objects.isNull(cid)) {
                cidMap.put(userId, cid.toString());
            }
        }
        return cidMap;
    }

    private String getCid(Long userId) {
        String key = buildCidKey(userId);
        return (String)redisTemplate.opsForValue().get(key);
    }

    private String buildCidKey(Long userId) {
        return StrUtil.join(":", RedisKey.IM_USER_CID, userId);
    }

    private String getNotifyId(Long groupId) {
        String key = StrUtil.join(":", RedisKey.IM_WEBRTC_GROUP_SESSION, groupId);
        return Math.abs(key.hashCode()) + "";
    }

    private Group getGroupInfo(Long groupId) {
        LambdaQueryWrapper<Group> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Group::getId, groupId);
        wrapper.select(Group::getName, Group::getHeadImageThumb);
        return groupService.getOne(wrapper);
    }

    private Map<Long, String> getLocale(List<Long> userIds) {
        if (CollectionUtil.isEmpty(userIds)) {
            return new HashMap<>();
        }
        List<String> keys = userIds.stream().map(this::buildLocaleKey).collect(Collectors.toList());
        List<Object> locales = redisTemplate.opsForValue().multiGet(keys);
        Map<Long, String> localeMap = new HashMap<>();
        int idx = 0;
        for (Long userId : userIds) {
            Object locale = locales.get(idx++);
            if (!Objects.isNull(locale)) {
                localeMap.put(userId, locale.toString());
            }
        }
        return localeMap;
    }

    private String buildLocaleKey(Long userId) {
        return StrUtil.join(":", RedisKey.IM_USER_LOCALE, userId, IMTerminalType.APP.code());
    }

    private String getLocale(Long userId) {
        String key = buildLocaleKey(userId);
        return (String)redisTemplate.opsForValue().get(key);
    }

    private String prefix(String locale) {
        return MessageUtils.getMessage(locale, "notify.rtcGroupCall") + " ";
    }

}
