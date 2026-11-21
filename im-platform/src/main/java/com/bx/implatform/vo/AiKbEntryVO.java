package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.Map;

@Data
@Schema(description = "AI知识库条目")
public class AiKbEntryVO {

    private Long id;

    @Schema(description = "分类代码 ACTIVITY|RECHARGE|WITHDRAW|APP")
    private String category;

    @Schema(description = "分类中文名")
    private String categoryLabel;

    @Schema(description = "1启用 0停用")
    private Integer status;

    private String title;

    @Schema(description = "分类字段键值")
    private Map<String, String> fields;

    private String chunkSource;

    private Integer sortOrder;

    private Long createdBy;

    private Long updatedBy;

    private Date createdTime;

    private Date updatedTime;
}
