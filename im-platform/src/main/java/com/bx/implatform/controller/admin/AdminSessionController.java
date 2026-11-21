package com.bx.implatform.controller.admin;

import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.util.KnowledgeIngestBotVisibility;
import com.bx.implatform.vo.AdminSessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台-会话")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminSessionController {

    private final AiCustomerServiceProperties aiCustomerServiceProperties;

    @GetMapping("/entry-visible")
    @Operation(summary = "IM 客户端是否展示管理后台入口", description = "任意登录用户可调用，仅返回是否有权限")
    public Result<Boolean> entryVisible() {
        var session = SessionContext.getSession();
        boolean v = KnowledgeIngestBotVisibility.isIngestAdmin(
            aiCustomerServiceProperties, session.getUserId());
        return ResultUtils.success(v);
    }

    @GetMapping("/me")
    @Operation(summary = "当前用户管理后台权限")
    public Result<AdminSessionVO> me() {
        var session = SessionContext.getSession();
        AdminSessionVO vo = new AdminSessionVO();
        vo.setUserId(session.getUserId());
        vo.setUserName(session.getUserName());
        vo.setNickName(session.getNickName());
        vo.setAdmin(KnowledgeIngestBotVisibility.isIngestAdmin(
            aiCustomerServiceProperties, session.getUserId()));
        return ResultUtils.success(vo);
    }
}
