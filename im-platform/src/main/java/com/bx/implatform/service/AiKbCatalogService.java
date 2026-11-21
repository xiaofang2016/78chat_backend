package com.bx.implatform.service;

import com.bx.implatform.vo.AiKbCategoryPublicVO;
import com.bx.implatform.vo.AiKbEntryPublicVO;
import com.bx.implatform.vo.PageVO;

import java.util.List;

public interface AiKbCatalogService {

    List<AiKbCategoryPublicVO> listEnabledCategories();

    PageVO<AiKbEntryPublicVO> pageEnabledEntries(String category, String keyword, int page, int size);

    AiKbEntryPublicVO getEnabledEntry(Long id);
}
