package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bx.implatform.dto.AiKbCategoryCreateDTO;
import com.bx.implatform.dto.AiKbCategoryUpdateDTO;
import com.bx.implatform.entity.AiKbCategoryDef;
import com.bx.implatform.entity.AiKbEntry;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.AiKbCategoryDefMapper;
import com.bx.implatform.mapper.AiKbEntryMapper;
import com.bx.implatform.service.AiKbCategoryService;
import com.bx.implatform.service.AiKnowledgeBaseService;
import com.bx.implatform.util.AdminGuard;
import com.bx.implatform.vo.AiKbCategoryMetaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiKbCategoryServiceImpl implements AiKbCategoryService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern FIELD_KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    private final AiKbCategoryDefMapper categoryMapper;
    private final AiKbEntryMapper entryMapper;
    private final AiKnowledgeBaseService knowledgeBaseService;

    @Override
    public List<AiKbCategoryMetaVO> listAll() {
        return loadOrdered(null);
    }

    @Override
    public List<AiKbCategoryMetaVO> listEnabled() {
        return loadOrdered(AiKbCategoryDef.STATUS_ENABLED);
    }

    @Override
    public AiKbCategoryMetaVO getByCode(String code) {
        AiKbCategoryDef row = findByCode(code);
        if (row == null) {
            throw new GlobalException(404, "分类不存在");
        }
        return toVo(row);
    }

    @Override
    public AiKbCategoryMetaVO getById(Long id) {
        return toVo(requireById(id));
    }

    @Override
    public AiKbCategoryDef requireEnabledByCode(String code) {
        AiKbCategoryDef row = findByCode(code);
        if (row == null) {
            throw new GlobalException(400, "分类不存在");
        }
        if (row.getStatus() != AiKbCategoryDef.STATUS_ENABLED) {
            throw new GlobalException(400, "分类已停用");
        }
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiKbCategoryCreateDTO dto) {
        String code = normalizeCode(dto.getCode());
        if (findByCode(code) != null) {
            throw new GlobalException(400, "分类代码已存在");
        }
        List<AiKbCategoryMetaVO.AiKbFieldMetaVO> fields = validateFieldDefs(dto.getFields());
        int status = dto.getStatus() == null ? AiKbCategoryDef.STATUS_ENABLED : dto.getStatus();

        AiKbCategoryDef row = new AiKbCategoryDef();
        row.setCode(code);
        row.setLabel(dto.getLabel().trim());
        row.setFieldsJson(JSON.toJSONString(fields));
        row.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        row.setStatus(status);
        Date now = new Date();
        row.setCreatedTime(now);
        row.setUpdatedTime(now);
        categoryMapper.insert(row);
        log.info("[admin-kb] category created code={}", code);
        return row.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AiKbCategoryUpdateDTO dto) {
        AiKbCategoryDef row = requireById(id);
        List<AiKbCategoryMetaVO.AiKbFieldMetaVO> fields = validateFieldDefs(dto.getFields());
        row.setLabel(dto.getLabel().trim());
        row.setFieldsJson(JSON.toJSONString(fields));
        if (dto.getSortOrder() != null) {
            row.setSortOrder(dto.getSortOrder());
        }
        if (dto.getStatus() != null) {
            row.setStatus(dto.getStatus());
        }
        row.setUpdatedTime(new Date());
        categoryMapper.updateById(row);

        resyncEnabledEntries(row.getCode());
        log.info("[admin-kb] category updated id={} code={}", id, row.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AiKbCategoryDef row = requireById(id);
        long count = entryMapper.selectCount(new LambdaQueryWrapper<AiKbEntry>()
            .eq(AiKbEntry::getCategory, row.getCode()));
        if (count > 0) {
            throw new GlobalException(400, "该分类下仍有 " + count + " 条知识库条目，无法删除");
        }
        categoryMapper.deleteById(id);
        log.info("[admin-kb] category deleted id={} code={}", id, row.getCode());
    }

    @Override
    public void validateFields(String categoryCode, Map<String, String> fields) {
        AiKbCategoryMetaVO meta = getByCode(categoryCode);
        if (meta.getStatus() != null && meta.getStatus() != AiKbCategoryDef.STATUS_ENABLED) {
            throw new GlobalException(400, "分类已停用");
        }
        if (fields == null || fields.isEmpty()) {
            throw new GlobalException(400, "fields 不能为空");
        }
        for (AiKbCategoryMetaVO.AiKbFieldMetaVO f : meta.getFields()) {
            String val = fields.get(f.getKey());
            if (f.isRequired() && (val == null || val.isBlank())) {
                throw new GlobalException(400, f.getLabel() + " 不能为空");
            }
        }
    }

    @Override
    public String renderEntryText(String categoryCode, String title, Map<String, String> fields) {
        AiKbCategoryMetaVO meta = getByCode(categoryCode);
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(meta.getLabel()).append("】\n");
        if (title != null && !title.isBlank()) {
            sb.append("标题：").append(title.trim()).append("\n");
        }
        for (AiKbCategoryMetaVO.AiKbFieldMetaVO f : meta.getFields()) {
            String val = fields.get(f.getKey());
            if (val != null && !val.isBlank()) {
                sb.append(f.getLabel()).append("：").append(val.trim()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private void resyncEnabledEntries(String categoryCode) {
        List<AiKbEntry> entries = entryMapper.selectList(new LambdaQueryWrapper<AiKbEntry>()
            .eq(AiKbEntry::getCategory, categoryCode)
            .eq(AiKbEntry::getStatus, AiKbEntry.STATUS_ENABLED));
        for (AiKbEntry e : entries) {
            try {
                Map<String, String> fields = JSON.parseObject(e.getFieldsJson(),
                    new TypeReference<Map<String, String>>() {});
                String text = renderEntryText(categoryCode, e.getTitle(), fields);
                String source = e.getChunkSource();
                if (source == null || source.isBlank()) {
                    source = AdminGuard.chunkSourceForId(e.getId());
                    e.setChunkSource(source);
                    entryMapper.updateById(e);
                }
                knowledgeBaseService.ingestDocument(source, text, true);
            } catch (Exception ex) {
                log.warn("[admin-kb] resync entry id={} failed: {}", e.getId(), ex.getMessage());
            }
        }
    }

    private List<AiKbCategoryMetaVO> loadOrdered(Integer statusFilter) {
        LambdaQueryWrapper<AiKbCategoryDef> q = new LambdaQueryWrapper<>();
        if (statusFilter != null) {
            q.eq(AiKbCategoryDef::getStatus, statusFilter);
        }
        q.orderByAsc(AiKbCategoryDef::getSortOrder).orderByAsc(AiKbCategoryDef::getId);
        return categoryMapper.selectList(q).stream().map(this::toVo).collect(Collectors.toList());
    }

    private AiKbCategoryDef findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return categoryMapper.selectOne(new LambdaQueryWrapper<AiKbCategoryDef>()
            .eq(AiKbCategoryDef::getCode, code.trim().toUpperCase()));
    }

    private AiKbCategoryDef requireById(Long id) {
        if (id == null || id <= 0) {
            throw new GlobalException(400, "id 无效");
        }
        AiKbCategoryDef row = categoryMapper.selectById(id);
        if (row == null) {
            throw new GlobalException(404, "分类不存在");
        }
        return row;
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new GlobalException(400, "code 不能为空");
        }
        String c = code.trim().toUpperCase();
        if (!CODE_PATTERN.matcher(c).matches()) {
            throw new GlobalException(400, "code 须以大写字母开头，仅含大写字母、数字、下划线");
        }
        return c;
    }

    private static List<AiKbCategoryMetaVO.AiKbFieldMetaVO> validateFieldDefs(
        List<AiKbCategoryMetaVO.AiKbFieldMetaVO> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new GlobalException(400, "至少配置一个字段");
        }
        List<AiKbCategoryMetaVO.AiKbFieldMetaVO> out = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (AiKbCategoryMetaVO.AiKbFieldMetaVO f : fields) {
            if (f.getKey() == null || f.getKey().isBlank()) {
                throw new GlobalException(400, "字段 key 不能为空");
            }
            if (f.getLabel() == null || f.getLabel().isBlank()) {
                throw new GlobalException(400, "字段 label 不能为空");
            }
            String key = f.getKey().trim();
            if (!FIELD_KEY_PATTERN.matcher(key).matches()) {
                throw new GlobalException(400, "字段 key 格式无效: " + key);
            }
            if (!keys.add(key)) {
                throw new GlobalException(400, "字段 key 重复: " + key);
            }
            String inputType = f.getInputType() == null ? "text" : f.getInputType().trim();
            if (!List.of("text", "textarea", "url", "select").contains(inputType)) {
                throw new GlobalException(400, "inputType 无效: " + inputType);
            }
            AiKbCategoryMetaVO.AiKbFieldMetaVO n = new AiKbCategoryMetaVO.AiKbFieldMetaVO();
            n.setKey(key);
            n.setLabel(f.getLabel().trim());
            n.setRequired(f.isRequired());
            n.setInputType(inputType);
            n.setOptions(f.getOptions());
            out.add(n);
        }
        return out;
    }

    private AiKbCategoryMetaVO toVo(AiKbCategoryDef row) {
        AiKbCategoryMetaVO vo = new AiKbCategoryMetaVO();
        vo.setId(row.getId());
        vo.setCode(row.getCode());
        vo.setLabel(row.getLabel());
        vo.setStatus(row.getStatus());
        vo.setSortOrder(row.getSortOrder());
        vo.setFields(parseFields(row.getFieldsJson()));
        return vo;
    }

    private static List<AiKbCategoryMetaVO.AiKbFieldMetaVO> parseFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JSON.parseObject(json, new TypeReference<List<AiKbCategoryMetaVO.AiKbFieldMetaVO>>() {});
    }
}
