package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.Map;

@Data
@Schema(description = "AI知识库条目（对外只读）")
public class AiKbEntryPublicVO {

    private Long id;

    @Schema(description = "分类代码")
    private String category;

    @Schema(description = "分类中文名")
    private String categoryLabel;

    private String title;

    @Schema(description = "分类字段键值")
    private Map<String, String> fields;

    private Integer sortOrder;

    private Date updatedTime;
}
