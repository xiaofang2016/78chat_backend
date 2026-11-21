package com.bx.implatform.config;

import com.bx.implatform.service.AiBotOnlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时将小红 / 小绿等 AI 机器人标记为在线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiBotOnlineInitializer implements ApplicationRunner {

    private final AiBotOnlineService aiBotOnlineService;

    @Override
    public void run(ApplicationArguments args) {
        aiBotOnlineService.refreshRedisPresence();
        log.info("[ai-bot-online] always-online bots: {}", aiBotOnlineService.alwaysOnlineBotIds());
    }
}
