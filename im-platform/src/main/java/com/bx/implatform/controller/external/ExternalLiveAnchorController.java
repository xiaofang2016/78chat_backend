package com.bx.implatform.controller.external;

import com.bx.implatform.client.LivePlatformClient;
import com.bx.implatform.dto.external.LiveAnchorStrangerPmDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "外部直播间主播", description = "live-api ⇐ box-im（HMAC 鉴权）")
@RestController
@RequestMapping("/external/live/anchor")
@RequiredArgsConstructor
public class ExternalLiveAnchorController {

    private final LivePlatformClient livePlatformClient;

    @PutMapping("/{userId}/stranger-pm")
    @Operation(summary = "同步主播陌生人私聊资格缓存（审核通过/撤销时由 live-api 调用）")
    public Result<Void> syncStrangerPm(@PathVariable Long userId,
        @Valid @RequestBody LiveAnchorStrangerPmDTO dto) {
        livePlatformClient.putAnchorPmAllowed(userId, Boolean.TRUE.equals(dto.getAllowed()));
        return ResultUtils.success();
    }
}
