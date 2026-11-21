package com.bx.implatform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiChatDTO {
    @NotBlank(message = "content不能为空")
    private String content;
}

