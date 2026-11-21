package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("im_favorite_tag")
public class FavoriteTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private Date createTime;
}
