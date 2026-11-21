package com.bx.implatform.service;

import java.util.List;

/**
 * 调用 OpenAI {@code /v1/embeddings}（参见官方 Embeddings 指南）。
 */
public interface OpenAiEmbeddingService {

    float[] embedText(String text);

    List<float[]> embedTexts(List<String> texts);
}
