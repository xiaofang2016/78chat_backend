package com.bx.implatform.service;

import com.bx.implatform.dto.AiKbCategoryCreateDTO;
import com.bx.implatform.dto.AiKbCategoryUpdateDTO;
import com.bx.implatform.entity.AiKbCategoryDef;
import com.bx.implatform.vo.AiKbCategoryMetaVO;

import java.util.List;
import java.util.Map;

public interface AiKbCategoryService {

    List<AiKbCategoryMetaVO> listAll();

    /** 仅启用分类，供条目编辑与侧栏菜单 */
    List<AiKbCategoryMetaVO> listEnabled();

    AiKbCategoryMetaVO getByCode(String code);

    AiKbCategoryMetaVO getById(Long id);

    AiKbCategoryDef requireEnabledByCode(String code);

    Long create(AiKbCategoryCreateDTO dto);

    void update(Long id, AiKbCategoryUpdateDTO dto);

    void delete(Long id);

    void validateFields(String categoryCode, Map<String, String> fields);

    String renderEntryText(String categoryCode, String title, Map<String, String> fields);
}
