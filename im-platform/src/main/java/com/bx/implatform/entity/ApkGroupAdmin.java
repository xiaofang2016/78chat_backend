package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 原站聊天群的管理员。账号编号沿用原站用户编号。
 */
@Data
@TableName("im_apk_group_admin")
public class ApkGroupAdmin {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;

    private Long userId;

    @TableField("is_owner")
    private Boolean owner;
}
