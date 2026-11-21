package com.bx.implatform.config;

import com.bx.implatform.interceptor.AuthInterceptor;
import com.bx.implatform.interceptor.XssInterceptor;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@AllArgsConstructor
public class MvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final XssInterceptor xssInterceptor;

    /**
     * 让所有 HTTP 响应都显式声明 charset=UTF-8，避免部分客户端 / Network 面板按 ISO-8859-1 解码导致中文乱码。
     * Spring 5.3+ 后 Jackson converter 默认 supportedMediaTypes 不带 charset；这里手动加回。
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> c : converters) {
            if (c instanceof MappingJackson2HttpMessageConverter jackson) {
                jackson.setDefaultCharset(StandardCharsets.UTF_8);
                jackson.setSupportedMediaTypes(List.of(
                    new MediaType("application", "json", StandardCharsets.UTF_8),
                    new MediaType("application", "*+json", StandardCharsets.UTF_8)
                ));
            } else if (c instanceof StringHttpMessageConverter sc) {
                sc.setDefaultCharset(StandardCharsets.UTF_8);
                sc.setSupportedMediaTypes(List.of(
                    new MediaType("text", "plain", StandardCharsets.UTF_8),
                    MediaType.ALL
                ));
            }
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(xssInterceptor).addPathPatterns("/**").excludePathPatterns(
            "/error",
            "/admin/**",
            // live-api 推送推广富文本（含 href/style），HMAC 鉴权，非用户输入
            "/external/live/group/*/promo"
        );
        registry.addInterceptor(authInterceptor).addPathPatterns("/**")
            .excludePathPatterns(
                // v1.1：登录入口
                "/login/external",
                "/login/guest",
                "/login/sso",
                "/login/sport",
                "/refreshToken",
                // 已停用接口仍保留 mapping 返回 410，需要免登
                "/login", "/logout", "/register", "/resetPwd", "/captcha/**",
                // v1.1 阶段2：aagames → box-im 入站，由 BoximInboundHmacFilter 完成 HMAC 鉴权
                "/external/**",
                // 系统级
                "/system/**", "/*/upload",
                // 公开聊天资料页（未登录可访问）
                "/talk/**",
                // Swagger
                "/swagger/**", "/v3/api-docs/**",
                "/swagger-resources/**", "/swagger-ui.html", "/swagger-ui/**", "/doc.html");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 使用BCrypt加密密码
        return new BCryptPasswordEncoder();
    }

}
