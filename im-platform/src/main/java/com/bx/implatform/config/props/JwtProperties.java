package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Data
@Component
public class JwtProperties {

    @Value("${jwt.accessToken.expireIn}")
    private Integer accessTokenExpireIn;

    @Value("${jwt.accessToken.secret}")
    private String accessTokenSecret;

    @Value("${jwt.refreshToken.expireIn}")
    private Integer refreshTokenExpireIn;

    @Value("${jwt.refreshToken.secret}")
    private String refreshTokenSecret;

    /** 旧令牌没有这个标记。默认 1，上线后已发出的会话全部失效。 */
    @Value("${jwt.sessionEpoch:1}")
    private Integer sessionEpoch;
}
