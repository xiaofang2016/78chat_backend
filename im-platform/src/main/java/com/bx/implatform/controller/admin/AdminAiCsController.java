package com.bx.implatform.controller.admin;

import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.dto.AiCsAdminReplyDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.entity.User;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.service.AiCsHandoffService;
import com.bx.implatform.util.AdminGuard;
import com.bx.implatform.vo.AiCsBotVO;
import com.bx.implatform.vo.AiCsSessionVO;
import com.bx.implatform.vo.PrivateMessageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Tag(name = "管理后台-AI客服工作台")
@RestController
@RequestMapping("/admin/ai-cs")
@RequiredArgsConstructor
public class AdminAiCsController {

    private final AiCsHandoffService handoffService;
    private final AdminGuard adminGuard;
    private final AiCustomerServiceProperties aiCsProps;
    private final UserMapper userMapper;

    @GetMapping("/bots")
    @Operation(summary = "可管理的 AI 客服机器人列表（含 im_user.nick_name）")
    public Result<List<AiCsBotVO>> bots() {
        adminGuard.requireAdmin();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (aiCsProps.getBotUserId() != null && aiCsProps.getBotUserId() > 0) {
            ids.add(aiCsProps.getBotUserId());
        }
        if (aiCsProps.getBotUserIds() != null) {
            aiCsProps.getBotUserIds().stream().filter(id -> id != null && id > 0).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            return ResultUtils.success(List.of());
        }
        List<User> users = userMapper.selectBatchIds(ids);
        Map<Long, User> userMap = users == null ? Map.of() : users.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a, LinkedHashMap::new));

        List<AiCsBotVO> out = new ArrayList<>();
        for (Long id : ids) {
            AiCsBotVO vo = new AiCsBotVO();
            vo.setId(id);
            User u = userMap.get(id);
            if (u != null) {
                vo.setNickName(u.getNickName());
                vo.setUserName(u.getUserName());
            }
            out.add(vo);
        }
        return ResultUtils.success(out);
    }

    @GetMapping("/sessions")
    @Operation(summary = "会话列表", description = "filter: all / human / pending")
    public Result<List<AiCsSessionVO>> sessions(
        @RequestParam("botUserId") Long botUserId,
        @RequestParam(value = "filter", defaultValue = "all") String filter,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "size", defaultValue = "30") int size) {
        adminGuard.requireAdmin();
        return ResultUtils.success(handoffService.listSessions(botUserId, filter, page, size));
    }

    @GetMapping("/sessions/{customerUserId}/messages")
    @Operation(summary = "会话聊天记录")
    public Result<List<PrivateMessageVO>> messages(
        @RequestParam("botUserId") Long botUserId,
        @PathVariable("customerUserId") Long customerUserId,
        @RequestParam(value = "beforeId", required = false) Long beforeId,
        @RequestParam(value = "limit", defaultValue = "50") int limit) {
        adminGuard.requireAdmin();
        return ResultUtils.success(handoffService.listMessages(botUserId, customerUserId, beforeId, limit));
    }

    @PostMapping("/sessions/{customerUserId}/takeover")
    @Operation(summary = "接管会话（互斥锁）")
    public Result<Void> takeover(
        @RequestParam("botUserId") Long botUserId,
        @PathVariable("customerUserId") Long customerUserId) {
        adminGuard.requireAdmin();
        handoffService.takeover(botUserId, customerUserId, adminGuard.currentUserId());
        return ResultUtils.success();
    }

    @PostMapping("/sessions/{customerUserId}/release")
    @Operation(summary = "交还 AI")
    public Result<Void> release(
        @RequestParam("botUserId") Long botUserId,
        @PathVariable("customerUserId") Long customerUserId) {
        adminGuard.requireAdmin();
        handoffService.release(botUserId, customerUserId, adminGuard.currentUserId());
        return ResultUtils.success();
    }

    @PostMapping("/sessions/{customerUserId}/handoff")
    @Operation(summary = "手动转人工并接管")
    public Result<Void> manualHandoff(
        @RequestParam("botUserId") Long botUserId,
        @PathVariable("customerUserId") Long customerUserId) {
        adminGuard.requireAdmin();
        handoffService.manualHandoff(botUserId, customerUserId, adminGuard.currentUserId());
        return ResultUtils.success();
    }

    @PostMapping("/sessions/{customerUserId}/typing")
    @Operation(summary = "管理员正在输入（心跳）")
    public Result<Void> typing(
        @RequestParam("botUserId") Long botUserId,
        @PathVariable("customerUserId") Long customerUserId) {
        adminGuard.requireAdmin();
        handoffService.reportAdminTyping(botUserId, customerUserId, adminGuard.currentUserId());
        return ResultUtils.success();
    }

    @PostMapping("/watch")
    @Operation(summary = "工作台在线监控心跳", description = "进入机器人工作台或选中会话时调用；customerUserId 可选")
    public Result<Void> watch(
        @RequestParam("botUserId") Long botUserId,
        @RequestParam(value = "customerUserId", required = false) Long customerUserId) {
        adminGuard.requireAdmin();
        handoffService.reportAdminWatching(botUserId, customerUserId, adminGuard.currentUserId());
        return ResultUtils.success();
    }

    @PostMapping("/reply")
    @Operation(summary = "以机器人身份代发", description = "未接管时可临时代发（仅取消当次 AI）；已接管须为当前接管客服")
    public Result<Long> reply(@Valid @RequestBody AiCsAdminReplyDTO dto) {
        adminGuard.requireAdmin();
        Long id = handoffService.replyAsBot(
            dto.getBotUserId(), dto.getCustomerUserId(),
            adminGuard.currentUserId(), dto.getContent(), dto.getType(), dto.getLocalId());
        return ResultUtils.success(id);
    }
}
