package com.bx.implatform.client;

import com.bx.implatform.config.props.AagamesProperties;
import com.bx.implatform.entity.User;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 职圈调 aagames 时的商户号校验。不允许缺省，也不允许名单外的商户。
 */
@Component
@RequiredArgsConstructor
public class AagamesMerchantSupport {

    private final AagamesProperties aagamesProps;
    private final UserMapper userMapper;

    public String requireAllowed(String merchantNo) {
        String normalized = merchantNo == null ? "" : merchantNo.trim();
        if (normalized.isEmpty() || !aagamesProps.getMerchantNos().contains(normalized)) {
            throw new GlobalException(400, "商户号无效，请选择已开通的商户");
        }
        return normalized;
    }

    public String requireForUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new GlobalException(400, "缺少商户号");
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getMerchantNo() == null || user.getMerchantNo().isBlank()) {
            throw new GlobalException(400, "用户未绑定商户号");
        }
        return requireAllowed(user.getMerchantNo());
    }

    /**
     * 前端必须带商户号，并且只能是当前登录用户已绑定的那个。
     */
    public String requireMatchesUser(Long userId, String requestMerchantNo) {
        String requested = requestMerchantNo == null ? "" : requestMerchantNo.trim();
        if (requested.isEmpty()) {
            throw new GlobalException(400, "商户号不可为空");
        }
        String bound = requireForUser(userId);
        if (!bound.equals(requested)) {
            throw new GlobalException(400, "商户号与当前账号不一致");
        }
        return bound;
    }
}
