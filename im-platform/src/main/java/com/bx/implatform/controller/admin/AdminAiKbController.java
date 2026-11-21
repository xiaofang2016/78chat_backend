package com.bx.implatform.controller.admin;

import com.bx.implatform.dto.AiKbEntryCreateDTO;
import com.bx.implatform.dto.AiKbEntryUpdateDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.AiKbCategoryService;
import com.bx.implatform.service.AiKbEntryService;
import com.bx.implatform.util.AdminGuard;
import com.bx.implatform.vo.AiKbCategoryMetaVO;
import com.bx.implatform.vo.AiKbEntryVO;
import com.bx.implatform.vo.PageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理后台-AI知识库")
@RestController
@RequestMapping("/admin/ai-kb")
@RequiredArgsConstructor
public class AdminAiKbController {

    private final AiKbEntryService entryService;
    private final AiKbCategoryService categoryService;
    private final AdminGuard adminGuard;

    @GetMapping("/category-meta")
    @Operation(summary = "启用中的分类及字段（供条目表单）")
    public Result<List<AiKbCategoryMetaVO>> categoryMeta() {
        adminGuard.requireAdmin();
        return ResultUtils.success(categoryService.listEnabled());
    }

    @GetMapping("/entries")
    @Operation(summary = "分页列表")
    public Result<PageVO<AiKbEntryVO>> page(
        @RequestParam(value = "category", required = false) String category,
        @RequestParam(value = "status", required = false) Integer status,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "size", defaultValue = "20") int size) {
        adminGuard.requireAdmin();
        return ResultUtils.success(entryService.page(category, status, keyword, page, size));
    }

    @GetMapping("/entries/{id}")
    @Operation(summary = "详情")
    public Result<AiKbEntryVO> detail(@PathVariable("id") Long id) {
        adminGuard.requireAdmin();
        return ResultUtils.success(entryService.detail(id));
    }

    @PostMapping("/entries")
    @Operation(summary = "新增")
    public Result<Long> create(@Valid @RequestBody AiKbEntryCreateDTO dto) {
        adminGuard.requireAdmin();
        return ResultUtils.success(entryService.create(dto, adminGuard.currentUserId()));
    }

    @PutMapping("/entries/{id}")
    @Operation(summary = "编辑")
    public Result<Void> update(@PathVariable("id") Long id, @Valid @RequestBody AiKbEntryUpdateDTO dto) {
        adminGuard.requireAdmin();
        entryService.update(id, dto, adminGuard.currentUserId());
        return ResultUtils.success();
    }

    @PutMapping("/entries/{id}/enable")
    @Operation(summary = "启用")
    public Result<Void> enable(@PathVariable("id") Long id) {
        adminGuard.requireAdmin();
        entryService.enable(id, adminGuard.currentUserId());
        return ResultUtils.success();
    }

    @PutMapping("/entries/{id}/disable")
    @Operation(summary = "停用")
    public Result<Void> disable(@PathVariable("id") Long id) {
        adminGuard.requireAdmin();
        entryService.disable(id, adminGuard.currentUserId());
        return ResultUtils.success();
    }

    @DeleteMapping("/entries/{id}")
    @Operation(summary = "删除")
    public Result<Void> delete(@PathVariable("id") Long id) {
        adminGuard.requireAdmin();
        entryService.delete(id, adminGuard.currentUserId());
        return ResultUtils.success();
    }
}
