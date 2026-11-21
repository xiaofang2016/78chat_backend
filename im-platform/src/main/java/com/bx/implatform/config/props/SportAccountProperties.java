package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sport.account")
public class SportAccountProperties {

    /** 说球帝恢复登录地址。职圈拿令牌来这里核对账号。 */
    private String sessionUrl = "https://dgtplaoir00la.cloudfront.net/prod-api/user/session";

    /** 说球帝公开地址，用来补全头像路径。 */
    private String publicBase = "https://dgtplaoir00la.cloudfront.net";

    /** 用已绑定的吉祥或 UED 令牌换登录。 */
    private String boundUrl = "https://dgtplaoir00la.cloudfront.net/prod-api/user/bound-login";

    /** 群红包扣说球帝余额、领到的钱入账、没领完退回。 */
    private String groupRedpackUrl = "https://dgtplaoir00la.cloudfront.net/prod-api/user/group-redpack";

    /** 和说球帝读取的 BOXIM_OUTBOUND_SECRET 是同一个值。 */
    private String boundSecret = "";

    private int timeoutMs = 5000;
}
