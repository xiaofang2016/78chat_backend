package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 红包派发主表（接口①落库）
 */
@Data
@TableName("im_redpacket")
public class Redpacket {

    private Long id;

    /**
     * 第三方业务侧唯一键，幂等控制依据
     */
    private String externalId;

    private Long groupId;

    /**
     * 群里那条红包卡片消息 id (im_group_message.id)
     */
    private Long messageId;

    /**
     * 机器人用户 id (j_users.id)
     */
    private Long botUserId;

    private BigDecimal amount;

    private Integer totalCount;

    /**
     * box-im 端流水的已领数；最终一致性以第三方为准
     */
    private Integer claimedCount;

    private String title;

    /**
     * any / valid_show_poster / daily_quota / third_party
     */
    private String eligibilityRule;

    /**
     * 业务透传 JSON
     */
    private String extra;

    /**
     * 0:claiming 1:finished 2:expired 3:rolled_back
     */
    private Integer status;

    private Date expireAt;

    private Date createdAt;

    private Date updatedAt;
}
