package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("im_ai_cs_human_message")
public class AiCsHumanMessage {

    private Long messageId;
    private Long botUserId;
    private Long customerUserId;
    private Long operatorId;
    private Date createdTime;
}
