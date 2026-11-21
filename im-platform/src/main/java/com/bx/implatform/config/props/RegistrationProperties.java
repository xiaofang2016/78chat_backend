package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 注册相关配置
 * @author Blue
 * @version 1.0
 * @date 2025-03-21
 */
@Data
@Component
@ConfigurationProperties(prefix = "registration")
public class RegistrationProperties {

    /**
     * 是否向客户端展示/开放注册能力 (如 MP 未接入极验前设为 false)。
     * 不影响 /register 服务端对极验四字段的强制校验。
     */
    private Boolean enabled = true;

    /**
     *  注册方式(多种)
     */
    private List<String> mode;

    /**
     * IP注册限制配置
     */
    private IpLimit ipLimit;

    @Data
    public static class IpLimit {
        /**
         * 是否启用IP注册限制
         */
        private Boolean enabled = false;

        /**
         * 每个IP允许注册的最大用户数量（永久限制）
         */
        private Integer maxCount = 5;
    }

}
