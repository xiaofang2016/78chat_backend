package com.bx.implatform.controller;

import com.bx.implatform.dto.LuckyBonusApplyDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.LuckyBonusService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.vo.LuckyBonusApplyResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "活动领取", description = "AI 客服触发：幸运加码 / 天天好运金领取")
@RestController
@RequestMapping("/lucky-bonus")
@RequiredArgsConstructor
public class LuckyBonusController {

    private final LuckyBonusService luckyBonusService;

    @PostMapping("/apply")
    @Operation(summary = "领取活动彩金", description = "用户点击 AI 下发的活动领取按钮后调用")
    public Result<LuckyBonusApplyResultVO> apply(@Valid @RequestBody LuckyBonusApplyDTO dto) {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(luckyBonusService.apply(userId, dto));
    }
}
