package com.bx.implatform.config.props;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 极验 v4 公开配置。二次校验由 aagames 执行, IM 仅校验四字段并透传。
 * x-device-type 不得参与是否强制极验的安全判断。
 */
@Data
@Component
@ConfigurationProperties(prefix = "geetest")
public class GeetestProperties {

    /** 全局公开配置开关; false 表示客户端注册不可用, 不是服务端免验 */
    private Boolean enabled = false;

    /** 公开 captchaId, 禁止配置或下发私钥 */
    private String captchaId;

    /** 权威二次校验方标识, IM 不得持有 captchaKey */
    private String verifyOwner = "AAGAMES";

    public boolean isGloballyEnabled() {
        return Boolean.TRUE.equals(enabled) && StrUtil.isNotBlank(captchaId);
    }
}
