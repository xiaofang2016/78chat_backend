package com.bx.implatform.controller;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.bx.implatform.annotation.RepeatSubmit;
import com.bx.implatform.config.props.JwtProperties;
import com.bx.implatform.dto.UserTalkSettingsDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.TalkLinkService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.session.UserSession;
import com.bx.imcommon.util.JwtUtil;
import com.bx.implatform.vo.TalkProfileVO;
import com.bx.implatform.vo.UserTalkSettingsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "公开聊天链接")
@RestController
@RequestMapping("/talk")
@RequiredArgsConstructor
public class TalkController {

    private final TalkLinkService talkLinkService;
    private final JwtProperties jwtProperties;

    @GetMapping("/{slug}")
    @Operation(summary = "解析公开聊天链接", description = "根据链接标识获取用户公开资料，无需登录")
    public Result<TalkProfileVO> resolve(@PathVariable String slug, HttpServletRequest request) {
        return ResultUtils.success(talkLinkService.resolveProfile(slug, resolveViewerId(request)));
    }

    /** 本接口免 AuthInterceptor，若请求头带有效 accessToken 则识别当前访客 */
    private Long resolveViewerId(HttpServletRequest request) {
        String token = request.getHeader("accessToken");
        if (StrUtil.isBlank(token)) {
            return null;
        }
        try {
            token = java.net.URLDecoder.decode(token, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 使用原始 token
        }
        if (!JwtUtil.checkSign(token, jwtProperties.getAccessTokenSecret())) {
            return null;
        }
        String strJson = JwtUtil.getInfo(token);
        UserSession userSession = JSON.parseObject(strJson, UserSession.class);
        return userSession == null ? null : userSession.getUserId();
    }
}
