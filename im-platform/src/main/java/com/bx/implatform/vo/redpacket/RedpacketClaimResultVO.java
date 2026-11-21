package com.bx.implatform.vo.redpacket;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户端领取红包响应（box-im → 前端）。
 */
@Data
public class RedpacketClaimResultVO {

    private Long redpacketId;

    private String externalId;

    /** success / already_claimed / sold_out / expired / not_eligible / failed */
    private String result;

    /** 实际领取金额（result=success / already_claimed 时有值） */
    private BigDecimal amount;

    /** 剩余名额；available_count = totalCount - claimedCount */
    private Integer remainCount;

    /** 第三方记录的领取时间（epoch millis） */
    private Long claimedAt;

    /** 失败原因（result != success 时给前端文案兜底） */
    private String failReason;
}
