package com.bx.implatform.util;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.Objects;

/**
 * 国际化消息工具类 在 Web 请求上下文中自动使用当前请求的 Locale，否则使用默认 Locale
 *
 * @author Blue
 */
@Slf4j
public final class MessageUtils {

    private static MessageSource messageSource;

    public static void setMessageSource(MessageSource messageSource) {
        MessageUtils.messageSource = messageSource;
    }

    /**
     * 根据消息 key 获取国际化消息
     *
     * @param code 消息 key
     * @return 国际化后的消息
     */
    public static String getMessage(String code) {
        return getMessage(code, null);
    }

    /**
     * 根据消息 key 和参数获取国际化消息
     *
     * @param code 消息 key
     * @param args 占位符参数
     * @return 国际化后的消息
     */
    public static String getMessage(String code, Object[] args) {
        try {
            Locale locale = LocaleContextHolder.getLocale();
            if (Objects.isNull(locale)) {
                locale = Locale.SIMPLIFIED_CHINESE;
            }
            return messageSource.getMessage(code, args, code, locale);
        } catch (Exception e) {
            log.error("国际化语言转换异常,code:{}", code, e);
            return code;
        }
    }

    /**
     * 根据消息 key、参数和默认值获取国际化消息
     *
     * @param locale 语言
     * @param code   消息 key
     * @param args   占位符参数
     * @return 国际化后的消息
     */
    public static String getMessage(String locale, String code, Object... args) {
        try {
            return messageSource.getMessage(code, args, code, parseLocale(locale));
        } catch (Exception e) {
            log.error("国际化语言转换异常,code:{}", code, e);
            return code;
        }
    }

    public static Locale parseLocale(String locale) {
        if (StrUtil.isNotEmpty(locale) && locale.startsWith("en")) {
            return Locale.ENGLISH;
        }
        // 默认简体中文
        return Locale.SIMPLIFIED_CHINESE;
    }
}
