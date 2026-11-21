package com.bx.implatform.service;

import java.util.List;

/**
 * 知识库向量。本机向量服务计算，不走 OpenAI，也不走 Codex。
 * 用户问题用 query，知识正文用 passage，两边前缀必须分开。
 */
public interface KnowledgeEmbeddingService {

    int dimensions();

    float[] embedQuery(String text);

    float[] embedPassage(String text);

    List<float[]> embedPassages(List<String> texts);
}
