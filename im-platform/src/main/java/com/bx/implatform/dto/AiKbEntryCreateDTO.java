package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "新增 AI 知识库条目")
public class AiKbEntryCreateDTO {

    @NotBlank(message = "category 不能为空")
    private String category;

    @NotBlank(message = "title 不能为空")
    private String title;

    @NotNull(message = "fields 不能为空")
    private Map<String, String> fields;

    @Schema(description = "1启用 0停用，默认启用")
    private Integer status;

    private Integer sortOrder;
}
