package com.bx.implatform.controller;

import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.AagamesPortalService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.vo.AagamesPortalVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * IM 已登录 → 获取 aagames token → 打开 7PG H5（?at=）。
 */
@Tag(name = "7PG 门户")
@RestController
@RequiredArgsConstructor
public class AagamesPortalController {

    private final AagamesPortalService aagamesPortalService;

    @PostMapping("/user/aagames/portal")
    @Operation(summary = "打开 7PG H5 门户",
        description = "需 box-im JWT。返回 aagames token 与带 ?at= 的 H5 URL，H5 可直接写入 Cookie 访问 user_api。"
            + "说球帝账号打开吉祥或 UED 时传 program（sports-jixiang-1 / sports-ued-1）或 brand（jxt / ued）。")
    public Result<AagamesPortalVO> portal(@RequestBody(required = false) Map<String, String> body) {
        Long userId = SessionContext.getSession().getUserId();
        String program = body == null ? "" : body.get("program");
        String brand = body == null ? "" : body.get("brand");
        AagamesPortalVO vo = aagamesPortalService.launch(userId, program, brand);
        return ResultUtils.success(vo);
    }
}
