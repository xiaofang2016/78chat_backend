package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "更新 AI 知识库条目")
public class AiKbEntryUpdateDTO {

    @NotBlank(message = "title 不能为空")
    private String title;

    @NotNull(message = "fields 不能为空")
    private Map<String, String> fields;

    private Integer sortOrder;
}
