package com.bx.implatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bx.implatform.config.props.AiKnowledgeBaseProperties;
import com.bx.implatform.entity.AiKbChunk;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.AiKbChunkMapper;
import com.bx.implatform.contant.Constant;
import com.bx.implatform.contant.OfficialCsNames;
import com.bx.implatform.service.AiKnowledgeBaseService;
import com.bx.implatform.service.KnowledgeEmbeddingService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import com.bx.implatform.util.AdminGuard;
import com.bx.implatform.vo.AiKbChunkVO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiKnowledgeBaseServiceImpl implements AiKnowledgeBaseService {

    private final AiKbChunkMapper chunkMapper;
    private final KnowledgeEmbeddingService embeddingService;
    private final AiKnowledgeBaseProperties kbProps;

    @EventListener(ApplicationReadyEvent.class)
    public void logRetrievalReady() {
        if (!kbProps.isEnabled()) {
            log.info("[ai-kb] retrieval off");
            return;
        }
        try {
            long n = countChunks();
            String sample = buildRetrievalContext("职圈网址是什么", OfficialCsNames.MEIMEI_ID);
            String top = "";
            int idx = sample.indexOf("来源：");
            if (idx >= 0) {
                int end = sample.indexOf('\n', idx);
                top = sample.substring(idx, end < 0 ? Math.min(sample.length(), idx + 80) : end);
            }
            log.info("[ai-kb] retrieval ready chunks={} {}", n, top);
        } catch (Exception e) {
            log.warn("[ai-kb] retrieval not ready: {}", e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int ingestDocument(String sourceTitle, String fullText, boolean replaceExisting) {
        if (sourceTitle == null || sourceTitle.isBlank()) {
            throw new GlobalException(400, "sourceTitle 不能为空");
        }
        if (fullText == null || fullText.isBlank()) {
            throw new GlobalException(400, "正文不能为空");
        }
        if (replaceExisting) {
            chunkMapper.delete(new LambdaQueryWrapper<AiKbChunk>()
                .eq(AiKbChunk::getSourceTitle, sourceTitle.trim()));
        }
        List<String> chunks = splitChunks(fullText, kbProps.getChunkSize(), kbProps.getChunkOverlap());
        if (chunks.isEmpty()) {
            return 0;
        }
        List<float[]> vectors = embeddingService.embedPassages(chunks);
        if (vectors.size() != chunks.size()) {
            throw new GlobalException(500, "Embedding 数量与分块不一致");
        }
        Date now = new Date();
        for (int i = 0; i < chunks.size(); i++) {
            float[] vec = vectors.get(i);
            AiKbChunk row = new AiKbChunk();
            row.setSourceTitle(sourceTitle.trim());
            row.setChunkIndex(i);
            row.setChunkText(chunks.get(i));
            row.setEmbeddingJson(toJsonArray(vec));
            row.setEmbeddingDims(vec.length);
            row.setCreatedTime(now);
            chunkMapper.insert(row);
        }
        log.info("[ai-kb] ingested source={} chunks={}", sourceTitle, chunks.size());
        return chunks.size();
    }

    @Override
    public String buildRetrievalContext(String userQuery, Long botUserId) {
        if (!kbProps.isEnabled()) {
            return "";
        }
        if (userQuery == null || userQuery.isBlank()) {
            return "";
        }
        List<AiKbChunk> all = chunksFor(botUserId);
        if (all.isEmpty()) {
            return "";
        }
        float[] q = embeddingService.embedQuery(userQuery);
        if (q.length == 0) {
            return "";
        }
        List<ScoredChunk> scored = new ArrayList<>();
        for (AiKbChunk c : all) {
            float[] v = parseEmbedding(c.getEmbeddingJson());
            if (v.length != q.length) {
                log.warn("[ai-kb] skip chunk id={} dims mismatch db={} query={}", c.getId(), v.length, q.length);
                continue;
            }
            scored.add(new ScoredChunk(c, cosineSimilarity(q, v)));
        }
        int k = Math.max(1, kbProps.getTopK());
        List<ScoredChunk> picked = pickWithAdminEntryPriority(scored, k);
        StringBuilder sb = new StringBuilder();
        sb.append("【知识库参考】以下片段与用户问题语义相近。");
        sb.append("**来源以 ").append(AdminGuard.ADMIN_CHUNK_SOURCE_PREFIX)
            .append(" 开头的是客服知识库标准说明，回答操作步骤、规则、金额和活动时必须优先采用，不要另编**；");
        sb.append("若涉及具体账户余额、订单、到账状态，仍以工具查询结果为准。\n\n");
        for (ScoredChunk s : picked) {
            boolean official = AdminGuard.isAdminKbChunkSource(s.chunk.getSourceTitle());
            sb.append("---\n");
            if (official) {
                sb.append("[官方知识库] ");
            }
            sb.append("来源：").append(s.chunk.getSourceTitle())
                .append(" [").append(s.chunk.getChunkIndex()).append("]\n");
            sb.append(s.chunk.getChunkText()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public double topKbSimilarity(String userQuery, Long botUserId) {
        if (!kbProps.isEnabled()) {
            return 0;
        }
        if (userQuery == null || userQuery.isBlank()) {
            return 0;
        }
        List<AiKbChunk> all = chunksFor(botUserId);
        if (all.isEmpty()) {
            return 0;
        }
        float[] q;
        try {
            q = embeddingService.embedQuery(userQuery);
        } catch (GlobalException e) {
            log.warn("[ai-kb] topKbSimilarity embedding failed, score=0: {}", e.getMessage());
            return 0;
        }
        if (q.length == 0) {
            return 0;
        }
        double best = 0;
        for (AiKbChunk c : all) {
            float[] v = parseEmbedding(c.getEmbeddingJson());
            if (v.length != q.length) {
                continue;
            }
            best = Math.max(best, cosineSimilarity(q, v));
        }
        return best;
    }

    /**
     * 吉祥只看 JX 文档，客服美美只看职圈文档。没有对应文档的客服不检索。
     */
    private List<AiKbChunk> chunksFor(Long botUserId) {
        String prefix = OfficialCsNames.knowledgeSourcePrefix(botUserId);
        if (prefix == null) {
            return List.of();
        }
        List<AiKbChunk> all = chunkMapper.selectList(null);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<AiKbChunk> matched = new ArrayList<>();
        for (AiKbChunk chunk : all) {
            if (chunk.getSourceTitle() != null && chunk.getSourceTitle().startsWith(prefix)) {
                matched.add(chunk);
            }
        }
        return matched;
    }

    /**
     * 先按相关度从管理后台条目（kb-entry-*）占满 topK 名额，不足再从历史片段补齐。
     */
    static List<ScoredChunk> pickWithAdminEntryPriority(List<ScoredChunk> scored, int k) {
        if (scored == null || scored.isEmpty() || k <= 0) {
            return List.of();
        }
        List<ScoredChunk> admin = new ArrayList<>();
        List<ScoredChunk> legacy = new ArrayList<>();
        for (ScoredChunk s : scored) {
            if (AdminGuard.isAdminKbChunkSource(s.chunk.getSourceTitle())) {
                admin.add(s);
            } else {
                legacy.add(s);
            }
        }
        Comparator<ScoredChunk> byScoreDesc = (a, b) -> Double.compare(b.score, a.score);
        admin.sort(byScoreDesc);
        legacy.sort(byScoreDesc);

        List<ScoredChunk> picked = new ArrayList<>(k);
        for (ScoredChunk s : admin) {
            if (picked.size() >= k) {
                break;
            }
            picked.add(s);
        }
        for (ScoredChunk s : legacy) {
            if (picked.size() >= k) {
                break;
            }
            picked.add(s);
        }
        return picked;
    }

    private static final class ScoredChunk {
        final AiKbChunk chunk;
        final double score;

        ScoredChunk(AiKbChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }

    @Override
    public long countChunks() {
        return chunkMapper.selectCount(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBySourceTitle(String sourceTitle) {
        if (sourceTitle == null || sourceTitle.isBlank()) {
            return;
        }
        chunkMapper.delete(new LambdaQueryWrapper<AiKbChunk>()
            .eq(AiKbChunk::getSourceTitle, sourceTitle.trim()));
    }

    @Override
    public List<String> listSourceTitles() {
        List<String> raw = chunkMapper.selectDistinctSourceTitles();
        return raw == null ? List.of() : raw;
    }

    @Override
    public List<AiKbChunkVO> listChunksBySourceTitle(String sourceTitle) {
        if (sourceTitle == null || sourceTitle.isBlank()) {
            throw new GlobalException(400, "sourceTitle 不能为空");
        }
        String t = sourceTitle.trim();
        List<AiKbChunk> rows = chunkMapper.selectList(Wrappers.<AiKbChunk>lambdaQuery()
            .eq(AiKbChunk::getSourceTitle, t)
            .orderByAsc(AiKbChunk::getChunkIndex));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<AiKbChunkVO> out = new ArrayList<>();
        for (AiKbChunk c : rows) {
            AiKbChunkVO vo = new AiKbChunkVO();
            vo.setId(c.getId());
            vo.setSourceTitle(c.getSourceTitle());
            vo.setChunkIndex(c.getChunkIndex());
            vo.setChunkText(c.getChunkText());
            vo.setEmbeddingDims(c.getEmbeddingDims());
            out.add(vo);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateChunkById(Long id, String newChunkText) {
        if (id == null || id <= 0) {
            throw new GlobalException(400, "id 无效");
        }
        if (newChunkText == null || newChunkText.isBlank()) {
            throw new GlobalException(400, "正文不能为空");
        }
        String normalized = normalizeChunkText(newChunkText);
        int bytes = Constant.utf8ByteLength(normalized);
        if (bytes > Constant.MAX_KNOWLEDGE_INGEST_BOT_UTF8_BYTES) {
            throw new GlobalException(400, "kb.chunkUtf8TooLarge", Constant.MAX_KNOWLEDGE_INGEST_BOT_UTF8_BYTES, bytes);
        }
        AiKbChunk row = chunkMapper.selectById(id);
        if (row == null) {
            throw new GlobalException(404, "知识库片段不存在");
        }
        float[] vec = embeddingService.embedPassage(normalized);
        if (vec.length == 0) {
            throw new GlobalException(500, "Embedding 为空");
        }
        row.setChunkText(normalized);
        row.setEmbeddingJson(toJsonArray(vec));
        row.setEmbeddingDims(vec.length);
        chunkMapper.updateById(row);
        log.info("[ai-kb] updated chunk id={} source={} index={}", id, row.getSourceTitle(), row.getChunkIndex());
    }

    static String normalizeChunkText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    static List<String> splitChunks(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            chunkSize = 600;
        }
        if (overlap < 0) {
            overlap = 0;
        }
        if (overlap >= chunkSize) {
            overlap = chunkSize / 4;
        }
        String t = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int step = Math.max(1, chunkSize - overlap);
        for (int i = 0; i < t.length(); i += step) {
            int end = Math.min(i + chunkSize, t.length());
            out.add(t.substring(i, end));
            if (end >= t.length()) {
                break;
            }
        }
        return out;
    }

    static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    static String toJsonArray(float[] vec) {
        JSONArray arr = new JSONArray();
        for (float v : vec) {
            arr.add(v);
        }
        return JSON.toJSONString(arr);
    }

    static float[] parseEmbedding(String json) {
        if (json == null || json.isBlank()) {
            return new float[0];
        }
        JSONArray arr = JSON.parseArray(json);
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = arr.getFloatValue(i);
        }
        return v;
    }
}
