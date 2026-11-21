package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * AI 知识库片段（一条记录 = 一段文本 + 对应 embedding）
 */
@Data
@TableName("im_ai_kb_chunk")
public class AiKbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sourceTitle;

    private Integer chunkIndex;

    private String chunkText;

    /** JSON 数组，与 OpenAI {@code /v1/embeddings} 返回的 embedding 一致 */
    private String embeddingJson;

    private Integer embeddingDims;

    private Date createdTime;
}
