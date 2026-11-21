package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("im_ai_kb_category")
public class AiKbCategoryDef {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String label;

    /** 字段定义 JSON 数组 */
    private String fieldsJson;

    private Integer sortOrder;

    /** 1 启用 0 停用 */
    private Integer status;

    private Date createdTime;

    private Date updatedTime;

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;
}
