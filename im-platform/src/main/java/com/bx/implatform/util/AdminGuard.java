package com.bx.implatform.util;

import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.session.SessionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台权限校验
 */
@Component
@RequiredArgsConstructor
public class AdminGuard {

    private final AiCustomerServiceProperties aiCustomerServiceProperties;

    public void requireAdmin() {
        var session = SessionContext.getSession();
        if (session == null || session.getUserId() == null) {
            throw new GlobalException(401, "未登录");
        }
        if (!KnowledgeIngestBotVisibility.isIngestAdmin(aiCustomerServiceProperties, session.getUserId())) {
            throw new GlobalException(403, "无管理后台权限");
        }
    }

    public Long currentUserId() {
        return SessionContext.getSession().getUserId();
    }

    public static Map<String, String> normalizeFields(Map<String, String> fields) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fields == null) {
            return out;
        }
        fields.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k.trim(), v.trim());
            }
        });
        return out;
    }

    /** 管理后台结构化知识库条目在 im_ai_kb_chunk.source_title 中的前缀 */
    public static final String ADMIN_CHUNK_SOURCE_PREFIX = "kb-entry-";

    public static String chunkSourceForId(long id) {
        return ADMIN_CHUNK_SOURCE_PREFIX + id;
    }

    public static boolean isAdminKbChunkSource(String sourceTitle) {
        return sourceTitle != null && sourceTitle.startsWith(ADMIN_CHUNK_SOURCE_PREFIX);
    }
}
