package com.bx.implatform.service;

import com.bx.implatform.dto.LuckyBonusApplyDTO;
import com.bx.implatform.vo.LuckyBonusApplyResultVO;

public interface LuckyBonusService {

    /**
     * 用户点击活动领取按钮：校验资格 → 发红包或（送彩票）返回游戏 URL。
     */
    LuckyBonusApplyResultVO apply(Long userId, LuckyBonusApplyDTO dto);
}
