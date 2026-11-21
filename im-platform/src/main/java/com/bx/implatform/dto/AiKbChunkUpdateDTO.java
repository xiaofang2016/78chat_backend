package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

@Data
public class AiKbChunkUpdateDTO {

    @Schema(description = "新的片段正文；将重新调用 Embeddings API 写入向量")
    @NotBlank(message = "content不能为空")
    @Length(max = 131072, message = "片段过长")
    private String content;
}
