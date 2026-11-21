package com.bx.imcommon.config;

import com.alibaba.fastjson.parser.ParserConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 Fastjson SafeMode，阻断 @type 触发的远程 class 加载（含 1.2.83 getResource 链）。
 * FastJsonRedisSerializer 默认复用 ParserConfig 全局实例，一并覆盖。
 */
@Slf4j
@Configuration
public class FastjsonSafeModeConfig {

    static {
        ParserConfig.getGlobalInstance().setSafeMode(true);
    }

    @PostConstruct
    public void ensureSafeMode() {
        ParserConfig.getGlobalInstance().setSafeMode(true);
        log.info("Fastjson SafeMode enabled");
    }
}
