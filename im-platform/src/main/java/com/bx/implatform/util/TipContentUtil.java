package com.bx.implatform.util;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * TIP_TEXT 消息 content 的 key+params 结构化存储与解析 存储格式 JSON: {"key":"tip.group.xxx","params":["#{name:userId}",...]} 历史消息
 * content 可能为纯字符串，解析时需兼容
 *
 * @author Blue
 */
@Slf4j
public final class TipContentUtil {

    private static final String KEY_KEY = "key";
    private static final String KEY_PARAMS = "params";

    /**
     * 构建 TIP_TEXT 的 content（存库用）
     *
     * @param key    i18n key，如 tip.group.quit
     * @param params 占位符参数，顺序与 properties 中 {0},{1} 一致
     * @return JSON 字符串
     */
    public static String buildTipContent(String key, Object... params) {
        JSONObject obj = new JSONObject();
        obj.put(KEY_KEY, key);
        if (!Objects.isNull(params) && params.length > 0) {
            obj.put(KEY_PARAMS, params);
        }
        return obj.toJSONString();
    }
}
