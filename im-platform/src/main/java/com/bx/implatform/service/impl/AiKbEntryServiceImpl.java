package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bx.implatform.dto.AiKbEntryCreateDTO;
import com.bx.implatform.dto.AiKbEntryUpdateDTO;
import com.bx.implatform.entity.AiKbEntry;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.AiKbEntryMapper;
import com.bx.implatform.service.AiKbCategoryService;
import com.bx.implatform.service.AiKbEntryService;
import com.bx.implatform.service.AiKnowledgeBaseService;
import com.bx.implatform.util.AdminGuard;
import com.bx.implatform.vo.AiKbCategoryMetaVO;
import com.bx.implatform.vo.AiKbEntryVO;
import com.bx.implatform.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiKbEntryServiceImpl implements AiKbEntryService {

    private final AiKbEntryMapper entryMapper;
    private final AiKnowledgeBaseService knowledgeBaseService;
    private final AiKbCategoryService categoryService;

    @Override
    public PageVO<AiKbEntryVO> page(String category, Integer status, String keyword, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        LambdaQueryWrapper<AiKbEntry> q = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank()) {
            q.eq(AiKbEntry::getCategory, category.trim().toUpperCase());
        }
        if (status != null) {
            q.eq(AiKbEntry::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(AiKbEntry::getTitle, kw).or().like(AiKbEntry::getFieldsJson, kw));
        }
        q.orderByAsc(AiKbEntry::getSortOrder).orderByDesc(AiKbEntry::getUpdatedTime);
        Page<AiKbEntry> result = entryMapper.selectPage(new Page<>(p, s), q);
        List<AiKbEntryVO> records = result.getRecords().stream().map(this::toVo).collect(Collectors.toList());
        return PageVO.of(result.getTotal(), records);
    }

    @Override
    public AiKbEntryVO detail(Long id) {
        AiKbEntry row = requireEntry(id);
        return toVo(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiKbEntryCreateDTO dto, Long operatorId) {
        String categoryCode = dto.getCategory().trim().toUpperCase();
        categoryService.requireEnabledByCode(categoryCode);
        Map<String, String> fields = AdminGuard.normalizeFields(dto.getFields());
        categoryService.validateFields(categoryCode, fields);

        int status = dto.getStatus() == null ? AiKbEntry.STATUS_ENABLED : dto.getStatus();
        if (status != AiKbEntry.STATUS_ENABLED && status != AiKbEntry.STATUS_DISABLED) {
            throw new GlobalException(400, "status 无效");
        }

        AiKbEntry row = new AiKbEntry();
        row.setCategory(categoryCode);
        row.setStatus(status);
        row.setTitle(dto.getTitle().trim());
        row.setFieldsJson(JSON.toJSONString(fields));
        row.setChunkSource("");
        row.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        row.setCreatedBy(operatorId);
        row.setUpdatedBy(operatorId);
        Date now = new Date();
        row.setCreatedTime(now);
        row.setUpdatedTime(now);
        entryMapper.insert(row);

        String chunkSource = AdminGuard.chunkSourceForId(row.getId());
        row.setChunkSource(chunkSource);
        entryMapper.updateById(row);

        if (status == AiKbEntry.STATUS_ENABLED) {
            syncToVector(row, fields);
        }
        log.info("[admin-kb] created id={} category={} by={}", row.getId(), categoryCode, operatorId);
        return row.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AiKbEntryUpdateDTO dto, Long operatorId) {
        AiKbEntry row = requireEntry(id);
        Map<String, String> fields = AdminGuard.normalizeFields(dto.getFields());
        categoryService.validateFields(row.getCategory(), fields);

        row.setTitle(dto.getTitle().trim());
        row.setFieldsJson(JSON.toJSONString(fields));
        if (dto.getSortOrder() != null) {
            row.setSortOrder(dto.getSortOrder());
        }
        row.setUpdatedBy(operatorId);
        row.setUpdatedTime(new Date());
        entryMapper.updateById(row);

        if (row.getStatus() == AiKbEntry.STATUS_ENABLED) {
            syncToVector(row, fields);
        }
        log.info("[admin-kb] updated id={} by={}", id, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enable(Long id, Long operatorId) {
        AiKbEntry row = requireEntry(id);
        categoryService.requireEnabledByCode(row.getCategory());
        if (row.getStatus() == AiKbEntry.STATUS_ENABLED) {
            return;
        }
        row.setStatus(AiKbEntry.STATUS_ENABLED);
        row.setUpdatedBy(operatorId);
        row.setUpdatedTime(new Date());
        entryMapper.updateById(row);

        Map<String, String> fields = parseFields(row.getFieldsJson());
        syncToVector(row, fields);
        log.info("[admin-kb] enabled id={} by={}", id, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disable(Long id, Long operatorId) {
        AiKbEntry row = requireEntry(id);
        if (row.getStatus() == AiKbEntry.STATUS_DISABLED) {
            return;
        }
        row.setStatus(AiKbEntry.STATUS_DISABLED);
        row.setUpdatedBy(operatorId);
        row.setUpdatedTime(new Date());
        entryMapper.updateById(row);

        if (row.getChunkSource() != null && !row.getChunkSource().isBlank()) {
            knowledgeBaseService.deleteBySourceTitle(row.getChunkSource());
        }
        log.info("[admin-kb] disabled id={} by={}", id, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        AiKbEntry row = requireEntry(id);
        if (row.getChunkSource() != null && !row.getChunkSource().isBlank()) {
            knowledgeBaseService.deleteBySourceTitle(row.getChunkSource());
        }
        entryMapper.deleteById(id);
        log.info("[admin-kb] deleted id={} by={}", id, operatorId);
    }

    private void syncToVector(AiKbEntry row, Map<String, String> fields) {
        String text = categoryService.renderEntryText(row.getCategory(), row.getTitle(), fields);
        String source = row.getChunkSource();
        if (source == null || source.isBlank()) {
            source = AdminGuard.chunkSourceForId(row.getId());
            row.setChunkSource(source);
            entryMapper.updateById(row);
        }
        knowledgeBaseService.ingestDocument(source, text, true);
    }

    private AiKbEntry requireEntry(Long id) {
        if (id == null || id <= 0) {
            throw new GlobalException(400, "id 无效");
        }
        AiKbEntry row = entryMapper.selectById(id);
        if (row == null) {
            throw new GlobalException(404, "知识库条目不存在");
        }
        return row;
    }

    private AiKbEntryVO toVo(AiKbEntry row) {
        AiKbEntryVO vo = new AiKbEntryVO();
        vo.setId(row.getId());
        vo.setCategory(row.getCategory());
        try {
            AiKbCategoryMetaVO meta = categoryService.getByCode(row.getCategory());
            vo.setCategoryLabel(meta.getLabel());
        } catch (Exception e) {
            vo.setCategoryLabel(row.getCategory());
        }
        vo.setStatus(row.getStatus());
        vo.setTitle(row.getTitle());
        vo.setFields(parseFields(row.getFieldsJson()));
        vo.setChunkSource(row.getChunkSource());
        vo.setSortOrder(row.getSortOrder());
        vo.setCreatedBy(row.getCreatedBy());
        vo.setUpdatedBy(row.getUpdatedBy());
        vo.setCreatedTime(row.getCreatedTime());
        vo.setUpdatedTime(row.getUpdatedTime());
        return vo;
    }

    private static Map<String, String> parseFields(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return JSON.parseObject(json, new TypeReference<Map<String, String>>() {});
    }
}
