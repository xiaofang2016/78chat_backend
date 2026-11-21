package com.bx.implatform.controller;

import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.WalletService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.vo.WalletRecordsVO;
import com.bx.implatform.vo.WalletSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "职圈钱包", description = "余额和流水。必须带当前账号的商户号")
@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    @Operation(summary = "主钱包余额", description = "merchantNo 必须等于当前登录用户绑定的商户号")
    public Result<WalletSummaryVO> summary(@RequestParam("merchantNo") String merchantNo) {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(walletService.summary(userId, merchantNo));
    }

    @GetMapping("/records")
    @Operation(summary = "账单 / 红包 / 提现记录", description = "kind=bill|redpacket|withdraw，merchantNo 必须等于当前账号")
    public Result<WalletRecordsVO> records(
        @RequestParam("merchantNo") String merchantNo,
        @RequestParam("kind") String kind,
        @RequestParam(value = "page", required = false) Integer page) {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(walletService.records(userId, merchantNo, kind, page));
    }
}
