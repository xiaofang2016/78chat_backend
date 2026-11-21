package com.bx.implatform.controller;

import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.dto.AiChatDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.AiCustomerService;
import com.bx.implatform.session.SessionContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "AI客服")
@RestController
@RequestMapping("/ai/cs")
@RequiredArgsConstructor
public class AiCustomerServiceController {

    private final AiCustomerService aiCustomerService;
    private final AiCustomerServiceProperties aiCsProps;

    @PostMapping("/chat")
    @Operation(summary = "AI客服对话（调试用）", description = "直接调用 OpenAI 生成回复")
    public Result<String> chat(@Valid @RequestBody AiChatDTO dto) {
        Long userId = SessionContext.getSession().getUserId();
        Long botUserId = aiCsProps.getBotUserId();
        return ResultUtils.success(aiCustomerService.reply(userId, botUserId, dto.getContent()).getText());
    }
}

