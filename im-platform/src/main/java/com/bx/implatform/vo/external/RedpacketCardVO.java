package com.bx.implatform.vo.external;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 群消息中"红包卡片"的内容载荷（GroupMessage.content 序列化为该 VO 的 JSON）。
 *
 * 前端按 type=9 (MessageType.REDPACKET) 渲染卡片：
 *   - 标题：title
 *   - 副标题：单个金额 amount
 *   - 状态徽章：status (claiming / finished / expired)
 */
@Data
public class RedpacketCardVO {

    /** create=首次派发新卡片；status_update=刷新已存在卡片的状态徽章 */
    private String kind = "create";

    private Long redpacketId;

    private String externalId;

    private Long groupId;

    private String title;

    private BigDecimal amount;

    private Integer totalCount;

    /** claiming / finished / expired / rolled_back */
    private String status;

    /** 已被领走的份数（status_update 时刷新） */
    private Integer claimedCount;

    private Long expireAt;

    /** 机器人头像 URL，方便前端兜底渲染 */
    private String botAvatar;

    /** 群成员自己发的红包显示发送人昵称；空则前端仍显示红包机器人 */
    private String senderName;

    /** group=群红包（默认）；private=1对1私聊红包 */
    private String scope = "group";

    /** scope=private 时的领取用户 id */
    private Long targetUserId;
}
