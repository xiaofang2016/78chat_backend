package com.bx.implatform.controller.admin;

import com.bx.implatform.dto.AiKbCategoryCreateDTO;
import com.bx.implatform.dto.AiKbCategoryUpdateDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.AiKbCategoryService;
import com.bx.implatform.util.AdminGuard;
import com.bx.implatform.vo.AiKbCategoryMetaVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理后台-AI知识库分类")
@RestController
@RequestMapping("/admin/ai-kb/categories")
@RequiredArgsConstructor
public class AdminAiKbCategoryController {

    private final AiKbCategoryService categoryService;
    private final AdminGuard adminGuard;

    @GetMapping
    @Operation(summary = "全部分类（含停用）")
    public Result<List<AiKbCategoryMetaVO>> list() {
        adminGuard.requireAdmin();
        return ResultUtils.success(categoryService.listAll());
    }

    @GetMapping("/enabled")
    @Operation(summary = "启用中的分类")
    public Result<List<AiKbCategoryMetaVO>> listEnabled() {
        adminGuard.requireAdmin();
        return ResultUtils.success(categoryService.listEnabled());
    }

    @GetMapping("/{id}")
    @Operation(summary = "分类详情")
    public Result<AiKbCategoryMetaVO> detail(@PathVariable("id") Long id) {
        adminGuard.requireAdmin();
        return ResultUtils.success(categoryService.getById(id));
    }

    @PostMapping
    @Operation(summary = "新增分类")
    public Result<Long> create(@Valid @RequestBody AiKbCategoryCreateDTO dto) {
        adminGuard.requireAdmin();
        return ResultUtils.success(categoryService.create(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑分类")
    public Result<Void> update(@PathVariable("id") Long id, @Valid @RequestBody AiKbCategoryUpdateDTO dto) {
        adminGuard.requireAdmin();
        categoryService.update(id, dto);
        return ResultUtils.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除分类")
    public Result<Void> delete(@PathVariable("id") Long id) {
        adminGuard.requireAdmin();
        categoryService.delete(id);
        return ResultUtils.success();
    }
}
