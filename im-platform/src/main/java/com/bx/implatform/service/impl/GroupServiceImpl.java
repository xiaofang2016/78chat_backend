package com.bx.implatform.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bx.imclient.IMClient;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.IMGroupMessage;
import com.bx.imcommon.model.IMUserInfo;
import com.bx.implatform.annotation.RedisLock;
import com.bx.implatform.contant.Constant;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.dto.*;
import com.bx.implatform.entity.*;
import com.bx.implatform.enums.BizTokenType;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.enums.UserType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.ApkGroupAdminMapper;
import com.bx.implatform.mapper.GroupMapper;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.service.*;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.session.UserSession;
import com.bx.implatform.util.BeanUtils;
import com.bx.implatform.util.TipContentUtil;
import com.bx.implatform.vo.*;
import com.bx.implatform.vo.external.LiveGroupCreateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@CacheConfig(cacheNames = RedisKey.IM_CACHE_GROUP)
public class GroupServiceImpl extends ServiceImpl<GroupMapper, Group> implements GroupService {
    private final UserMapper userMapper;
    private final GroupMemberService groupMemberService;
    private final FriendService friendsService;
    private final IMClient imClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final FriendService friendService;

    @Lazy
    @Autowired
    private GroupMessageService groupMessageService;

    @Lazy
    @Autowired
    private WebrtcGroupService webrtcGroupService;

    private final ZhiquanChatStore zhiquanChatStore;
    private final MineDeskService mineDesk;
    private final ApkGroupAdminMapper apkGroupAdminMapper;

    @Override
    public GroupVO createGroup(GroupVO vo) {
        UserSession session = SessionContext.getSession();
        User user = userMapper.selectById(session.getUserId());
        // 保存群组数据
        Group group = BeanUtils.copyProperties(vo, Group.class);
        group.setOwnerId(user.getId());
        this.save(group);
        // 把群主加入群
        GroupMember member = new GroupMember();
        member.setGroupId(group.getId());
        member.setUserId(user.getId());
        member.setUserNickName(user.getNickName());
        member.setHeadImage(user.getHeadImageThumb());
        member.setRemarkNickName(vo.getRemarkNickName());
        member.setRemarkGroupName(vo.getRemarkGroupName());
        member.setCompanyName(user.getCompanyName());
        groupMemberService.save(member);
        GroupVO groupVo = findById(group.getId());
        // 推送同步消息给自己的其他终端
        sendAddGroupMessage(groupVo, List.of(session.getUserId()));
        // 返回
        log.info("创建群聊，群聊id:{},群聊名称:{}", group.getId(), group.getName());
        return groupVo;
    }

    @CacheEvict(key = "#vo.getId()")
    @Transactional(rollbackFor = Exception.class)
    @Override
    public GroupVO modifyGroup(GroupVO vo) {
        UserSession session = SessionContext.getSession();
        // 校验是不是群主，只有群主能改信息
        Group group = this.getAndCheckById(vo.getId());
        // 更新成员信息
        GroupMember member = groupMemberService.findByGroupAndUserId(vo.getId(), session.getUserId());
        if (Objects.isNull(member) || member.getQuit()) {
            throw new GlobalException("group.notMember");
        }
        member.setRemarkNickName(vo.getRemarkNickName());
        member.setRemarkGroupName(vo.getRemarkGroupName());
        member.setVersion(groupMemberService.getNextVersion());
        groupMemberService.updateById(member);
        // 群主和管理员有权修改群基本信息
        if (group.getOwnerId().equals(session.getUserId()) || member.getIsManager()) {
            // 禁言模式发生修改，推送提示语
            if (!Objects.isNull(vo.getIsAllMuted()) && !group.getIsAllMuted().equals(vo.getIsAllMuted())) {
                List<Long> userIds = groupMemberService.findUserIdsByGroupId(vo.getId());
                sendMutedTip(vo.getId(), member, userIds, vo.getIsAllMuted());
                sendSyncMessage(vo.getId(), userIds, MessageType.GROUP_ALL_MUTED, vo.getIsAllMuted());
            }
            group = BeanUtils.copyProperties(vo, Group.class);
            this.updateById(group);
        }
        log.info("修改群聊，群聊id:{},群聊名称:{}", group.getId(), group.getName());
        return convert(group, member);
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(key = "#groupId")
    @Override
    public void deleteGroup(Long groupId) {
        UserSession session = SessionContext.getSession();
        Group group = this.getById(groupId);
        if (!group.getOwnerId().equals(session.getUserId())) {
            throw new GlobalException("group.onlyOwnerDisband");
        }
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, session.getUserId());
        // 群聊用户id
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(groupId);
        // 逻辑删除群数据
        group.setDissolve(true);
        this.updateById(group);
        // 删除成员数据
        groupMemberService.removeByGroupId(groupId);
        // 清理已读缓存
        String key = StrUtil.join(":", RedisKey.IM_GROUP_READED_POSITION, groupId);
        redisTemplate.delete(key);
        // 清理成员版本号
        key = StrUtil.join(":", RedisKey.IM_GROUP_MEMBER_MAX_VERSION, groupId);
        redisTemplate.delete(key);
        // 推送解散群聊提示
        this.sendTipMessage(groupId, userIds, "tip.group.disband", formatUserMark(member));
        // 推送同步消息所有用户
        this.sendDelGroupMessage(groupId, userIds);
        log.info("删除群聊，群聊id:{},群聊名称:{}", group.getId(), group.getName());
    }

