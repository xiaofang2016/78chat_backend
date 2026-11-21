package com.bx.implatform.vo.redpacket;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 红包详情（用户端展示），合并 box-im im_redpacket 主表 + aagames 接口④ 流水。
 */
@Data
public class RedpacketDetailVO {

    private Long redpacketId;

    private String externalId;

    private Long groupId;

    private String title;

    /** 单个红包金额 */
    private BigDecimal amount;

    /** 总名额 */
    private Integer totalCount;

    /** 已领名额 */
    private Integer claimedCount;

    /** 已领总金额（来自 aagames 流水） */
    private BigDecimal claimedAmount;

    /** claiming / finished / expired / rolled_back */
    private String status;

    private Long expireAt;

    private Long createdAt;

    /** 是否当前用户已经领过 */
    private Boolean myClaimed;

    /** 当前用户领到的金额（myClaimed=true 时） */
    private BigDecimal myAmount;

    /** 领取者列表（按 claimedAt 升序） */
    private List<Item> items;

    @Data
    public static class Item {
        private Long userId;
        private String userName;
        private String userAvatar;
        private BigDecimal amount;
        private Long claimedAt;
        private Boolean isLuckiest;
    }
}
