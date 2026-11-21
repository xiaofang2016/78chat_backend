package com.bx.implatform.service.external.impl;

import com.bx.implatform.dto.GroupJoinDTO;
import com.bx.implatform.dto.external.LiveGroupCreateDTO;
import com.bx.implatform.dto.external.LiveGroupEnsureOwnerDTO;
import com.bx.implatform.dto.external.LiveGroupJoinDTO;
import com.bx.implatform.dto.external.LiveGroupGuestSendDTO;
import com.bx.implatform.dto.external.LiveGroupPresenceSendDTO;
import com.bx.implatform.dto.external.LiveGroupPromoSendDTO;
import com.bx.implatform.enums.BizTokenType;
import com.bx.implatform.service.GroupMessageService;
import com.bx.implatform.service.GroupService;
import com.bx.implatform.service.external.ExternalLiveGroupService;
import com.bx.implatform.vo.BizTokenVO;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.external.LiveGroupCreateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ExternalLiveGroupServiceImpl implements ExternalLiveGroupService {

    private final GroupService groupService;
    private final GroupMessageService groupMessageService;

    @Override
    public LiveGroupCreateVO create(LiveGroupCreateDTO dto) {
        return groupService.createLivePublicGroup(dto.getOwnerUserId(), dto.getName().trim());
    }

    @Override
    public void join(LiveGroupJoinDTO dto) {
        GroupJoinDTO join = new GroupJoinDTO();
        join.setGroupId(dto.getGroupId());
        BizTokenVO token = new BizTokenVO();
        token.setToken(dto.getJoinToken());
        token.setType(BizTokenType.GROUP_LIVE_PUBLIC.getCode());
        join.setToken(token);
        groupService.joinAsUser(dto.getUserId(), join);
    }

    @Override
    public void ensureOwnerMember(Long groupId, LiveGroupEnsureOwnerDTO dto) {
        groupService.ensureLiveGroupOwnerMember(groupId, dto.getUserId(), dto.getJoinToken());
    }

    @Override
    public List<GroupMessageVO> guestMessages(Long groupId, int limit) {
        return groupMessageService.loadGuestMessages(groupId, limit);
    }

    @Override
    public GroupMessageVO sendPromo(Long groupId, LiveGroupPromoSendDTO dto) {
        return groupMessageService.sendLivePromoMessage(groupId, dto.getContentHtml().trim());
    }

    @Override
    public GroupMessageVO sendPresence(Long groupId, LiveGroupPresenceSendDTO dto) {
        return groupMessageService.sendLivePresenceMessage(
            groupId, dto.getNick().trim(), dto.getAction().trim(), dto.getViewerCount());
    }

    @Override
    public GroupMessageVO sendGuestMessage(Long groupId, LiveGroupGuestSendDTO dto) {
        return groupMessageService.sendLiveGuestMessage(groupId, dto.getGuestNick().trim(), dto.getContent().trim());
    }
}
