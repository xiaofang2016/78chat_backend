package com.bx.implatform.vo;

import lombok.Data;

/**
 * 知识库片段（管理/编辑用，不含向量明细）
 */
@Data
public class AiKbChunkVO {

    private Long id;

    private String sourceTitle;

    private Integer chunkIndex;

    /** 片段正文（可能与入库时格式化一致） */
    private String chunkText;

    private Integer embeddingDims;
}
