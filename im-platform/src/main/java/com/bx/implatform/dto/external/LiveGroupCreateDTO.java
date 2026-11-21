package com.bx.implatform.dto.external;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

@Data
@Schema(description = "创建直播间公开群")
public class LiveGroupCreateDTO {

    @NotNull(message = "ownerUserId 不可为空")
    @Schema(description = "群主/主播用户 ID（im_user.id）")
    private Long ownerUserId;

    @NotBlank(message = "群名称不可为空")
    @Length(max = 32, message = "群名称长度不能大于32")
    @Schema(description = "群名称，通常为直播间标题")
    private String name;
}
