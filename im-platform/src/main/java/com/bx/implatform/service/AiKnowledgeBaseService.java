package com.bx.implatform.service;

import com.bx.implatform.vo.AiKbChunkVO;

import java.util.List;

/**
 * 基于本机向量服务的文本知识库：分块、入库、余弦相似度检索。
 */
public interface AiKnowledgeBaseService {

    /**
     * 将长文本分块、向量化并写入数据库；可选先删除同源旧片段。
     *
     * @return 写入的片段条数
     */
    int ingestDocument(String sourceTitle, String fullText, boolean replaceExisting);

    /**
     * 对用户问题做向量检索，只取该客服自己的文档，格式化为可拼进 system prompt 的参考文本。
     * 无命中或该客服没有文档时返回空串。
     */
    String buildRetrievalContext(String userQuery, Long botUserId);

    /**
     * 对用户问题做向量检索，只在该客服自己的文档里取最高余弦相似度；无片段时返回 0。
     */
    double topKbSimilarity(String userQuery, Long botUserId);

    long countChunks();

    void deleteBySourceTitle(String sourceTitle);

    /** 全部来源标题（去重） */
    List<String> listSourceTitles();

    /** 某来源下的全部片段（按 chunkIndex 排序） */
    List<AiKbChunkVO> listChunksBySourceTitle(String sourceTitle);

    /**
     * 按主键更新单条片段正文并重新生成 embedding（维数须与当前模型一致）。
     */
    void updateChunkById(Long id, String newChunkText);
}
