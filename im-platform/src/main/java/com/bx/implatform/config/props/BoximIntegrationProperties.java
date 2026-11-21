package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * box-im 自身的对接侧配置（被 aagames 调用时用到的入站参数 + 红包机器人）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "boxim")
public class BoximIntegrationProperties {

    /** 入站 secret：aagames 调 box-im（接口① 红包推送）时签名 / box-im 验签 */
    private String inboundSecret;

    /** 入站请求时间戳容差，单位秒（超出窗口拒绝） */
    private long timestampToleranceSeconds = 300L;

    /** 入站 nonce 防重放窗口，单位秒 */
    private long nonceTtlSeconds = 300L;

    private RedpacketBot redpacketBot = new RedpacketBot();

    private Activities activities = new Activities();

    @Data
    public static class Activities {
        /** 天天好运金 j_activity.id；0 表示未配置 */
        private long dailyGoodLuckGoldId = 0L;

        /** 邀请好友送彩礼 j_activity.id；0 表示未配置 */
        private long inviteFriendsBonusId = 0L;

        /** 佣金加倍 999 奖上奖 j_activity.id；0 表示未配置 */
        private long commissionBonus999Id = 0L;

        /** 好友存款领赏金 4215 j_activity.id；0 表示未配置 */
        private long friendDepositBonus4215Id = 0L;

        /** 幸运排列五送彩票 j_activity.id；0 表示未配置 */
        private long lotteryGift132467Id = 0L;

        /** 体育周周领 j_activity.id；0 表示未配置 */
        private long weeklySportsBonusId = 0L;

        /** 日投注闯关 j_activity.id；0 表示未配置 */
        private long dailySportsBonusId = 0L;

        /** 体育连红挑战 j_activity.id；0 表示未配置 */
        private long sportWinStreakId = 0L;
    }

    @Data
    public static class RedpacketBot {
        /** 红包机器人在 j_users 中的 ID，等于 box-im im_user.id */
        private Long userId;

        /** 机器人在群里的昵称 */
        private String nickName = "红包机器人";

        /** 机器人头像 URL */
        private String headImage = "";

        /** 接口① 不指定 groupId 时使用的默认晒图群 id；0 表示未配置 */
        private Long defaultGroupId = 0L;
    }
}
