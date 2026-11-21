package com.bx.implatform.service;

import com.bx.implatform.vo.WalletRecordsVO;
import com.bx.implatform.vo.WalletSummaryVO;

public interface WalletService {

    WalletSummaryVO summary(Long userId, String merchantNo);

    WalletRecordsVO records(Long userId, String merchantNo, String kind, Integer page);
}
