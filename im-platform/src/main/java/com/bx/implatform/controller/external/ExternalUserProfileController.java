package com.bx.implatform.controller.external;

import com.bx.implatform.dto.external.UserProfileSyncDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.external.ExternalUserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "外部用户资料", description = "box-im ⇐ aagames_api（HMAC 鉴权）")
@RestController
@RequestMapping("/external/user/profile")
@RequiredArgsConstructor
public class ExternalUserProfileController {

    private final ExternalUserProfileService externalUserProfileService;

    @PostMapping("/sync")
    @Operation(summary = "同步用户资料", description = "aagames 保存姓名/手机后推送到 im_user.phone 与 im_realname_auth")
    public Result<Void> sync(@Valid @RequestBody UserProfileSyncDTO dto) {
        externalUserProfileService.sync(dto);
        return ResultUtils.success();
    }
}
