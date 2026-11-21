package com.bx.implatform.service;

import com.bx.implatform.dto.AiKbEntryCreateDTO;
import com.bx.implatform.dto.AiKbEntryUpdateDTO;
import com.bx.implatform.vo.AiKbEntryVO;
import com.bx.implatform.vo.PageVO;

public interface AiKbEntryService {

    PageVO<AiKbEntryVO> page(String category, Integer status, String keyword, int page, int size);

    AiKbEntryVO detail(Long id);

    Long create(AiKbEntryCreateDTO dto, Long operatorId);

    void update(Long id, AiKbEntryUpdateDTO dto, Long operatorId);

    void enable(Long id, Long operatorId);

    void disable(Long id, Long operatorId);

    void delete(Long id, Long operatorId);
}
