package com.bx.implatform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiKbIngestDTO {

    @NotBlank(message = "title不能为空")
    private String title;

    @NotBlank(message = "content不能为空")
    private String content;

    /** 为 true 时先删除同 title 下旧片段再写入 */
    private Boolean replaceExisting = true;
}
