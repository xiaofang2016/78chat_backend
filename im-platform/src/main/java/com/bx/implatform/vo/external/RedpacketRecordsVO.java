package com.bx.implatform.vo.external;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接口④ /external/redpacket/{externalId}/records 的响应 data。
 */
@Data
public class RedpacketRecordsVO {

    private String redpacketExternalId;

    /** 已领总金额 */
    private BigDecimal totalAmount;

    /** 已领人数 */
    private Integer claimedCount;

    private List<Record> records;

    @Data
    public static class Record {
        private Long userId;
        private String userName;
        private String userAvatar;
        private BigDecimal amount;
        private Long claimedAt;
        private Boolean isLuckiest;
    }
}
