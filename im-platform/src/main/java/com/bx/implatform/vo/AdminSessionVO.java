package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理后台当前用户")
public class AdminSessionVO {

    private Long userId;

    private String userName;

    private String nickName;

    @Schema(description = "是否有管理后台权限")
    private boolean admin;
}
