package com.bx.implatform.controller;

import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.SportPublicChatService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.vo.SportChatSessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "说球帝公开群")
@RestController
@RequestMapping("/sport/chat")
@RequiredArgsConstructor
public class SportPublicChatController {

    private final SportPublicChatService sportPublicChatService;

    @PostMapping("/enter")
    @Operation(summary = "进入说球帝公开群",
        description = "没有群时自动创建。请求体可带 matchId，有则按比赛分房间；不传仍进总群「说球帝」。")
    public Result<SportChatSessionVO> enter(@RequestBody(required = false) Map<String, Object> body) {
        Long matchId = null;
        if (body != null && body.get("matchId") != null) {
            try {
                matchId = Long.valueOf(String.valueOf(body.get("matchId")));
            } catch (NumberFormatException ignored) {
                matchId = null;
            }
        }
        return ResultUtils.success(sportPublicChatService.enter(SessionContext.getSession(), matchId));
    }
}
