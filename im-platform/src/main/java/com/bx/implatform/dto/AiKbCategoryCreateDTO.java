package com.bx.implatform.dto;

import com.bx.implatform.vo.AiKbCategoryMetaVO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "新增知识库分类")
public class AiKbCategoryCreateDTO {

    @NotBlank(message = "code 不能为空")
    @Schema(description = "分类代码，大写字母数字下划线，创建后不可改")
    private String code;

    @NotBlank(message = "label 不能为空")
    private String label;

    @NotNull(message = "fields 不能为空")
    private List<AiKbCategoryMetaVO.AiKbFieldMetaVO> fields;

    private Integer sortOrder;

    @Schema(description = "1启用 0停用，默认启用")
    private Integer status;
}
