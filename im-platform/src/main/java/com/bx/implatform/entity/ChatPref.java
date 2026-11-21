package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("im_chat_pref")
public class ChatPref {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String kind;

    private Long peerId;

    private String remark;

    private String phone;

    private String description;

    private Integer starred;

    private Integer isTop;

    private Integer isDnd;

    private Integer audioOn;

    private Date createTime;

    private Date updateTime;
}
