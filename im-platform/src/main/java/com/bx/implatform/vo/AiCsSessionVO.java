package com.bx.implatform.vo;

import lombok.Data;

import java.util.Date;

@Data
public class AiCsSessionVO {

    private Long botUserId;
    private Long customerUserId;
    private String customerNickName;
    private String customerUserName;
    private Integer mode;
    private Long operatorId;
    private String operatorNickName;
    private String handoffReason;
    private Long lastUserMsgId;
    private String lastMessagePreview;
    /** 最后一条消息类型，与 MessageType.code 一致 */
    private Integer lastMessageType;
    private Date lastMsgAt;
    /** pending=待接入（人工模式无 operator） */
    private boolean pendingHuman;
}
