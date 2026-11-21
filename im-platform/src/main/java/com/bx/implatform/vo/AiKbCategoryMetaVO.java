package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "AI知识库分类元数据")
public class AiKbCategoryMetaVO {

    private Long id;

    private String code;

    private String label;

    @Schema(description = "1启用 0停用")
    private Integer status;

    private Integer sortOrder;

    private List<AiKbFieldMetaVO> fields;

    @Data
    public static class AiKbFieldMetaVO {
        private String key;
        private String label;
        private boolean required;
        /** text | textarea | url | select */
        private String inputType;
        private List<String> options;
    }
}
