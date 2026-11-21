package com.bx.implatform.service.external;

import com.bx.implatform.dto.external.LiveGroupCreateDTO;
import com.bx.implatform.dto.external.LiveGroupEnsureOwnerDTO;
import com.bx.implatform.dto.external.LiveGroupJoinDTO;
import com.bx.implatform.dto.external.LiveGroupGuestSendDTO;
import com.bx.implatform.dto.external.LiveGroupPresenceSendDTO;
import com.bx.implatform.dto.external.LiveGroupPromoSendDTO;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.external.LiveGroupCreateVO;

import java.util.List;

public interface ExternalLiveGroupService {

    LiveGroupCreateVO create(LiveGroupCreateDTO dto);

    void join(LiveGroupJoinDTO dto);

    void ensureOwnerMember(Long groupId, LiveGroupEnsureOwnerDTO dto);

    List<GroupMessageVO> guestMessages(Long groupId, int limit);

    GroupMessageVO sendPromo(Long groupId, LiveGroupPromoSendDTO dto);

    GroupMessageVO sendPresence(Long groupId, LiveGroupPresenceSendDTO dto);

    GroupMessageVO sendGuestMessage(Long groupId, LiveGroupGuestSendDTO dto);
}
