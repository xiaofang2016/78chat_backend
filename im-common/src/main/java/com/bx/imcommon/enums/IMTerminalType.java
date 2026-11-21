package com.bx.imcommon.enums;

import lombok.AllArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@AllArgsConstructor
public enum IMTerminalType {

    /**
     * web
     */
    WEB(0, "web"),
    /**
     * app
     */
    APP(1, "app"),

    /**
     * 内嵌客服。小程序里打开客服用这个终端，避免和外层 App / Web 抢同一个在线位。
     */
    CS(3, "cs"),

    /**
     * 未知
     */
    UNKNOW(-1, "未知");

    private final Integer code;

    private final String desc;

    public static IMTerminalType fromCode(Integer code) {
        for (IMTerminalType typeEnum : values()) {
            if (typeEnum.code.equals(code)) {
                return typeEnum;
            }
        }
        return null;
    }

    public static List<Integer> codes() {
        return Arrays.stream(values()).map(IMTerminalType::code).filter(code -> !code.equals(UNKNOW.code))
            .collect(Collectors.toList());
    }

    public Integer code() {
        return this.code;
    }

}
