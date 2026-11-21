package com.bx.implatform.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bx.implatform.entity.Redpacket;
import com.bx.implatform.entity.RedpacketRecord;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.mapper.RedpacketMapper;
import com.bx.implatform.mapper.RedpacketRecordMapper;
import com.bx.implatform.util.LuckyBonusExternalIdResolver;
import com.bx.implatform.vo.PrivateMessageVO;
import com.bx.implatform.vo.ai.LuckyBonusApplyCardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拉取历史私聊消息时，为活动领取卡片回填状态：
 * applied = 已申请（im_redpacket 已生成，待开红包）；
 * claimed = 已领取（im_redpacket_record 入账成功）。
 */
@Component
@RequiredArgsConstructor
public class LuckyBonusApplyCardEnricher {

    private static final int RECORD_STATUS_SUCCESS = 1;

    private final RedpacketMapper redpacketMapper;
    private final RedpacketRecordMapper redpacketRecordMapper;

    public void enrich(Long viewerUserId, List<PrivateMessageVO> messages) {
        if (viewerUserId == null || messages == null || messages.isEmpty()) {
            return;
        }
        Map<Integer, String> indexToExternalId = new HashMap<>();
        Set<String> externalIds = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            PrivateMessageVO vo = messages.get(i);
            if (vo == null || !MessageType.LUCKY_BONUS_APPLY.code().equals(vo.getType())) {
                continue;
            }
            if (StrUtil.isBlank(vo.getContent())) {
                continue;
            }
            JSONObject card;
            try {
                card = JSON.parseObject(vo.getContent());
            } catch (Exception e) {
                continue;
            }
            if (card == null) {
                continue;
            }
            if (Boolean.TRUE.equals(card.getBoolean("claimed"))
                && Boolean.TRUE.equals(card.getBoolean("applied"))) {
                continue;
            }
            String externalId = LuckyBonusExternalIdResolver.resolve(card, viewerUserId);
            if (externalId == null) {
                continue;
            }
            indexToExternalId.put(i, externalId);
            externalIds.add(externalId);
        }
        if (externalIds.isEmpty()) {
            return;
        }

        LambdaQueryWrapper<Redpacket> rpWrapper = Wrappers.lambdaQuery();
        rpWrapper.in(Redpacket::getExternalId, externalIds);
        List<Redpacket> redpackets = redpacketMapper.selectList(rpWrapper);
        if (redpackets == null || redpackets.isEmpty()) {
            return;
        }

        Map<String, Long> externalToRedpacketId = new HashMap<>();
        Set<Long> redpacketIds = new HashSet<>();
        for (Redpacket rp : redpackets) {
            if (rp == null || rp.getExternalId() == null || rp.getId() == null) {
                continue;
            }
            if (!belongsToViewer(rp, viewerUserId)) {
                continue;
            }
            externalToRedpacketId.put(rp.getExternalId(), rp.getId());
            redpacketIds.add(rp.getId());
        }
        if (externalToRedpacketId.isEmpty()) {
            return;
        }

        Set<Long> walletClaimedRedpacketIds = new HashSet<>();
        if (!redpacketIds.isEmpty()) {
            LambdaQueryWrapper<RedpacketRecord> recWrapper = Wrappers.lambdaQuery();
            recWrapper.in(RedpacketRecord::getRedpacketId, redpacketIds);
            recWrapper.eq(RedpacketRecord::getUserId, viewerUserId);
            recWrapper.eq(RedpacketRecord::getStatus, RECORD_STATUS_SUCCESS);
            recWrapper.select(RedpacketRecord::getRedpacketId);
            List<RedpacketRecord> records = redpacketRecordMapper.selectList(recWrapper);
            for (RedpacketRecord rec : records) {
                if (rec != null && rec.getRedpacketId() != null) {
                    walletClaimedRedpacketIds.add(rec.getRedpacketId());
                }
            }
        }

        indexToExternalId.forEach((index, externalId) -> {
            Long redpacketId = externalToRedpacketId.get(externalId);
            if (redpacketId == null) {
                return;
            }
            PrivateMessageVO vo = messages.get(index);
            LuckyBonusApplyCardVO card = JSON.parseObject(vo.getContent(), LuckyBonusApplyCardVO.class);
            if (card == null) {
                return;
            }
            card.setApplied(true);
            if (walletClaimedRedpacketIds.contains(redpacketId)) {
                card.setClaimed(true);
            }
            vo.setContent(JSON.toJSONString(card));
        });
    }

    private static boolean belongsToViewer(Redpacket rp, Long viewerUserId) {
        if (rp == null || viewerUserId == null) {
            return false;
        }
        if (StrUtil.isBlank(rp.getExtra())) {
            return true;
        }
        try {
            JSONObject extra = JSON.parseObject(rp.getExtra());
            if (extra == null) {
                return true;
            }
            if (!"private".equals(extra.getString("scope"))) {
                return true;
            }
            Long targetUserId = extra.getLong("targetUserId");
            return targetUserId == null || viewerUserId.equals(targetUserId);
        } catch (Exception e) {
            return true;
        }
    }
}
