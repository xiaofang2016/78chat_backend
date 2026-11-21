package com.bx.implatform.controller;

import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.AiKbCatalogService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.vo.AiKbCategoryPublicVO;
import com.bx.implatform.vo.AiKbEntryPublicVO;
import com.bx.implatform.vo.PageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "AI知识库-对外只读")
@RestController
@RequestMapping("/ai/kb/catalog")
@RequiredArgsConstructor
public class AiKbCatalogController {

    private final AiKbCatalogService catalogService;

    @GetMapping("/categories")
    @Operation(summary = "启用中的分类列表", description = "任意登录用户可访问")
    public Result<List<AiKbCategoryPublicVO>> categories() {
        SessionContext.getSession();
        return ResultUtils.success(catalogService.listEnabledCategories());
    }

    @GetMapping("/entries")
    @Operation(summary = "条目分页列表", description = "仅返回启用中的条目；任意登录用户可访问")
    public Result<PageVO<AiKbEntryPublicVO>> entries(
        @RequestParam(value = "category", required = false) String category,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "size", defaultValue = "20") int size) {
        SessionContext.getSession();
        return ResultUtils.success(catalogService.pageEnabledEntries(category, keyword, page, size));
    }

    @GetMapping("/entries/{id}")
    @Operation(summary = "条目详情", description = "仅启用中的条目可访问；任意登录用户可访问")
    public Result<AiKbEntryPublicVO> entryDetail(@PathVariable("id") Long id) {
        SessionContext.getSession();
        return ResultUtils.success(catalogService.getEnabledEntry(id));
    }
}
