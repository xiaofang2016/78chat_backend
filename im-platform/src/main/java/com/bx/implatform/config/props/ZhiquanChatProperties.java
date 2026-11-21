package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "zhiquan.chat")
public class ZhiquanChatProperties {

    /** 关掉后群聊仍只读 MySQL。 */
    private boolean enabled = true;

    private String region = "ap-northeast-1";

    private String table = "zhiquan_chat";

    /**
     * 网页要读的分区。当前抓取把最新群和消息写在 apk#{uid}。
     */
    private String partition = "apk#5739998";

    /**
     * 旧抓取账号。只有带着抓取程序标识的请求才继续读 MySQL。
     */
    private Long sourceUserId = 8000000001L;

    /** 表每分钟更新，缓存短于这个间隔。 */
    private int cacheSeconds = 20;

    /** 每次每个群只带最近这么多条。更早的不在登录时下发。 */
    private int offlinePerGroup = 100;

    /** 说球帝聊天推送函数。空字符串则不推。 */
    private String liveFunction = "sport-crawl-chat-live";
}
