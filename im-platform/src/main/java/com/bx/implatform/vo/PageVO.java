package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "分页结果")
public class PageVO<T> {

    private long total;

    private List<T> records;

    public static <T> PageVO<T> of(long total, List<T> records) {
        PageVO<T> p = new PageVO<>();
        p.setTotal(total);
        p.setRecords(records);
        return p;
    }
}
