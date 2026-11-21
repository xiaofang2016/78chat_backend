package com.bx.implatform.vo.external;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 接口⑤ /external/user/{userId}/redpacket/quota 的响应 data。
 *
 * v1.1 阶段2：aagames 桩固定返回 dailyLimit=888, claimedAmount=0；
 * 后续接入业务后由"当天中奖总额"重新计算。
 */
@Data
public class UserDailyQuotaVO {

    private Long userId;

    /** yyyy-MM-dd */
    private String date;

    /** 当日总额度（元） */
    private BigDecimal dailyLimit;

    /** 已领金额（元） */
    private BigDecimal claimedAmount;

    /** 剩余可领（元） */
    private BigDecimal remainAmount;

    /** 已领次数（红包个数） */
    private Integer claimedCount;

    /** 是否还可继续领取 */
    private Boolean canClaim;

    /** 业务侧给出的限制来源说明（例如 invite-friends-150137 等） */
    private String limitFromMerchant;

    /** aagames quota 接口原始 data（含 invite_friends 等扩展字段） */
    private JSONObject rawPayload;
}
