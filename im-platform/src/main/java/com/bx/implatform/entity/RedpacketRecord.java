package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 红包领取流水（接口③）
 */
@Data
@TableName("im_redpacket_record")
public class RedpacketRecord {

    private Long id;

    private Long redpacketId;

    private String externalId;

    /**
     * box-im 本次领取唯一 id, 给第三方做幂等
     */
    private String claimId;

    private Long userId;

    private String userName;

    private String userAvatar;

    private BigDecimal amount;

    /**
     * 0:pending 1:success 2:failed 3:rolled_back
     */
    private Integer status;

    private String failReason;

    private Date claimedAt;

    private Date createdAt;
}
