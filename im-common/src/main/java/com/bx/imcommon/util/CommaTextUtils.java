package com.bx.imcommon.util;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 逗号分格文本处理工具类
 *
 * @author: blue
 * @date: 2023-11-09 09:52:49
 * @version: 1.0
 */
public class CommaTextUtils {

    /**
     * 文本转列表
     *
     * @param strText 文件
     * @return 列表
     */
    public static List<String> asList(String strText) {
        if (StrUtil.isEmpty(strText)) {
            return new LinkedList<>();
        }
        return new LinkedList<>(Arrays.asList(strText.split(",")));

    }

    /**
     * 列表转字符串
     *
     * @param texts 列表
     * @return 文本
     */
    public static <T> String asText(Collection<T> texts) {
        if (CollUtil.isEmpty(texts)) {
            return StrUtil.EMPTY;
        }
        return texts.stream().filter(text -> !Objects.isNull(text)).map(text -> StrUtil.toString(text))
            .filter(StrUtil::isNotEmpty).collect(Collectors.joining(","));
    }


}
