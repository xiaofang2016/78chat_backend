package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("im_feedback")
public class Feedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String content;

    private String contact;

    private String images;

    private Date createTime;
}
