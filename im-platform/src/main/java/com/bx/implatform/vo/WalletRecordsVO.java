package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "钱包流水")
public class WalletRecordsVO {

    private String merchantNo;

    @Schema(description = "bill / redpacket / withdraw")
    private String kind;

    private List<WalletRecordItemVO> records = new ArrayList<>();
}
