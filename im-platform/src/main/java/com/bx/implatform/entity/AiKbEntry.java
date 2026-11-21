package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("im_ai_kb_entry")
public class AiKbEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** {@link com.bx.implatform.enums.AiKbCategory} */
    private String category;

    /** 1 启用 0 停用 */
    private Integer status;

    private String title;

    /** JSON 对象字符串 */
    private String fieldsJson;

    private String chunkSource;

    private Integer sortOrder;

    private Long createdBy;

    private Long updatedBy;

    private Date createdTime;

    private Date updatedTime;

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;
}
