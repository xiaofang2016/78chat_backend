package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 客服知识库（本机向量检索），配置前缀 {@code ai-cs.knowledge-base}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-cs.knowledge-base")
public class AiKnowledgeBaseProperties {

    /** 是否在对话前检索知识库并注入上下文 */
    private boolean enabled = false;

    /** 允许检索的客服。吉祥体育客服查 JX 文档，客服美美和说球帝通讯录客服查职圈文档。 */
    private List<Long> botUserIds = new ArrayList<>(List.of(
        9000000004L, 900001L,
        9000000005L, 9000000006L, 9000000007L, 9000000008L,
        9000000009L, 9000000010L, 9000000011L, 9000000012L));

    /** 本机向量服务。对话仍走 Codex，不使用这项。 */
    private String embeddingUrl = "http://127.0.0.1:8791";

    /** 每次对话取最相近的片段条数 */
    private int topK = 5;

    /** 入库时分块字符长度（近似 token，勿过大以免超模型 8192 token 输入） */
    private int chunkSize = 600;

    /** 相邻分块重叠字符数，减轻边界截断 */
    private int chunkOverlap = 80;

    public boolean appliesTo(Long botUserId) {
        if (!enabled || botUserId == null || botUserIds == null) {
            return false;
        }
        for (Long id : botUserIds) {
            if (id != null && id.longValue() == botUserId.longValue()) {
                return true;
            }
        }
        return false;
    }
}
