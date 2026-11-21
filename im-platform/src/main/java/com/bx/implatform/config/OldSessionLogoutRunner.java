package com.bx.implatform.config;

import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.model.IMForceLogoutInfo;
import com.bx.imcommon.mq.RedisMQTemplate;
import com.bx.implatform.config.props.JwtProperties;
import com.bx.implatform.contant.OfficialCsNames;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.UserType;
import com.bx.implatform.service.AiBotOnlineService;
import com.bx.implatform.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 每个会话标记只做一次：把当时还连着、且不是说球帝账号和客服机器人的旧连接踢下线。
 * 没有标记的令牌在接口和续期处已经失效，这里只处理还挂着的 WebSocket。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OldSessionLogoutRunner implements ApplicationRunner {

    private static final String APPLIED_KEY = "im:session:applied_epoch";
    private static final String LOCK_KEY = "im:session:logout_lock";

    private final RedisMQTemplate redisMQTemplate;
    private final JwtProperties jwtProperties;
    private final UserService userService;
    private final AiBotOnlineService aiBotOnlineService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            logoutOnce();
        } catch (Exception ex) {
            log.error("旧会话下线失败", ex);
        }
    }

    private void logoutOnce() {
        Integer epoch = jwtProperties.getSessionEpoch();
        if (epoch == null || alreadyApplied(epoch)) {
            return;
        }
        Boolean locked = redisMQTemplate.opsForValue().setIfAbsent(LOCK_KEY, epoch, 10, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            if (alreadyApplied(epoch)) {
                return;
            }
            int count = kickOnlineUsers();
            redisMQTemplate.opsForValue().set(APPLIED_KEY, epoch);
            log.info("旧会话已下线 count={} epoch={}", count, epoch);
        } finally {
            redisMQTemplate.delete(LOCK_KEY);
        }
    }

    private boolean alreadyApplied(Integer epoch) {
        Object applied = redisMQTemplate.opsForValue().get(APPLIED_KEY);
        return applied != null && String.valueOf(epoch).equals(String.valueOf(applied));
    }

    private int kickOnlineUsers() {
        Set<String> keys = redisMQTemplate.keys(IMRedisKey.IM_USER_SERVER_ID + ":*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        String prefix = IMRedisKey.IM_USER_SERVER_ID + ":";
        int count = 0;
        for (String key : keys) {
            if (key == null || !key.startsWith(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int colon = rest.indexOf(':');
            if (colon <= 0 || colon >= rest.length() - 1) {
                continue;
            }
            Long userId;
            Integer terminal;
            try {
                userId = Long.valueOf(rest.substring(0, colon));
                terminal = Integer.valueOf(rest.substring(colon + 1));
            } catch (NumberFormatException ex) {
                continue;
            }
            Object server = redisMQTemplate.opsForValue().get(key);
            if (server == null || "0".equals(String.valueOf(server))) {
                continue;
            }
            if (OfficialCsNames.isOfficial(userId) || aiBotOnlineService.isAlwaysOnlineBot(userId)) {
                continue;
            }
            User user = userService.getById(userId);
            if (user != null && UserType.SPORT.getValue().equals(user.getType())) {
                continue;
            }
            IMForceLogoutInfo info = new IMForceLogoutInfo();
            info.setUserId(userId);
            info.setTerminal(terminal);
            String queueKey = IMRedisKey.IM_USER_FORCE_LOGOUT_QUEUE + ":" + server;
            redisMQTemplate.opsForList().rightPush(queueKey, info);
            count++;
        }
        return count;
    }
}
