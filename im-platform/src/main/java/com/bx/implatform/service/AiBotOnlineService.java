package com.bx.implatform.service;

import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.util.KnowledgeIngestBotVisibility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 客服机器人（小红 / 小绿等）保持「始终在线」：写入 im:user:server_id Redis 键，
 * 并在业务层查询在线状态时强制为 true。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBotOnlineService {

    /** 虚拟 serverId，仅表示在线占位，不对应真实 im-server 连接 */
    private static final String VIRTUAL_SERVER_ID = "0";

    private final AiCustomerServiceProperties aiCsProps;
    private final StringRedisTemplate stringRedisTemplate;

    /** 配置中的 AI 客服机器人（不含「AI知识库」录入机器人） */
    public List<Long> alwaysOnlineBotIds() {
        Set<Long> ids = new LinkedHashSet<>();
        Long primary = aiCsProps.getBotUserId();
        if (primary != null && primary > 0) {
            ids.add(primary);
        }
        List<Long> configured = aiCsProps.getBotUserIds();
        if (configured != null) {
            for (Long id : configured) {
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
        }
        Long kbBotId = KnowledgeIngestBotVisibility.botUserId(aiCsProps);
        if (kbBotId != null) {
            ids.remove(kbBotId);
        }
        return new ArrayList<>(ids);
    }

    public boolean isAlwaysOnlineBot(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }
        return alwaysOnlineBotIds().contains(userId);
    }

    /** 将机器人各终端标记为在线（无过期） */
    public void refreshRedisPresence() {
        if (!aiCsProps.isEnabled()) {
            return;
        }
        List<Long> botIds = alwaysOnlineBotIds();
        if (botIds.isEmpty()) {
            return;
        }
        for (Long botId : botIds) {
            for (Integer terminal : IMTerminalType.codes()) {
                String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, botId.toString(), terminal.toString());
                stringRedisTemplate.opsForValue().set(key, VIRTUAL_SERVER_ID);
            }
        }
        log.debug("[ai-bot-online] refreshed presence for bots {}", botIds);
    }
}
