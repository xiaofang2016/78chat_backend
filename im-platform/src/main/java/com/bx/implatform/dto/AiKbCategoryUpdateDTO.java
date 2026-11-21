package com.bx.implatform.dto;

import com.bx.implatform.vo.AiKbCategoryMetaVO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "更新知识库分类")
public class AiKbCategoryUpdateDTO {

    @NotBlank(message = "label 不能为空")
    private String label;

    @NotNull(message = "fields 不能为空")
    private List<AiKbCategoryMetaVO.AiKbFieldMetaVO> fields;

    private Integer sortOrder;

    private Integer status;
}
