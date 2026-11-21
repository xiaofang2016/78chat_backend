package com.bx.implatform.controller;

import com.bx.implatform.dto.RedpacketSendDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.UserRedpacketService;
import com.bx.implatform.service.impl.MineDeskService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.external.UserDailyQuotaVO;
import com.bx.implatform.vo.redpacket.RedpacketClaimResultVO;
import com.bx.implatform.vo.redpacket.RedpacketDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户视角的红包接口（前端 → box-im → aagames），需要 box-im JWT。
 * 详见《红包与晒图对接接口规范-v1.1》§4.3 / §4.4 / §4.5。
 */
@Tag(name = "红包接口", description = "群聊红包：领取 / 详情 / 当日额度")
@RestController
@RequestMapping("/redpacket")
@RequiredArgsConstructor
public class RedpacketController {

    private final UserRedpacketService userRedpacketService;
    private final MineDeskService mineDesk;

    @PostMapping("/send")
    @Operation(summary = "发红包", description = "群里的普通人、群主、管理员都可以发。金额从当前登录商户钱包扣除。")
    public Result<GroupMessageVO> send(@Valid @RequestBody RedpacketSendDTO dto) {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(userRedpacketService.send(userId, dto));
    }

    @PostMapping("/{redpacketId}/claim")
    @Operation(summary = "领取红包", description = "对应规范 §4.3 接口③")
    public Result<RedpacketClaimResultVO> claim(@PathVariable("redpacketId") Long redpacketId) {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(userRedpacketService.claim(userId, redpacketId));
    }

    @GetMapping("/{redpacketId}/records")
    @Operation(summary = "红包详情 / 领取列表", description = "对应规范 §4.4 接口④")
    public Result<RedpacketDetailVO> records(@PathVariable("redpacketId") Long redpacketId) {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(userRedpacketService.records(userId, redpacketId));
    }

    @GetMapping("/open")
    @Operation(summary = "群里还没领完的红包", description = "领取中且未过期。claimed 表示当前用户是否已领")
    public Result<java.util.List<java.util.Map<String, Object>>> open(@RequestParam("groupId") Long groupId) {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(mineDesk.openRedpackets(userId, groupId));
    }

    @GetMapping("/summary")
    @Operation(summary = "我发出和领到的红包合计")
    public Result<java.util.Map<String, Object>> summary() {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(mineDesk.redpacketSummary(userId));
    }

    @GetMapping("/quota")
    @Operation(summary = "用户当日红包额度", description = "对应规范 §4.5 接口⑤；v1.1 阶段2 远端写死 dailyLimit=888")
    public Result<UserDailyQuotaVO> quota(
        @Parameter(description = "yyyy-MM-dd，省略默认今天") @RequestParam(value = "date", required = false) String date) {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(userRedpacketService.quota(userId, date));
    }
}
