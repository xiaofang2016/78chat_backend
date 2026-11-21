package com.bx.implatform.controller;

import com.bx.implatform.client.AagamesAuthClient;
import com.bx.implatform.dto.ExternalLoginDTO;
import com.bx.implatform.dto.GuestLoginDTO;
import com.bx.implatform.dto.LoginDTO;
import com.bx.implatform.dto.ModifyPwdDTO;
import com.bx.implatform.dto.RegisterDTO;
import com.bx.implatform.dto.ResetPwdDTO;
import com.bx.implatform.dto.SportLoginDTO;
import com.bx.implatform.dto.SsoLoginDTO;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.UserService;
import com.bx.implatform.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 注册登录入口。
 *
 * v1.1（用户体系打通）后：
 *   - 注册、改密、重置密、扫码登录下线（返回 HTTP 410）
 *   - 登录有两条等价入口，最终都走 aagames_api 用户体系：
 *       1) {@link #login}          —— 账号 + 密码：box-im 内部代调 aagames /login 拿 access_token
 *       2) {@link #loginExternal}  —— 外部跳转：直接携带 aagames access_token
 *     两者都不会在 box-im 校验本地密码（im_user 不再持有可登录凭据）。
 *   - {@code /refreshToken} 用于 box-im 自有 JWT 续期
 *   - {@code /unregister} 用户主动注销 box-im 端会话，不影响 aagames_api
 */
@Tag(name = "注册登录")
@RestController
@RequiredArgsConstructor
public class LoginController {

    private final UserService userService;
    private final AagamesAuthClient aagamesAuthClient;

    @PostMapping("/login")
    @Operation(summary = "账号密码登录（透传 aagames）",
        description = "前端提交 userName + password；box-im 后端代调 aagames_api /login 拿到 access_token，"
            + "再走外部登录流程换 box-im 自有 JWT。box-im 不存任何密码。")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        // 必须走 aagames，让 im_user.id 等于该商户 j_users.id，并缓存这次登录的 token。
        // 本地导入账号的 id 可能对不上 10011/10012，不能先按本地行签发会话。
        String accessToken;
        try {
            accessToken = aagamesAuthClient.loginOrRegister(dto.getUserName(), dto.getPassword(), dto.getMerchantNo());
        } catch (GlobalException ex) {
            if (ex.getCode() != null && ex.getCode() == 500) {
                LoginVO imported = userService.loginImported(dto);
                if (imported != null) {
                    return ResultUtils.success(imported);
                }
            }
            throw ex;
        }
        ExternalLoginDTO ext = new ExternalLoginDTO();
        ext.setAccessToken(accessToken);
        ext.setTerminal(dto.getTerminal());
        ext.setMerchantNo(dto.getMerchantNo());
        return ResultUtils.success(userService.loginExternal(ext));
    }

    @PostMapping("/login/external")
    @Operation(summary = "外部登录（aagames access_token → box-im JWT）",
        description = "使用 aagames_api 颁发的 access_token 换取 box-im 自有 JWT")
    public Result<LoginVO> loginExternal(@Valid @RequestBody ExternalLoginDTO dto) {
        LoginVO vo = userService.loginExternal(dto);
        return ResultUtils.success(vo);
    }

    @PostMapping("/login/guest")
    @Operation(summary = "未登录客服访客登录",
        description = "按商户号和设备标识发放职圈本地登录态，用于未登录时咨询客服。不创建 aagames 账号。")
    public Result<LoginVO> loginGuest(@Valid @RequestBody GuestLoginDTO dto) {
        return ResultUtils.success(userService.loginGuest(dto));
    }

    @PostMapping("/login/sport")
    @Operation(summary = "说球帝令牌登录",
        description = "用说球帝登录令牌核对手机号账号，找到或新建聊天用户。昵称、头像、手机号以说球帝为准。不使用吉祥或 UED 的旧账号。")
    public Result<LoginVO> loginSport(@Valid @RequestBody SportLoginDTO dto) {
        return ResultUtils.success(userService.loginSport(dto));
    }

    @PostMapping("/login/sso")
    @Operation(summary = "SSO 登录（aagames 已登录 → IM 免密）",
        description = "用 aagames 签发的一次性 ssoCode 兑换账号信息 + IM 专用 token，不触发 aagames /login")
    public Result<LoginVO> loginSso(@Valid @RequestBody SsoLoginDTO dto) {
        var exchange = aagamesAuthClient.exchangeSsoCode(dto.getSsoCode(), dto.getMerchantNo());
        LoginVO vo = userService.loginExternalSso(exchange, dto.getTerminal());
        return ResultUtils.success(vo);
    }

    @PutMapping("/refreshToken")
    @Operation(summary = "刷新token", description = "用 refreshToken 换取新的 token")
    public Result<LoginVO> refreshToken(@RequestHeader("refreshToken") String refreshToken) {
        LoginVO vo = userService.refreshToken(refreshToken);
        return ResultUtils.success(vo);
    }

    @DeleteMapping("/unregister")
    @Operation(summary = "用户注销 box-im 会话", description = "仅注销 box-im 端，不影响 aagames")
    public Result unregister() {
        userService.unregister();
        return ResultUtils.success();
    }

    @PostMapping("/register")
    @Operation(summary = "用户注册（透传 aagames）",
        description = "box-im 后端代调 aagames_api 注册接口；所有终端无条件要求极验四字段, 二次校验由 aagames 执行。")
    public Result register(@Valid @RequestBody RegisterDTO dto) {
        // 1) 代调 aagames 注册（成功则 aagames 侧落库）
        aagamesAuthClient.register(dto);
        // 2) 立刻走一次外部登录链路，触发 lazy upsert 同步 im_user（不改变前端行为）
        String loginName;
        if ("phone".equalsIgnoreCase(dto.getMode()) && dto.getPhone() != null && !dto.getPhone().isEmpty()) {
            loginName = dto.getPhone();
        } else if ("email".equalsIgnoreCase(dto.getMode()) && dto.getEmail() != null && !dto.getEmail().isEmpty()) {
            loginName = dto.getEmail();
        } else {
            loginName = dto.getUserName();
        }
        String accessToken = aagamesAuthClient.login(loginName, dto.getPassword(), dto.getMerchantNo());
        ExternalLoginDTO ext = new ExternalLoginDTO();
        ext.setAccessToken(accessToken);
        ext.setMerchantNo(dto.getMerchantNo());
        // 注册完成后的同步以 WEB 端身份进行一次登录（前端后续仍会按原逻辑再 /login 一次）
        ext.setTerminal(0);
        userService.loginExternal(ext);
        return ResultUtils.success();
    }

    @PutMapping("/modifyPwd")
    @Operation(summary = "[已停用] 修改密码", description = "请在 aagames 端修改")
    public Result modifyPassword(@Valid @RequestBody ModifyPwdDTO dto) {
        throw new GlobalException(410, "修改密码请在 aagames 端完成");
    }

    @PutMapping("/resetPwd")
    @Operation(summary = "[已停用] 重置密码", description = "请在 aagames 端重置")
    public Result resetPassword(@Valid @RequestBody ResetPwdDTO dto) {
        throw new GlobalException(410, "重置密码请在 aagames 端完成");
    }
}
