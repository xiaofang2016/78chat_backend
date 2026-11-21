package com.bx.implatform.controller.external;

import com.bx.implatform.dto.external.RedpacketPushDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.external.ExternalRedpacketService;
import com.bx.implatform.vo.external.RedpacketPushVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * box-im 暴露给 aagames_api 的红包对外接口。
 *
 * 入口路径以 /external/ 开头，由 BoximInboundHmacFilter 完成 HMAC 校验，
 * 不经过普通用户 AuthInterceptor。
 */
@Tag(name = "外部红包接口", description = "box-im ⇐ aagames_api（HMAC 鉴权）")
@RestController
@RequestMapping("/external/redpacket")
@RequiredArgsConstructor
public class ExternalRedpacketController {

    private final ExternalRedpacketService externalRedpacketService;

    @PostMapping("/push")
    @Operation(summary = "接口①：红包派发", description = "第三方推送红包到 box-im，机器人在群里发卡片消息")
    public Result<RedpacketPushVO> push(@Valid @RequestBody RedpacketPushDTO dto) {
        return ResultUtils.success(externalRedpacketService.push(dto));
    }
}
