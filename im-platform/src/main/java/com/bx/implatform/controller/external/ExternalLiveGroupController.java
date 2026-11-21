package com.bx.implatform.controller.external;

import com.bx.implatform.dto.external.LiveGroupCreateDTO;
import com.bx.implatform.dto.external.LiveGroupEnsureOwnerDTO;
import com.bx.implatform.dto.external.LiveGroupJoinDTO;
import com.bx.implatform.dto.external.LiveGroupGuestSendDTO;
import com.bx.implatform.dto.external.LiveGroupPresenceSendDTO;
import com.bx.implatform.dto.external.LiveGroupPromoSendDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.external.ExternalLiveGroupService;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.external.LiveGroupCreateVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "外部直播间群聊", description = "live-api ⇐ box-im（HMAC 鉴权）")
@RestController
@RequestMapping("/external/live/group")
@RequiredArgsConstructor
public class ExternalLiveGroupController {

    private final ExternalLiveGroupService externalLiveGroupService;

    @PostMapping("/create")
    @Operation(summary = "创建直播间公开群")
    public Result<LiveGroupCreateVO> create(@Valid @RequestBody LiveGroupCreateDTO dto) {
        return ResultUtils.success(externalLiveGroupService.create(dto));
    }

    @PostMapping("/join")
    @Operation(summary = "代用户加入直播间群")
    public Result<Void> join(@Valid @RequestBody LiveGroupJoinDTO dto) {
        externalLiveGroupService.join(dto);
        return ResultUtils.success();
    }

    @PostMapping("/{groupId}/ensure-owner")
    @Operation(summary = "确保直播间群主在群内")
    public Result<Void> ensureOwner(@PathVariable Long groupId,
        @Valid @RequestBody LiveGroupEnsureOwnerDTO dto) {
        externalLiveGroupService.ensureOwnerMember(groupId, dto);
        return ResultUtils.success();
    }

    @GetMapping("/{groupId}/messages")
    @Operation(summary = "游客只读群消息")
    public Result<List<GroupMessageVO>> messages(@PathVariable Long groupId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ResultUtils.success(externalLiveGroupService.guestMessages(groupId, limit));
    }

    @PostMapping("/{groupId}/promo")
    @Operation(summary = "推送直播间推广文案到群聊")
    public Result<GroupMessageVO> sendPromo(@PathVariable Long groupId,
        @Valid @RequestBody LiveGroupPromoSendDTO dto) {
        return ResultUtils.success(externalLiveGroupService.sendPromo(groupId, dto));
    }

    @PostMapping("/{groupId}/presence")
    @Operation(summary = "推送直播间进出提示到群聊")
    public Result<GroupMessageVO> sendPresence(@PathVariable Long groupId,
        @Valid @RequestBody LiveGroupPresenceSendDTO dto) {
        return ResultUtils.success(externalLiveGroupService.sendPresence(groupId, dto));
    }

    @PostMapping("/{groupId}/guest-message")
    @Operation(summary = "推送直播间游客发言到群聊")
    public Result<GroupMessageVO> sendGuestMessage(@PathVariable Long groupId,
        @Valid @RequestBody LiveGroupGuestSendDTO dto) {
        return ResultUtils.success(externalLiveGroupService.sendGuestMessage(groupId, dto));
    }
}
