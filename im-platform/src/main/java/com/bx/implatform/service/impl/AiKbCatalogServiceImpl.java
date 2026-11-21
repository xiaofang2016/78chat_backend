package com.bx.implatform.service.impl;

import com.bx.implatform.entity.AiKbEntry;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.service.AiKbCatalogService;
import com.bx.implatform.service.AiKbCategoryService;
import com.bx.implatform.service.AiKbEntryService;
import com.bx.implatform.vo.AiKbCategoryMetaVO;
import com.bx.implatform.vo.AiKbCategoryPublicVO;
import com.bx.implatform.vo.AiKbEntryPublicVO;
import com.bx.implatform.vo.AiKbEntryVO;
import com.bx.implatform.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiKbCatalogServiceImpl implements AiKbCatalogService {

    private final AiKbCategoryService categoryService;
    private final AiKbEntryService entryService;

    @Override
    public List<AiKbCategoryPublicVO> listEnabledCategories() {
        return categoryService.listEnabled().stream()
            .map(this::toPublicCategory)
            .collect(Collectors.toList());
    }

    @Override
    public PageVO<AiKbEntryPublicVO> pageEnabledEntries(String category, String keyword, int page, int size) {
        PageVO<AiKbEntryVO> pageVo = entryService.page(category, AiKbEntry.STATUS_ENABLED, keyword, page, size);
        List<AiKbEntryPublicVO> records = pageVo.getRecords().stream()
            .map(this::toPublicEntry)
            .collect(Collectors.toList());
        return PageVO.of(pageVo.getTotal(), records);
    }

    @Override
    public AiKbEntryPublicVO getEnabledEntry(Long id) {
        AiKbEntryVO vo = entryService.detail(id);
        if (!Objects.equals(vo.getStatus(), AiKbEntry.STATUS_ENABLED)) {
            throw new GlobalException(404, "知识库条目不存在或已停用");
        }
        return toPublicEntry(vo);
    }

    private AiKbCategoryPublicVO toPublicCategory(AiKbCategoryMetaVO meta) {
        AiKbCategoryPublicVO vo = new AiKbCategoryPublicVO();
        vo.setId(meta.getId());
        vo.setCode(meta.getCode());
        vo.setLabel(meta.getLabel());
        vo.setSortOrder(meta.getSortOrder());
        vo.setFields(meta.getFields());
        return vo;
    }

    private AiKbEntryPublicVO toPublicEntry(AiKbEntryVO row) {
        AiKbEntryPublicVO vo = new AiKbEntryPublicVO();
        vo.setId(row.getId());
        vo.setCategory(row.getCategory());
        vo.setCategoryLabel(row.getCategoryLabel());
        vo.setTitle(row.getTitle());
        vo.setFields(row.getFields());
        vo.setSortOrder(row.getSortOrder());
        vo.setUpdatedTime(row.getUpdatedTime());
        return vo;
    }
}
