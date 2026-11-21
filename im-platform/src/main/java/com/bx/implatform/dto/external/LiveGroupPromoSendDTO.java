package com.bx.implatform.dto.external;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LiveGroupPromoSendDTO {

    @NotBlank
    private String contentHtml;
}
