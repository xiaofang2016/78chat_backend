package com.bx.implatform.vo.external;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 接口③ /external/redpacket/claim 的响应 data。
 */
@Data
public class RedpacketClaimResultVO {

    /** 实际领取金额 */
    private BigDecimal amount;

    /** 该红包剩余名额 */
    private Integer remainCount;

    /** 第三方记录的实际领取时间 (epoch millis) */
    private Long claimedAt;

    /** 用户当前余额（aagames 端可选返回） */
    private BigDecimal balance;
}
