package com.bx.implatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bx.implatform.entity.AiKbChunk;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiKbChunkMapper extends BaseMapper<AiKbChunk> {

    @Select("SELECT DISTINCT source_title FROM im_ai_kb_chunk ORDER BY source_title")
    List<String> selectDistinctSourceTitles();
}