    @Override
    public void quitGroup(Long groupId) {
        Long userId = SessionContext.getSession().getUserId();
        // 演示环境测试号处理
        User userInfo = userMapper.selectById(userId);
        if (userInfo.getType().equals(UserType.OPEN_ACCOUNT.getValue())) {
            // 测试号好友数量低于100时不允许删除好友(防止有人捣乱)
            Long count = groupMemberService.findCountByUserId(userId);
            if (count < 100) {
                throw new GlobalException("group.demoNoQuit");
            }
        }
        Group group = this.getById(groupId);
        if (group.getOwnerId().equals(userId)) {
            throw new GlobalException("group.ownerCannotQuit");
        }
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, userId);
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(groupId);
        // 删除群聊成员
        groupMemberService.removeByGroupAndUserId(groupId, userId);
        // 推送退出提示给其他成员
        this.sendTipMessage(groupId, userIds, "tip.group.quit", formatUserMark(member));
        // 推送同步消息给其他终端
        this.sendDelGroupMessage(groupId, List.of(userId));
        log.info("退出群聊，群聊id:{},群聊名称:{},用户id:{}", group.getId(), group.getName(), userId);
    }

    @Override
    public void removeGroupMembers(GroupMemberRemoveDTO dto) {
        UserSession session = SessionContext.getSession();
        Group group = this.getAndCheckById(dto.getGroupId());
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        boolean isOwner = group.getOwnerId().equals(session.getUserId());
        if (!isOwner && !member.getIsManager()) {
            throw new GlobalException("group.noPermission");
        }
        if (dto.getUserIds().contains(group.getOwnerId())) {
            throw new GlobalException("group.cannotRemoveOwner");
        }
        if (dto.getUserIds().contains(session.getUserId())) {
            throw new GlobalException("group.cannotRemoveSelf");
        }
        List<GroupMember> members = groupMemberService.findByGroupAndUserIds(dto.getGroupId(), dto.getUserIds());
        boolean hasManager = members.stream().anyMatch(GroupMember::getIsManager);
        if (!isOwner && hasManager) {
            throw new GlobalException("group.noRemoveAdminPermission");
        }
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(dto.getGroupId());
        // 删除群聊成员
        groupMemberService.removeByGroupAndUserIds(dto.getGroupId(), dto.getUserIds());
        // 推送踢出群聊提示
        this.sendTipMessage(dto.getGroupId(), userIds, "tip.group.remove", formatUserMark(member),
            formatUserMarks(members));
        // 推送同步消息
        this.sendDelGroupMessage(dto.getGroupId(), dto.getUserIds());
        log.info("踢出群聊，群聊id:{},群聊名称:{},用户id:{}", group.getId(), group.getName(), dto.getUserIds());
    }

    @Override
    public GroupVO findById(Long groupId) {
        UserSession session = SessionContext.getSession();
        Group group = super.getById(groupId);
        if (Objects.isNull(group)) {
            if (zhiquanChatStore.serveTo(session.getUserId())) {
                GroupVO shared = zhiquanChatStore.group(groupId);
                if (shared != null) {
                    fillApkOwners(List.of(shared));
                    zhiquanChatStore.touchViewer(groupId, session.getUserId());
                    mineDesk.paintGroups(session.getUserId(), List.of(shared));
                    return shared;
                }
            }
            throw new GlobalException("group.groupNotExist");
        }
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, session.getUserId());
        if (Objects.isNull(member)) {
            member = publicReaderMember(groupId);
        }
        if (Objects.isNull(member) && zhiquanChatStore.serveTo(session.getUserId())) {
            member = zhiquanChatStore.readerMember(groupId);
        }
        GroupVO vo = convert(group, member);
        // 填充群置顶消息
        if (!Objects.isNull(group.getTopMessageId()) && !Objects.isNull(member) && member.getIsTopMessage()) {
            GroupMessage message = groupMessageService.getById(group.getTopMessageId());
            if (!Objects.isNull(message)) {
                vo.setTopMessage(BeanUtils.copyProperties(message, GroupMessageVO.class));
            }
        }
        // 通话信息
        WebrtcGroupInfoVO rtcInfo = webrtcGroupService.info(groupId);
        vo.setRtcInfo(rtcInfo);
        if (zhiquanChatStore.contains(groupId)) {
            zhiquanChatStore.touchViewer(groupId, session.getUserId());
        }
        mineDesk.paintGroups(session.getUserId(), List.of(vo));
        return vo;
    }

    @Cacheable(key = "#groupId")
    @Override
    public Group getAndCheckById(Long groupId) {
        Group group = super.getById(groupId);
        if (Objects.isNull(group)) {
            throw new GlobalException("group.groupNotExist");
        }
        if (group.getDissolve()) {
            throw new GlobalException("group.groupDissolved", group.getName());
        }
        if (group.getIsBanned()) {
            throw new GlobalException("group.groupBanned", group.getName(), group.getReason());
        }
        return group;
    }

    @Override
    public List<GroupVO> findGroups(Long version) {
        UserSession session = SessionContext.getSession();
        List<GroupMember> groupMembers;
        if (version > 0) {
            // 增量拉取
            LambdaQueryWrapper<GroupMember> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(GroupMember::getUserId, session.getUserId());
            wrapper.gt(GroupMember::getVersion, version);
            groupMembers = groupMemberService.list(wrapper);
        } else {
            // 全量拉取查询当前用户的群id列表
            groupMembers = groupMemberService.findByUserId(session.getUserId());
            // 30天内退的群可能存在退群前的离线消息,一并返回作为前端缓存
            Date minQuitTime = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
            groupMembers.addAll(groupMemberService.findQuitMembers(session.getUserId(), minQuitTime));
        }
        List<GroupVO> vos;
        if (groupMembers.isEmpty()) {
            vos = new ArrayList<>();
        } else {
            List<Long> ids = groupMembers.stream().map(GroupMember::getGroupId).collect(Collectors.toList());
            LambdaQueryWrapper<Group> wrapper = Wrappers.lambdaQuery();
            wrapper.in(Group::getId, ids);
            List<Group> groups = this.list(wrapper);
            Map<Long, GroupMember> map =
                groupMembers.stream().collect(Collectors.toMap(GroupMember::getGroupId, o -> o, (a, b) -> a));
            vos = groups.stream().map(group -> convert(group, map.get(group.getId()))).collect(Collectors.toList());
        }
        Set<Long> have = vos.stream().map(GroupVO::getId).collect(Collectors.toSet());
        for (Group group : listPublicGroups()) {
            if (!have.contains(group.getId())) {
                vos.add(convert(group, publicReaderMember(group.getId())));
                have.add(group.getId());
            }
        }
        if (zhiquanChatStore.serveTo(session.getUserId())) {
            vos.addAll(zhiquanChatStore.groupsExcept(have));
        }
        fillApkOwners(vos);
        mineDesk.paintGroups(session.getUserId(), vos);
        return vos;
    }

    @Override
    public List<Group> listPublicGroups() {
        LambdaQueryWrapper<Group> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Group::getIsPublic, true);
        wrapper.eq(Group::getDissolve, false);
        return this.list(wrapper);
    }

    @Override
    public GroupMember publicReaderMember(Long groupId) {
        Group group = this.getById(groupId);
        if (Objects.isNull(group) || !Boolean.TRUE.equals(group.getIsPublic()) || Boolean.TRUE.equals(group.getDissolve())) {
            return null;
        }
        GroupMember member = new GroupMember();
        member.setGroupId(group.getId());
        member.setQuit(false);
        member.setIsDnd(false);
        member.setIsTop(false);
        member.setIsMuted(false);
        member.setIsManager(false);
        member.setIsTopMessage(false);
        member.setVersion(0L);
        member.setCreatedTime(new Date(0));
        return member;
    }

    @RedisLock(prefixKey = RedisKey.IM_LOCK_GROUP_ENTER, key = "#dto.getGroupId()")
    @Override
    public void invite(GroupInviteDTO dto) {
        UserSession session = SessionContext.getSession();
        Group group = this.getAndCheckById(dto.getGroupId());
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        if (Objects.isNull(group) || member.getQuit()) {
            throw new GlobalException("group.notInGroupInvite");
        }
        boolean isOwner = session.getUserId().equals(group.getOwnerId());
        if (!group.getIsAllowInvite() && !isOwner && !member.getIsManager()) {
            throw new GlobalException("group.noInvitePermission");
        }
        // 群聊人数校验
        List<GroupMember> members = groupMemberService.findByGroupId(dto.getGroupId(), 0L);
        long size = members.stream().filter(m -> !m.getQuit()).count();
        if (dto.getFriendIds().size() + size > Constant.MAX_GROUP_MEMBER) {
            throw new GlobalException("group.memberLimit", Constant.MAX_GROUP_MEMBER);
        }
        // 找出好友信息
        List<Friend> friends = friendsService.findByFriendIds(dto.getFriendIds());
        if (dto.getFriendIds().size() != friends.size()) {
            throw new GlobalException("group.inviteNotFriend");
        }
        // 批量保存成员数据
        List<GroupMember> groupMembers = friends.stream().map(f -> {
            Optional<GroupMember> optional =
                members.stream().filter(m -> m.getUserId().equals(f.getFriendId())).findFirst();
            GroupMember groupMember = optional.orElseGet(GroupMember::new);
            groupMember.setGroupId(dto.getGroupId());
            groupMember.setUserId(f.getFriendId());
            groupMember.setUserNickName(f.getFriendNickName());
            groupMember.setHeadImage(f.getFriendHeadImage());
            groupMember.setCompanyName(f.getFriendCompanyName());
            groupMember.setCreatedTime(new Date());
            groupMember.setIsManager(false);
            groupMember.setQuit(false);
            return groupMember;
        }).collect(Collectors.toList());
        if (!groupMembers.isEmpty()) {
            groupMemberService.saveOrUpdateBatch(group.getId(), groupMembers);
        }
        // 推送同步消息给被邀请人
        for (GroupMember groupMember : groupMembers) {
            GroupVO groupVo = convert(group, groupMember);
            sendAddGroupMessage(groupVo, List.of(groupMember.getUserId()));
        }
        // 推送进入群聊消息
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(dto.getGroupId());
        this.sendTipMessage(dto.getGroupId(), userIds, "tip.group.invite", formatUserMark(member),
            formatUserMarks(groupMembers));
        log.info("邀请进入群聊，群聊id:{},群聊名称:{},被邀请用户id:{}", group.getId(), group.getName(),
            dto.getFriendIds());
    }

    @Override
    public BizTokenVO generateShareCardToken(Long groupId) {
        UserSession session = SessionContext.getSession();
        Group group = this.getAndCheckById(groupId);
        // 检查用户是否在群聊中，只有群成员才能生成加入token
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, session.getUserId());
        if (Objects.isNull(member) || member.getQuit()) {
            throw new GlobalException("group.noCardPermission");
        }
        // 检查群聊是否允许分享
        boolean isOwner = session.getUserId().equals(group.getOwnerId());
        if ((!isOwner && !member.getIsManager() && !group.getIsAllowShareCard())) {
            // 普通成员需要检查是否允许分享名片
            throw new GlobalException("group.groupNoShareCard");
        }
        // 生成唯一token
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = StrUtil.join(":", RedisKey.IM_GROUP_CARD_TOKEN, token);
        GroupCardDTO card = new GroupCardDTO();
        card.setGroupId(groupId);
        card.setUserId(session.getUserId());
        redisTemplate.opsForValue().set(key, card, Constant.SHARE_CARD_EXPIRED_DAYS, TimeUnit.DAYS);
        // 返回token
        BizTokenVO vo = new BizTokenVO();
        vo.setToken(token);
        vo.setType(BizTokenType.GROUP_CARD.getCode());
        vo.setExpiredIn(DateUtils.addDays(new Date(), Math.toIntExact(Constant.SHARE_CARD_EXPIRED_DAYS)).getTime());
        log.info("生成群名片token，群聊id:{},用户id:{}", group.getId(), session.getUserId());
        return vo;
    }

    @Override
    public BizTokenVO generateQrcodeToken(Long groupId) {
        UserSession session = SessionContext.getSession();
        Group group = this.getAndCheckById(groupId);
        // 检查用户是否在群聊中，只有群成员才能生成加入token
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, session.getUserId());
        if (Objects.isNull(member) || member.getQuit()) {
            throw new GlobalException("group.noQrPermission");
        }
        // 检查群聊是否允许分享
        boolean isOwner = session.getUserId().equals(group.getOwnerId());
        if ((!isOwner && !member.getIsManager() && !group.getIsAllowInvite())) {
            // 普通成员需要检查是否允许分享名片
            throw new GlobalException("group.groupNoQr");
        }
        // 生成唯一token
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = StrUtil.join(":", RedisKey.IM_GROUP_QRCODE_TOKEN, token);
        GroupQrcodeDTO qrcode = new GroupQrcodeDTO();
        qrcode.setGroupId(groupId);
        qrcode.setUserId(session.getUserId());
        redisTemplate.opsForValue().set(key, qrcode, Constant.GROUP_QRCODE_EXPIRED_DAYS, TimeUnit.DAYS);
        // 返回vo
        BizTokenVO vo = new BizTokenVO();
        vo.setToken(token);
        vo.setType(BizTokenType.GROUP_QRCODE.getCode());
        vo.setExpiredIn(DateUtils.addDays(new Date(), Math.toIntExact(Constant.GROUP_QRCODE_EXPIRED_DAYS)).getTime());
        log.info("生成群聊二维码token，群聊id:{},用户id:{}", group.getId(), session.getUserId());
        return vo;
    }

    @RedisLock(prefixKey = RedisKey.IM_LOCK_GROUP_ENTER, key = "#dto.groupId")
    @Override
    public GroupVO join(GroupJoinDTO dto) {
        UserSession session = SessionContext.getSession();
        User user = userMapper.selectById(session.getUserId());
        Group group = this.getAndCheckById(dto.getGroupId());
        String tipKey = null;
        Object[] tipParams = null;
        BizTokenVO tokenVo = dto.getToken();
        // 名片分享校验
        if (BizTokenType.GROUP_CARD.getCode().equals(tokenVo.getType())) {
            String key = StrUtil.join(":", RedisKey.IM_GROUP_CARD_TOKEN, tokenVo.getToken());
            GroupCardDTO card = (GroupCardDTO)redisTemplate.opsForValue().get(key);
            if (Objects.isNull(card) || !dto.getGroupId().equals(card.getGroupId())) {
                throw new GlobalException("group.cardInvalid");
            }
            GroupMember member = groupMemberService.findByGroupAndUserId(card.getGroupId(), card.getUserId());
            tipKey = "tip.group.joinByCard";
            tipParams =
                new Object[] {formatUserMark(session.getUserId(), session.getNickName()), formatUserMark(member)};
        }
        // 二维码分享校验
        if (BizTokenType.GROUP_QRCODE.getCode().equals(tokenVo.getType())) {
            String key = StrUtil.join(":", RedisKey.IM_GROUP_QRCODE_TOKEN, tokenVo.getToken());
            GroupQrcodeDTO qrcode = (GroupQrcodeDTO)redisTemplate.opsForValue().get(key);
            if (Objects.isNull(qrcode) || !dto.getGroupId().equals(qrcode.getGroupId())) {
                throw new GlobalException("group.qrInvalid");
            }
            GroupMember member = groupMemberService.findByGroupAndUserId(qrcode.getGroupId(), qrcode.getUserId());
            tipKey = "tip.group.joinByQr";
            tipParams =
                new Object[] {formatUserMark(session.getUserId(), session.getNickName()), formatUserMark(member)};
        }
        // 直播间公开群校验
        if (BizTokenType.GROUP_LIVE_PUBLIC.getCode().equals(tokenVo.getType())) {
            String key = StrUtil.join(":", RedisKey.IM_GROUP_LIVE_JOIN, dto.getGroupId());
            Object stored = redisTemplate.opsForValue().get(key);
            if (Objects.isNull(stored) || !tokenVo.getToken().equals(stored.toString())) {
                throw new GlobalException("group.liveTokenInvalid");
            }
        }
        // 检查用户是否已在群聊中
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        if (!Objects.isNull(member) && !member.getQuit()) {
            if (BizTokenType.GROUP_LIVE_PUBLIC.getCode().equals(tokenVo.getType())) {
                return convert(group, member);
            }
            throw new GlobalException("group.alreadyInGroup");
        }
        // 检查群聊人数限制
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(dto.getGroupId());
        if (userIds.size() >= Constant.MAX_GROUP_MEMBER) {
            throw new GlobalException("group.memberFull");
        }
        // 创建或更新群成员信息
        if (Objects.isNull(member)) {
            member = new GroupMember();
        }
        member.setGroupId(dto.getGroupId());
        member.setUserId(user.getId());
        member.setUserNickName(user.getNickName());
        member.setHeadImage(user.getHeadImageThumb());
        member.setCompanyName(user.getCompanyName());
        member.setCreatedTime(new Date());
        member.setIsManager(false);
        member.setQuit(false);
        groupMemberService.saveOrUpdate(member);
        GroupVO vo = convert(group, member);
        // 同步群聊列表
        sendAddGroupMessage(vo, List.of(session.getUserId()));
        // 推送提示语
        userIds.add(session.getUserId());
        if (tipKey != null) {
            this.sendTipMessage(dto.getGroupId(), userIds, tipKey, tipParams);
        }
        log.info("加入群聊，群聊id:{},群聊名称:{},用户id:{}", group.getId(), group.getName(), session.getUserId());
        return vo;
    }

    @Override
    public List<GroupMemberVO> findGroupMembers(Long groupId, Long version) {
        Group group = getById(groupId);
        if (group == null) {
            UserSession session = SessionContext.getSession();
            if (zhiquanChatStore.serveTo(session.getUserId()) && zhiquanChatStore.contains(groupId)) {
                return apkGroupAdmins(groupId);
            }
            throw new GlobalException("group.groupNotExist");
        }
        List<GroupMember> members = groupMemberService.findByGroupId(groupId, version);
        List<Long> userIds = members.stream().map(GroupMember::getUserId).collect(Collectors.toList());
        Map<Long, String> friendRemarkMap = friendService.loadRemark(userIds);
        return members.stream().map(m -> {
            GroupMemberVO vo = BeanUtils.copyProperties(m, GroupMemberVO.class);
            // 优先使用好友备注昵称
            vo.setShowNickName(
                friendRemarkMap.containsKey(m.getUserId()) ? friendRemarkMap.get(m.getUserId()) : m.getShowNickName());
            vo.setShowGroupName(StrUtil.blankToDefault(m.getRemarkGroupName(), group.getName()));
            return vo;
        }).collect(Collectors.toList());
    }

    private void fillApkOwners(List<GroupVO> vos) {
        List<Long> missing = new ArrayList<>();
        for (GroupVO vo : vos) {
            if (vo != null && vo.getId() != null && vo.getOwnerId() == null && zhiquanChatStore.contains(vo.getId())) {
                missing.add(vo.getId());
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<ApkGroupAdmin> wrapper = Wrappers.lambdaQuery();
        wrapper.in(ApkGroupAdmin::getGroupId, missing);
        wrapper.eq(ApkGroupAdmin::getOwner, true);
        Map<Long, Long> owners = new HashMap<>();
        for (ApkGroupAdmin row : apkGroupAdminMapper.selectList(wrapper)) {
            if (row.getGroupId() != null && row.getUserId() != null) {
                owners.put(row.getGroupId(), row.getUserId());
            }
        }
        for (GroupVO vo : vos) {
            if (vo != null && vo.getOwnerId() == null) {
                vo.setOwnerId(owners.get(vo.getId()));
            }
        }
    }

    private List<GroupMemberVO> apkGroupAdmins(Long groupId) {
        LambdaQueryWrapper<ApkGroupAdmin> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ApkGroupAdmin::getGroupId, groupId);
        List<ApkGroupAdmin> rows = apkGroupAdminMapper.selectList(wrapper);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = rows.stream().map(ApkGroupAdmin::getUserId).filter(Objects::nonNull).distinct()
            .collect(Collectors.toList());
        Map<Long, User> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<User> found = userMapper.selectBatchIds(userIds);
            if (found != null) {
                for (User user : found) {
                    if (user != null && user.getId() != null) {
                        users.put(user.getId(), user);
                    }
                }
            }
        }
        GroupVO group = zhiquanChatStore.group(groupId);
        String name = group == null ? "" : StrUtil.blankToDefault(group.getShowGroupName(), group.getName());
        return ApkGroupAdminMembers.list(name, rows, users);
    }

    @Override
    public List<Long> findOnlineMemberIds(Long groupId) {
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(groupId);
        return imClient.getOnlineUser(userIds);
    }

    @CacheEvict(key = "#dto.id")
    @Transactional
    @Override
    public void setGroupMuted(GroupMutedDTO dto) {
        UserSession session = SessionContext.getSession();
        Group group = getAndCheckById(dto.getId());
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getId(), session.getUserId());
        if (!session.getUserId().equals(group.getOwnerId()) && !member.getIsManager()) {
            throw new GlobalException("group.noOperationPermission");
        }
        if (!group.getIsAllMuted().equals(dto.getIsMuted())) {
            group.setIsAllMuted(dto.getIsMuted());
            this.updateById(group);
            // 推送提示语
            List<Long> userIds = groupMemberService.findUserIdsByGroupId(dto.getId());
            this.sendMutedTip(dto.getId(), member, userIds, dto.getIsMuted());
            // 推送同步消息
            sendSyncMessage(dto.getId(), userIds, MessageType.GROUP_ALL_MUTED, dto.getIsMuted());
        }
    }

    @Override
    public void setMemberMuted(GroupMemberMutedDTO dto) {
        UserSession session = SessionContext.getSession();
        Group group = getAndCheckById(dto.getGroupId());
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        if (!session.getUserId().equals(group.getOwnerId()) && !member.getIsManager()) {
            throw new GlobalException("group.noOperationPermission");
        }
        if (dto.getUserIds().contains(group.getOwnerId())) {
            throw new GlobalException("group.cannotMuteOwner");
        }
        // 过滤掉禁言状态无需修改的用户，避免出现重复推送提示消息
        List<Long> userIds = groupMemberService.findMutedUserIds(dto.getGroupId(), dto.getUserIds(), !dto.getIsMuted());
        if (!userIds.isEmpty()) {
            groupMemberService.setMuted(dto.getGroupId(), userIds, dto.getIsMuted());
            List<GroupMember> members = groupMemberService.findByGroupAndUserIds(dto.getGroupId(), userIds);
            String tipKey = dto.getIsMuted() ? "tip.group.memberMuted" : "tip.group.memberUnmuted";
            List<Long> sendIds = groupMemberService.findUserIdsByGroupId(dto.getGroupId());
            this.sendTipMessage(dto.getGroupId(), sendIds, tipKey, formatUserMark(member), formatUserMarks(members));
            // 推送同步消息
            sendSyncMessage(dto.getGroupId(), userIds, MessageType.GROUP_MEMBER_MUTED, dto.getIsMuted());
        }
    }

    @Transactional
    @Override
    public void setTopMessage(Long groupId, Long messageId) {
        UserSession session = SessionContext.getSession();
        Group group = getAndCheckById(groupId);
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, session.getUserId());
        if (!session.getUserId().equals(group.getOwnerId()) && !member.getIsManager()) {
            throw new GlobalException("group.noOperationPermission");
        }
        GroupMessage message = groupMessageService.getById(messageId);
        if (Objects.isNull(message) || !message.getGroupId().equals(groupId)) {
            throw new GlobalException("groupMsg.messageNotExist");
        }
        // 更新群置顶消息id
        group.setTopMessageId(messageId);
        this.updateById(group);
        // 更新用户置顶显示状态
        groupMemberService.updateTopMessage(groupId, true);
        // 推送提示语
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(groupId);
        sendTipMessage(groupId, userIds, "tip.group.topMessage", formatUserMark(member));
        // 推送同步消息
        sendTopGroupMessage(groupId, userIds, message);
    }

    @Transactional
    @Override
    public void removeTopMessage(Long groupId) {
        UserSession session = SessionContext.getSession();
        Group group = getAndCheckById(groupId);
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, session.getUserId());
        if (!session.getUserId().equals(group.getOwnerId()) && !member.getIsManager()) {
            throw new GlobalException("group.noOperationPermission");
        }
        // 清空置顶消息
        LambdaUpdateWrapper<Group> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(Group::getId, groupId);
        wrapper.set(Group::getTopMessageId, null);
        this.update(wrapper);
        // 更新用户置顶显示状态
        groupMemberService.updateTopMessage(groupId, false);
        // 推送同步消息
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(groupId);
        sendTopGroupMessage(groupId, userIds, null);
    }

    @Override
    public void hideTopMessage(Long groupId) {
        UserSession session = SessionContext.getSession();
        // 更新用户置顶显示状态
        groupMemberService.updateTopMessage(groupId, session.getUserId(), false);
        // 推送同步消息
        sendTopGroupMessage(groupId, List.of(session.getUserId()), null);
    }

    @Override
    public void addManager(GroupManagerDTO dto) {
        UserSession session = SessionContext.getSession();
        Group group = getAndCheckById(dto.getGroupId());
        if (!session.getUserId().equals(group.getOwnerId())) {
            throw new GlobalException("group.noOperationPermission");
        }
        groupMemberService.setManager(dto.getGroupId(), dto.getUserIds(), true);
        List<GroupMember> members = groupMemberService.findByGroupAndUserIds(dto.getGroupId(), dto.getUserIds());
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(dto.getGroupId());
        this.sendTipMessage(dto.getGroupId(), userIds, "tip.group.addManager", formatUserMarks(members));
    }

    @Override
    public void removeManager(GroupManagerDTO dto) {
        UserSession session = SessionContext.getSession();
        Group group = getAndCheckById(dto.getGroupId());
        if (!session.getUserId().equals(group.getOwnerId())) {
            throw new GlobalException("group.noOperationPermission");
        }
        groupMemberService.setManager(dto.getGroupId(), dto.getUserIds(), false);
        List<GroupMember> members = groupMemberService.findByGroupAndUserIds(dto.getGroupId(), dto.getUserIds());
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(dto.getGroupId());
        this.sendTipMessage(dto.getGroupId(), userIds, "tip.group.removeManager", formatUserMarks(members));
    }

    @Override
    public void setDnd(GroupDndDTO dto) {
        UserSession session = SessionContext.getSession();
        groupMemberService.setDnd(dto.getGroupId(), session.getUserId(), dto.getIsDnd());
        mineDesk.saveGroupFlag(session.getUserId(), dto.getGroupId(), dto.getIsDnd(), null);
        // 更新缓存
        String key = StrUtil.join(":", RedisKey.IM_CACHE_GROUP_DNS, session.getUserId());
        if (dto.getIsDnd()) {
            redisTemplate.opsForSet().add(key, dto.getGroupId());
        } else {
            redisTemplate.opsForSet().remove(key, dto.getGroupId());
        }
        // 推送同步消息
        sendSyncDndMessage(dto.getGroupId(), dto.getIsDnd());
    }

    @Override
    public void initDndCache(Long userId) {
        LambdaQueryWrapper<GroupMember> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMember::getUserId, userId);
        wrapper.eq(GroupMember::getIsDnd, true);
        wrapper.select(GroupMember::getGroupId);
        List<GroupMember> members = groupMemberService.list(wrapper);
        Set<Object> ids = new LinkedHashSet<>();
        for (GroupMember member : members) {
            if (member.getGroupId() != null) {
                ids.add(member.getGroupId());
            }
        }
        ids.addAll(mineDesk.dndPeers(userId, MineDeskService.GROUP));
        String key = StrUtil.join(":", RedisKey.IM_CACHE_GROUP_DNS, userId);
        redisTemplate.delete(key);
        if (!ids.isEmpty()) {
            redisTemplate.opsForSet().add(key, ids.toArray());
        }
    }

    @Override
    public Boolean isDnd(Long userId, Long groupId) {
        String key = StrUtil.join(":", RedisKey.IM_CACHE_GROUP_DNS, userId);
        return redisTemplate.opsForSet().isMember(key, groupId);
    }

    @Override
    public void setTop(GroupTopDTO dto) {
        UserSession session = SessionContext.getSession();
        groupMemberService.setTop(dto.getGroupId(), session.getUserId(), dto.getIsTop());
        mineDesk.saveGroupFlag(session.getUserId(), dto.getGroupId(), null, dto.getIsTop());
        // 推送同步消息
        sendSyncTopMessage(dto.getGroupId(), dto.getIsTop());
    }

    @Override
    public void setAllowInvite(GroupAllowInviteDTO dto) {
        UserSession session = SessionContext.getSession();
        Group group = getAndCheckById(dto.getGroupId());
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        if (!session.getUserId().equals(group.getOwnerId()) && !member.getIsManager()) {
            throw new GlobalException("group.noOperationPermission");
        }
        LambdaUpdateWrapper<Group> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(Group::getId, dto.getGroupId());
        wrapper.set(Group::getIsAllowInvite, dto.getIsAllowInvite());
        this.update(wrapper);
    }

    @Override
    public void setAllowShareCard(GroupAllowShareCardDTO dto) {
        UserSession session = SessionContext.getSession();
        Group group = getAndCheckById(dto.getGroupId());
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        if (!session.getUserId().equals(group.getOwnerId()) && !member.getIsManager()) {
            throw new GlobalException("group.noOperationPermission");
        }
        LambdaUpdateWrapper<Group> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(Group::getId, dto.getGroupId());
        wrapper.set(Group::getIsAllowShareCard, dto.getIsAllowShareCard());
        this.update(wrapper);
    }

    private void sendMutedTip(Long groupId, GroupMember member, List<Long> userIds, Boolean isMuted) {
        String tipKey = isMuted ? "tip.group.mutedAll" : "tip.group.unmutedAll";
        this.sendTipMessage(groupId, userIds, tipKey, formatUserMark(member));
    }

    private String formatUserMark(Long userId, String displayName) {
        // 格式：#{displayName:userId}，例如：#{张三:123}
        return String.format("#{%s:%d}", displayName, userId);
    }

    private String formatUserMark(GroupMember member) {
        // 格式：#{displayName:userId}，例如：#{张三:123}
        return String.format("#{%s:%d}", member.getShowNickName(), member.getUserId());
    }

    private String formatUserMarks(List<GroupMember> members) {
        // 格式：#{displayName1:userId1},#{displayName2:userId2}，例如：#{张三:123},#{李四:456}
        return members.stream().map(this::formatUserMark).collect(Collectors.joining(","));
    }

    private void sendTipMessage(Long groupId, List<Long> recvIds, String tipKey, Object... params) {
        UserSession session = SessionContext.getSession();
        String content = TipContentUtil.buildTipContent(tipKey, params);
        // 消息入库
        GroupMessage message = new GroupMessage();
        message.setLocalId(IdWorker.getIdStr());
        message.setContent(content);
        message.setType(MessageType.TIP_TEXT.code());
        message.setStatus(MessageStatus.PENDING.code());
        message.setSendTime(new Date());
        message.setGroupId(groupId);
        message.setSendId(session.getUserId());
        groupMessageService.saveMessage(message);
        // 推送
        GroupMessageVO msgInfo = BeanUtils.copyProperties(message, GroupMessageVO.class);
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        sendMessage.setSendToSelf(false);
        imClient.sendGroupMessage(sendMessage);
    }

    private void sendAddGroupMessage(GroupVO group, List<Long> recvIds) {
        UserSession session = SessionContext.getSession();
        sendAddGroupMessage(group, recvIds, session.getUserId());
    }

    private void sendAddGroupMessage(GroupVO group, List<Long> recvIds, Long senderId) {
        GroupMessageVO msgInfo = new GroupMessageVO();
        msgInfo.setContent(JSON.toJSONString(group));
        msgInfo.setType(MessageType.GROUP_NEW.code());
        msgInfo.setSendTime(new Date());
        msgInfo.setGroupId(group.getId());
        msgInfo.setSendId(senderId);
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(senderId, IMTerminalType.WEB.code()));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        sendMessage.setSendToSelf(true);
        imClient.sendGroupMessage(sendMessage);
    }

    private void sendDelGroupMessage(Long groupId, List<Long> recvIds) {
        UserSession session = SessionContext.getSession();
        GroupMessageVO msgInfo = new GroupMessageVO();
        msgInfo.setType(MessageType.GROUP_DEL.code());
        msgInfo.setSendTime(new Date());
        msgInfo.setGroupId(groupId);
        msgInfo.setSendId(session.getUserId());
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        sendMessage.setSendToSelf(false);
        imClient.sendGroupMessage(sendMessage);
    }

    private void sendSyncDndMessage(Long groupId, Boolean isDnd) {
        UserSession session = SessionContext.getSession();
        GroupMessageVO msgInfo = new GroupMessageVO();
        msgInfo.setType(MessageType.GROUP_DND.code());
        msgInfo.setSendTime(new Date());
        msgInfo.setGroupId(groupId);
        msgInfo.setSendId(session.getUserId());
        msgInfo.setContent(isDnd.toString());
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        imClient.sendGroupMessage(sendMessage);
    }

    private void sendSyncTopMessage(Long groupId, Boolean isTop) {
        UserSession session = SessionContext.getSession();
        GroupMessageVO msgInfo = new GroupMessageVO();
        msgInfo.setType(MessageType.GROUP_TOP.code());
        msgInfo.setSendTime(new Date());
        msgInfo.setGroupId(groupId);
        msgInfo.setSendId(session.getUserId());
        msgInfo.setContent(isTop.toString());
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        imClient.sendGroupMessage(sendMessage);
    }

    private void sendTopGroupMessage(Long groupId, List<Long> recvIds, GroupMessage message) {
        UserSession session = SessionContext.getSession();
        GroupMessageVO msgInfo = new GroupMessageVO();
        msgInfo.setType(MessageType.GROUP_TOP_MESSAGE.code());
        msgInfo.setSendTime(new Date());
        msgInfo.setGroupId(groupId);
        msgInfo.setSendId(session.getUserId());
        if (!Objects.isNull(message)) {
            GroupMessageVO topMessage = BeanUtils.copyProperties(message, GroupMessageVO.class);
            msgInfo.setContent(JSON.toJSONString(topMessage));
        }
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        sendMessage.setSendToSelf(false);
        imClient.sendGroupMessage(sendMessage);
    }

    private void sendSyncMessage(Long groupId, List<Long> recvIds, MessageType type, Object content) {
        UserSession session = SessionContext.getSession();
        GroupMessageVO msgInfo = new GroupMessageVO();
        msgInfo.setType(type.code());
        msgInfo.setSendTime(new Date());
        msgInfo.setGroupId(groupId);
        msgInfo.setSendId(session.getUserId());
        msgInfo.setContent(JSON.toJSONString(content));
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvIds(recvIds);
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        sendMessage.setSendToSelf(false);
        imClient.sendGroupMessage(sendMessage);
    }

    private GroupVO convert(Group group, GroupMember member) {
        GroupVO vo = BeanUtils.copyProperties(group, GroupVO.class);
        if (!Objects.isNull(member)) {
            vo.setRemarkGroupName(member.getRemarkGroupName());
            vo.setRemarkNickName(member.getRemarkNickName());
            vo.setShowNickName(member.getShowNickName());
            vo.setShowGroupName(StrUtil.blankToDefault(member.getRemarkGroupName(), group.getName()));
            vo.setQuit(member.getQuit());
            vo.setIsDnd(member.getIsDnd());
            vo.setIsTop(member.getIsTop());
            vo.setIsMuted(member.getIsMuted());
            vo.setVersion(member.getVersion());
        } else {
            vo.setShowGroupName(group.getName());
            vo.setQuit(true);
            vo.setIsDnd(false);
            vo.setIsTop(false);
            vo.setIsMuted(false);
            vo.setVersion(0L);
        }
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LiveGroupCreateVO createLivePublicGroup(Long ownerUserId, String name) {
        User user = userMapper.selectById(ownerUserId);
        if (Objects.isNull(user)) {
            throw new GlobalException(400, "主播用户未在 IM 注册，请先登录一次 78聊/IM");
        }
        String groupName = StrUtil.sub(name, 0, 32);
        Group group = new Group();
        group.setName(groupName);
        group.setOwnerId(ownerUserId);
        group.setIsAllowInvite(true);
        group.setIsAllowShareCard(true);
        group.setIsAllMuted(false);
        group.setDissolve(false);
        group.setCreatedTime(new Date());
        this.save(group);

        GroupMember member = new GroupMember();
        member.setGroupId(group.getId());
        member.setUserId(user.getId());
        member.setUserNickName(user.getNickName());
        member.setHeadImage(user.getHeadImageThumb());
        member.setCompanyName(user.getCompanyName());
        member.setCreatedTime(new Date());
        member.setIsManager(false);
        member.setQuit(false);
        member.setIsMuted(false);
        member.setIsDnd(false);
        member.setIsTop(false);
        groupMemberService.save(member);

        String token = UUID.randomUUID().toString().replace("-", "");
        String key = StrUtil.join(":", RedisKey.IM_GROUP_LIVE_JOIN, group.getId());
        try {
            redisTemplate.opsForValue().set(key, token);
        } catch (Exception e) {
            log.error("写入直播间加群 token 失败 groupId={}", group.getId(), e);
            throw new GlobalException(500, "Redis 不可用，无法创建直播间群");
        }

        LiveGroupCreateVO vo = new LiveGroupCreateVO();
        vo.setGroupId(group.getId());
        vo.setJoinToken(token);
        GroupVO groupVo = convert(group, member);
        sendAddGroupMessage(groupVo, List.of(ownerUserId), ownerUserId);
        log.info("创建直播间公开群 groupId={} ownerId={} name={}", group.getId(), ownerUserId, groupName);
        return vo;
    }

    @Override
    public GroupVO joinAsUser(Long userId, GroupJoinDTO dto) {
        User user = userMapper.selectById(userId);
        if (Objects.isNull(user)) {
            throw new GlobalException("user.notFound");
        }
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setUserName(user.getUserName());
        session.setNickName(user.getNickName());
        session.setTerminal(0);
        SessionContext.setSession(session);
        try {
            return join(dto);
        } finally {
            SessionContext.clear();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void ensureLiveGroupOwnerMember(Long groupId, Long ownerUserId, String joinToken) {
        Group group = getAndCheckById(groupId);
        if (!group.getOwnerId().equals(ownerUserId)) {
            throw new GlobalException(403, "仅群主可恢复入群");
        }
        User user = userMapper.selectById(ownerUserId);
        if (Objects.isNull(user)) {
            throw new GlobalException("user.notFound");
        }
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, ownerUserId);
        if (Objects.isNull(member)) {
            member = new GroupMember();
        }
        member.setGroupId(groupId);
        member.setUserId(user.getId());
        member.setUserNickName(user.getNickName());
        member.setHeadImage(user.getHeadImageThumb());
        member.setCompanyName(user.getCompanyName());
        if (member.getCreatedTime() == null) {
            member.setCreatedTime(new Date());
        }
        member.setIsManager(false);
        member.setQuit(false);
        member.setQuitTime(null);
        member.setIsMuted(false);
        groupMemberService.saveOrUpdate(member);

        syncLiveJoinToken(groupId, joinToken);
        log.info("恢复直播间群主入群 groupId={} ownerId={}", groupId, ownerUserId);
    }

    private void syncLiveJoinToken(Long groupId, String joinToken) {
        if (!StrUtil.isNotBlank(joinToken)) {
            return;
        }
        String key = StrUtil.join(":", RedisKey.IM_GROUP_LIVE_JOIN, groupId);
        try {
            Object stored = redisTemplate.opsForValue().get(key);
            if (stored == null || !joinToken.trim().equals(stored.toString())) {
                redisTemplate.opsForValue().set(key, joinToken.trim());
                log.info("同步直播间加群 token groupId={}", groupId);
            }
        } catch (Exception e) {
            log.warn("同步直播间加群 token 失败 groupId={}", groupId, e);
        }
    }
}
