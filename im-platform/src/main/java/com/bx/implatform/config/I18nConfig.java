package com.bx.implatform.config;

import cn.hutool.core.util.StrUtil;
import com.bx.implatform.util.MessageUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

/**
 * 国际化配置 支持通过 Accept-Language 请求头或 X-Locale 请求头指定语言 可选值: zh_CN(中文), en(英文)
 *
 * @author Blue
 */
@Configuration
public class I18nConfig {

    public I18nConfig(MessageSource messageSource) {
        MessageUtils.setMessageSource(messageSource);
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                // 优先从 X-Locale 请求头获取（便于客户端显式指定）
                String localeHeader = request.getHeader("X-Locale");
                if (StrUtil.isNotEmpty(localeHeader)) {
                    return MessageUtils.parseLocale(localeHeader);
                }
                return super.resolveLocale(request);
            }
        };
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        return resolver;
    }

}
