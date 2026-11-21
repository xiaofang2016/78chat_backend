package com.bx.implatform.controller;

import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.dto.AiKbChunkUpdateDTO;
import com.bx.implatform.dto.AiKbIngestDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.AiKnowledgeBaseService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.util.KnowledgeIngestBotVisibility;
import com.bx.implatform.vo.AiKbChunkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "AI知识库")
@RestController
@RequestMapping("/ai/kb")
@RequiredArgsConstructor
public class AiKnowledgeBaseController {

    private final AiKnowledgeBaseService knowledgeBaseService;
    private final AiCustomerServiceProperties aiCustomerServiceProperties;

    @GetMapping("/quick-entry-visible")
    @Operation(summary = "会话页是否展示「AI知识库」快捷入口", description = "仅知识库录入管理员可见")
    public Result<Boolean> quickEntryVisible() {
        var session = SessionContext.getSession();
        boolean v = KnowledgeIngestBotVisibility.showKbQuickEntryToViewer(aiCustomerServiceProperties,
            session.getUserId());
        return ResultUtils.success(v);
    }

    @PostMapping("/ingest")
    @Operation(summary = "导入知识（分块+Embedding 入库）", description = "向量由本机知识库服务计算；数据写入 im_ai_kb_chunk")
    public Result<Void> ingest(@Valid @RequestBody AiKbIngestDTO dto) {
        SessionContext.getSession();
        boolean replace = dto.getReplaceExisting() == null || dto.getReplaceExisting();
        knowledgeBaseService.ingestDocument(dto.getTitle(), dto.getContent(), replace);
        return ResultUtils.success();
    }

    @GetMapping("/stats")
    @Operation(summary = "知识库片段数量")
    public Result<Long> stats() {
        SessionContext.getSession();
        return ResultUtils.success(knowledgeBaseService.countChunks());
    }

    @DeleteMapping("/source")
    @Operation(summary = "按来源标题删除全部片段")
    public Result<Void> deleteBySource(@RequestParam("title") String title) {
        SessionContext.getSession();
        knowledgeBaseService.deleteBySourceTitle(title);
        return ResultUtils.success();
    }

    @GetMapping("/sources")
    @Operation(summary = "列出全部知识来源标题")
    public Result<List<String>> listSources() {
        SessionContext.getSession();
        return ResultUtils.success(knowledgeBaseService.listSourceTitles());
    }

    @GetMapping("/source/chunks")
    @Operation(summary = "按来源标题列出片段（可编辑）", description = "title 为入库时的来源标题")
    public Result<List<AiKbChunkVO>> listChunks(@RequestParam("title") String title) {
        SessionContext.getSession();
        return ResultUtils.success(knowledgeBaseService.listChunksBySourceTitle(title));
    }

    @PutMapping("/chunk/{id}")
    @Operation(summary = "更新单条片段并重算向量")
    public Result<Void> updateChunk(@PathVariable("id") Long id, @Valid @RequestBody AiKbChunkUpdateDTO dto) {
        SessionContext.getSession();
        knowledgeBaseService.updateChunkById(id, dto.getContent());
        return ResultUtils.success();
    }
}
