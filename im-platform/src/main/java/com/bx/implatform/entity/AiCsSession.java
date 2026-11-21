package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("im_ai_cs_session")
public class AiCsSession {

    public static final int MODE_AI = 0;
    public static final int MODE_HUMAN = 1;

    private Long id;
    private Long botUserId;
    private Long customerUserId;
    private Integer mode;
    private Long operatorId;
    private String handoffReason;
    private Long lastUserMsgId;
    private Date lastMsgAt;
    private Date createdTime;
    private Date updatedTime;
}
