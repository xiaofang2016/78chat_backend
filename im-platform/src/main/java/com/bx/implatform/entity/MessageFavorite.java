package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("im_message_favorite")
public class MessageFavorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String kind;

    private String content;

    private String title;

    private String url;

    private String sourceId;

    /** JSON 数组，例如 ["比赛","录像"] */
    private String tags;

    private Date createTime;
}
