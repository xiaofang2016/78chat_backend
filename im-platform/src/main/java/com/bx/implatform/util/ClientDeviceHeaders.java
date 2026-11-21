package com.bx.implatform.util;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * 读取外部客户端注入的请求头，判断是否为打包 App 登录。
 *
 * <p>与 aagames {@code Device.HEADER_KEY} / {@code x-version-code} 约定一致（见
 * {@code UserAppInstallLog::initAppInstall}：{@code Device::isApp() && version_code 非空}）。
 *
 * <p>打包 App：{@code x-device-type=IOS|ANDROID} 且 {@code x-version-code} 非空
 * <p>网页 / H5：{@code x-device-type} 为 PC / H5 / MACOS / WINDOWS 等，或无版本号
 */
public final class ClientDeviceHeaders {

    public static final String HEADER_DEVICE_TYPE = "x-device-type";
    public static final String HEADER_VERSION_CODE = "x-version-code";

    private static final String DEVICE_ANDROID = "ANDROID";
    private static final String DEVICE_IOS = "IOS";

    private ClientDeviceHeaders() {
    }

    public record PackagedAppLogin(String deviceType, String versionCode) {
    }

    public static String deviceType(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        return StrUtil.blankToDefault(request.getHeader(HEADER_DEVICE_TYPE), "").trim();
    }

    public static String versionCode(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        return StrUtil.blankToDefault(request.getHeader(HEADER_VERSION_CODE), "").trim();
    }

    /**
     * 仅从 HTTP 请求头解析打包 App 登录信息；网页/H5 请求返回 empty。
     */
    public static Optional<PackagedAppLogin> parsePackagedAppLogin(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String device = deviceType(request).toUpperCase();
        if (!DEVICE_ANDROID.equals(device) && !DEVICE_IOS.equals(device)) {
            return Optional.empty();
        }
        String version = versionCode(request);
        if (StrUtil.isBlank(version)) {
            return Optional.empty();
        }
        return Optional.of(new PackagedAppLogin(device, version.trim()));
    }

    public static boolean isPackagedMobileApp(HttpServletRequest request) {
        return parsePackagedAppLogin(request).isPresent();
    }
}
