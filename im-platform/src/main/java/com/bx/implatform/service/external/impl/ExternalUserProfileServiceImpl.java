package com.bx.implatform.service.external.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bx.implatform.dto.external.UserProfileSyncDTO;
import com.bx.implatform.entity.RealnameAuth;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.RealnameAuthStatus;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.service.RealnameAuthService;
import com.bx.implatform.service.UserService;
import com.bx.implatform.service.external.ExternalUserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalUserProfileServiceImpl implements ExternalUserProfileService {

    /** aagames 仅同步姓名时无身份证号，满足 im_realname_auth.id_card_number NOT NULL */
    private static final String AAGAMES_SYNC_ID_CARD_PLACEHOLDER = "AAGAMES";

    private final UserService userService;
    private final RealnameAuthService realnameAuthService;

    @Override
    @Transactional
    public void sync(UserProfileSyncDTO dto) {
        User user = userService.getById(dto.getUserId());
        if (user == null) {
            throw new GlobalException("用户不存在");
        }

        boolean syncedPhone = syncPhone(user, dto);
        boolean syncedRealname = syncRealname(user, dto);

        if (!syncedPhone && !syncedRealname) {
            return;
        }

        log.info("aagames profile synced userId={} phone={} realname={}",
            dto.getUserId(), syncedPhone, syncedRealname);
    }

    private boolean syncPhone(User user, UserProfileSyncDTO dto) {
        if (StrUtil.isBlank(dto.getMobile()) || StrUtil.isNotBlank(user.getPhone())) {
            return false;
        }

        String phone = dto.getMobile().trim();
        if (StrUtil.isNotBlank(dto.getPhoneAreaCode()) && !"86".equals(dto.getPhoneAreaCode().trim())) {
            phone = dto.getPhoneAreaCode().trim() + phone;
        }

        LambdaUpdateWrapper<User> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(User::getId, user.getId());
        wrapper.set(User::getPhone, phone);
        userService.update(wrapper);
        return true;
    }

    private boolean syncRealname(User user, UserProfileSyncDTO dto) {
        if (StrUtil.isBlank(dto.getRealName())) {
            return false;
        }

        String realName = dto.getRealName().trim();
        LambdaQueryWrapper<RealnameAuth> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(RealnameAuth::getUserId, user.getId());
        RealnameAuth auth = realnameAuthService.getOne(wrapper);

        if (auth != null && RealnameAuthStatus.APPROVED.getCode().equals(auth.getAuthStatus())) {
            return false;
        }

        if (auth == null) {
            auth = new RealnameAuth();
            auth.setUserId(user.getId());
            auth.setCreateTime(new Date());
            auth.setIdCardNumber(AAGAMES_SYNC_ID_CARD_PLACEHOLDER);
        } else if (StrUtil.isBlank(auth.getIdCardNumber())) {
            auth.setIdCardNumber(AAGAMES_SYNC_ID_CARD_PLACEHOLDER);
        }

        auth.setRealName(realName);
        auth.setAuthStatus(RealnameAuthStatus.APPROVED.getCode());
        auth.setAuthTime(new Date());
        realnameAuthService.saveOrUpdate(auth);

        LambdaUpdateWrapper<User> userWrapper = Wrappers.lambdaUpdate();
        userWrapper.eq(User::getId, user.getId());
        userWrapper.set(User::getNickName, realName);
        userWrapper.set(User::getAuthStatus, RealnameAuthStatus.APPROVED.getCode());
        userService.update(userWrapper);
        return true;
    }
}
