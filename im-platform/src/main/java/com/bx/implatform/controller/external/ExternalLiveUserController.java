package com.bx.implatform.controller.external;

import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.external.ExternalLiveUserService;
import com.bx.implatform.vo.external.LiveUserDisplayVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "外部直播间用户", description = "live-api ⇐ box-im（HMAC 鉴权）")
@RestController
@RequestMapping("/external/live/user")
@RequiredArgsConstructor
public class ExternalLiveUserController {

    private final ExternalLiveUserService externalLiveUserService;

    @GetMapping("/{userId}/display")
    @Operation(summary = "查询用户对外展示名（IM 昵称）")
    public Result<LiveUserDisplayVO> display(@PathVariable Long userId) {
        return ResultUtils.success(externalLiveUserService.display(userId));
    }
}
