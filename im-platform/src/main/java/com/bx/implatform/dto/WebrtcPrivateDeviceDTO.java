package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单人通话设备状态同步
 */
@Data
@Schema(description = "单人通话设备操作DTO")
public class WebrtcPrivateDeviceDTO {

    @NotNull(message = "对方用户id不可为空")
    @Schema(description = "通话对方用户id")
    private Long uid;

    @NotNull(message = "isCamera不可为空")
    @Schema(description = "是否开启摄像头")
    private Boolean isCamera;

    @NotNull(message = "isMicroPhone不可为空")
    @Schema(description = "是否开启麦克风")
    private Boolean isMicroPhone;

    @NotNull(message = "isShareScreen不可为空")
    @Schema(description = "是否共享屏幕")
    private Boolean isShareScreen;
}
