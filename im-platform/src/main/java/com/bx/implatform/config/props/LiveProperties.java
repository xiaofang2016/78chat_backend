package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * live-api 对接配置（主播陌生人私聊校验等）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "live")
public class LiveProperties {

    /** live-api base url，例：https://live.7pgb88.com/live-api */
    private String baseUrl = "";

    /** HTTP 请求超时（毫秒） */
    private long requestTimeoutMs = 3000L;

    /** 主播陌生人私聊资格缓存 TTL（秒） */
    private long anchorPmCacheSeconds = 300L;

    /**
     * 官方主播 IM userId，始终允许陌生人私聊（不依赖 live-api 查询）。
     * 例：78live 官方账号 10001。
     */
    private List<Long> officialAnchorUserIds = new ArrayList<>(List.of(10001L));
}
