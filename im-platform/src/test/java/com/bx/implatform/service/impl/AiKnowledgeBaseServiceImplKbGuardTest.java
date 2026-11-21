package com.bx.implatform.service.impl;

import com.bx.implatform.config.props.AiKnowledgeBaseProperties;
import com.bx.implatform.contant.OfficialCsNames;
import com.bx.implatform.entity.AiKbChunk;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.AiKbChunkMapper;
import com.bx.implatform.service.KnowledgeEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiKnowledgeBaseServiceImplKbGuardTest {

    @Mock
    private AiKbChunkMapper chunkMapper;
    @Mock
    private KnowledgeEmbeddingService embeddingService;
    @Mock
    private AiKnowledgeBaseProperties kbProps;

    @InjectMocks
    private AiKnowledgeBaseServiceImpl service;

    @BeforeEach
    void kbEnabledByDefault() {
        when(kbProps.isEnabled()).thenReturn(true);
    }

    @Test
    void topKbSimilarity_whenKbDisabled_doesNotCallEmbedding() {
        when(kbProps.isEnabled()).thenReturn(false);

        assertEquals(0, service.topKbSimilarity("有什么活动", OfficialCsNames.MEIMEI_ID));

        verify(embeddingService, never()).embedQuery(org.mockito.ArgumentMatchers.anyString());
        verify(chunkMapper, never()).selectList(null);
    }

    @Test
    void buildRetrievalContext_whenKbDisabled_returnsEmpty() {
        when(kbProps.isEnabled()).thenReturn(false);

        assertEquals("", service.buildRetrievalContext("有什么活动", OfficialCsNames.XIAOHONG_ID));

        verify(embeddingService, never()).embedQuery(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void topKbSimilarity_whenEmbeddingFails_returnsZero() {
        AiKbChunk chunk = new AiKbChunk();
        chunk.setSourceTitle("kb-entry-ZQ-001 职圈网址是什么？");
        chunk.setEmbeddingJson("[1.0,0.0]");
        when(chunkMapper.selectList(null)).thenReturn(List.of(chunk));
        when(embeddingService.embedQuery("q")).thenThrow(new GlobalException(500, "Embedding 请求失败"));

        assertEquals(0, service.topKbSimilarity("q", OfficialCsNames.MEIMEI_ID));
    }

    @Test
    void buildRetrievalContext_otherBot_doesNotSearch() {
        assertEquals("", service.buildRetrievalContext("怎么注册", OfficialCsNames.XIAOLV_ID));

        verify(chunkMapper, never()).selectList(null);
        verify(embeddingService, never()).embedQuery(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void buildRetrievalContext_keepsEachBotOnItsOwnDocument() {
        AiKbChunk jixiang = new AiKbChunk();
        jixiang.setSourceTitle("kb-entry-JX-001 如何注册开户");
        jixiang.setChunkIndex(0);
        jixiang.setChunkText("您好，欢迎来到吉祥体育");
        jixiang.setEmbeddingJson("[1.0,0.0]");
        AiKbChunk zhiquan = new AiKbChunk();
        zhiquan.setSourceTitle("kb-entry-ZQ-001 职圈网址是什么？");
        zhiquan.setChunkIndex(0);
        zhiquan.setChunkText("职圈网址在这里");
        zhiquan.setEmbeddingJson("[0.0,1.0]");
        when(chunkMapper.selectList(null)).thenReturn(List.of(jixiang, zhiquan));
        when(embeddingService.embedQuery("注册")).thenReturn(new float[] {1f, 0f});

        String jixiangContext = service.buildRetrievalContext("注册", OfficialCsNames.XIAOHONG_ID);
        String meimeiContext = service.buildRetrievalContext("注册", OfficialCsNames.MEIMEI_ID);

        assertTrue(jixiangContext.contains("吉祥体育"));
        assertFalse(jixiangContext.contains("职圈网址"));
        assertTrue(meimeiContext.contains("职圈网址"));
        assertFalse(meimeiContext.contains("吉祥体育"));
    }
}
