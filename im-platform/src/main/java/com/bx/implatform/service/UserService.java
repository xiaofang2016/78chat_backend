package com.bx.implatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bx.implatform.dto.*;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.RealnameAuthStatus;
import com.bx.implatform.vo.AagamesSsoExchangeVO;
import com.bx.implatform.vo.LoginVO;
import com.bx.implatform.vo.UserVO;

import java.util.List;

public interface UserService extends IService<User> {

    /**
     * 用户登录（已禁用，本地账号登录全部走 aagames_api 外部登录）
     *
     * @param dto 登录dto
     * @return 登录token
     * @deprecated 阶段 1 起本地登录被禁用，请改用 {@link #loginExternal}
     */
    @Deprecated
    LoginVO login(LoginDTO dto);

    /**
     * 外部登录：用 aagames_api 颁发的 access_token 换取 box-im 自有 JWT。
     * 内部会调用 aagames_api 接口⑥校验 token，并把用户 lazy upsert 到 im_user 表。
     *
     * @param dto 外部登录 DTO
     * @return 登录 token
     */
    /**
     * 导入用户本地登录。仅在 aagames 不可用时兜底。正常登录必须走 aagames，保证 im_user.id 等于该商户的 j_users.id。
     */
    LoginVO loginImported(LoginDTO dto);

    LoginVO loginExternal(ExternalLoginDTO dto);

    /**
     * 未登录客服：按商户号和设备发放一套本地登录态。不向 aagames 注册，也不保存游戏 token。
     */
    LoginVO loginGuest(GuestLoginDTO dto);

    /**
     * 说球帝已登录：用说球帝令牌核对账号，找到或新建对应的聊天用户。不沿用吉祥、UED 的旧账号。
     */
    LoginVO loginSport(SportLoginDTO dto);

    /**
     * SSO 登录：使用 exchange 返回的账号信息 + IM 专用 token，不再调 aagames /login 或 validate。
     */
    LoginVO loginExternalSso(AagamesSsoExchangeVO exchange, Integer terminal);


    /**
     * 修改用户密码
     *
     * @param dto 修改密码dto
     */
    void modifyPassword(ModifyPwdDTO dto);

    /**
     * 重置用户密码
     * @param dto 重置密码dto
     */
    void resetPassword(ResetPwdDTO dto);

    /**
     * 用refreshToken换取新 token
     *
     * @param refreshToken 刷新token
     * @return 登录token
     */
    LoginVO refreshToken(String refreshToken);

    /**
     * 用户注册
     *
     * @param dto 注册dto
     */
    void register(RegisterDTO dto);


    /**
     * 用户注销
     *
     */
    void unregister();

    /**
     * 根据登录名查询用户
     *
     * @param loginName 登录名
     * @return 用户信息
     */
    User findUserByLoginName(String loginName);


    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户信息
     */
    User findUserByUserName(String username);


    /**
     * 根据手机号查询用户
     *
     * @param phone 手机号
     * @return 用户信息
     */
    User findUserByPhone(String phone);


    /**
     * 根据邮箱查询用户
     *
     * @param email 邮箱
     * @return 用户信息
     */
    User findUserByEmail(String email);

    /**
     * 是否存在手机用户
     *
     * @param phone 手机号
     * @return
     */
    Boolean isExistPhone(String phone);

    /**
     * 是否存在邮箱用户
     *
     * @param email 邮箱
     * @return 用户信息
     */
    Boolean isExistEmail(String email);

    /**
     * 更新用户信息，好友昵称和群聊昵称等冗余信息也会更新
     *
     * @param vo 用户信息vo
     */
    void update(UserVO vo);

    /**
     * 根据用户昵id查询用户以及在线状态
     *
     * @param id 用户id
     * @return 用户信息
     */
    UserVO findUserById(Long id);

    /**
     * 查询当前用户的信息
     *
     * @return 用户信息
     */
    UserVO findSelfInfo();
    /**
     * 根据用户昵称查询用户，最多返回20条数据
     *
     * @param name 用户名或昵称
     * @return 用户列表
     */
    List<UserVO> findUserByName(String name);

    /**
     * 查询用户，最多返回20条数据
     *
     * @param name 用户名/昵称/手机号/邮箱
     * @return 用户列表
     */
    List<UserVO> search(String name);

    /**
     * 上报用户cid
     * @param cid 用户cid
     */
    void reportCid(String cid);

    /**
     *  清理用户cid
     */
    void removeCid();

    /**
     * 开启/关闭好友验证
     * @param enabled
     */
    void setManualApprove(Boolean enabled);

    /**
     * 开启/关闭新消息提醒
     * @param enabled
     */
    void setAudioTip(Boolean enabled);

    /**
     * 绑定手机号
     * @param dto dto
     */
    void bindPhone(BindPhoneDTO dto);

    /**
     * 绑定邮箱
     * @param dto dto
     */
    void bindEmail(BindEmailDTO dto);

    /**
     * 更新实名认证状态
     *
     * @param status
     */
    void updateAuthStatus(RealnameAuthStatus status);

    /**
     * 设置用户语言
     * @param locale
     */
    void setLocale(String locale);
}
