package com.bx.implatform.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 仅做 JSON 语法校验，不走 Fastjson AutoType / class 加载路径。
 */
public final class JsonFormatUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonFormatUtil() {
    }

    /**
     * @throws Exception 内容不是合法 JSON 时抛出
     */
    public static void requireValidJson(String content) throws Exception {
        MAPPER.readTree(content);
    }
}
