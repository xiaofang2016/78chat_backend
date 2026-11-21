package com.bx.implatform.task;

import com.bx.implatform.service.AiBotOnlineService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期刷新 AI 机器人在线占位，防止 Redis 键被意外清理后显示离线。
 */
@Component
@RequiredArgsConstructor
public class AiBotOnlineRefreshTask {

    private final AiBotOnlineService aiBotOnlineService;

    @Scheduled(fixedRate = 300_000)
    public void refresh() {
        aiBotOnlineService.refreshRedisPresence();
    }
}
