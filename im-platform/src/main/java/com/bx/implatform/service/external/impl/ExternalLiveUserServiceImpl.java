package com.bx.implatform.service.external.impl;

import com.bx.implatform.entity.User;
import com.bx.implatform.service.UserService;
import com.bx.implatform.service.external.ExternalLiveUserService;
import com.bx.implatform.vo.external.LiveUserDisplayVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExternalLiveUserServiceImpl implements ExternalLiveUserService {

    private final UserService userService;

    @Override
    public LiveUserDisplayVO display(Long userId) {
        LiveUserDisplayVO vo = new LiveUserDisplayVO();
        vo.setUserId(userId);
        User user = userService.getById(userId);
        if (user == null) {
            return vo;
        }
        vo.setNickName(user.getNickName());
        vo.setUserName(user.getUserName());
        return vo;
    }
}
