package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "AI知识库分类（对外只读，仅启用）")
public class AiKbCategoryPublicVO {

    private Long id;

    private String code;

    private String label;

    private Integer sortOrder;

    private List<AiKbCategoryMetaVO.AiKbFieldMetaVO> fields;
}
