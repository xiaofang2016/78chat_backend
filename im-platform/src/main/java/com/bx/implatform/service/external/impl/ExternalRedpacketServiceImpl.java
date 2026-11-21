package com.bx.implatform.service.external.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bx.implatform.config.props.BoximIntegrationProperties;
import com.bx.implatform.dto.external.RedpacketPushDTO;
import com.bx.implatform.entity.Group;
import com.bx.implatform.entity.Redpacket;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.RedpacketMapper;
import com.bx.implatform.service.GroupService;
import com.bx.implatform.service.RedpacketBotService;
import com.bx.implatform.service.external.ExternalRedpacketService;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.external.RedpacketPushVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.Objects;

/**
 * 红包对外业务实现：当前仅含接口①。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalRedpacketServiceImpl implements ExternalRedpacketService {

    private static final int DEFAULT_EXPIRE_MINUTES = 10;
    private static final String DEFAULT_TITLE = "恭喜发财，大吉大利";
    private static final String DEFAULT_RULE = "any";

    /** 业务码：externalId 已存在但参数不一致 */
    private static final int CODE_PARAM_CONFLICT = 40005;

    private final BoximIntegrationProperties props;
    private final RedpacketMapper redpacketMapper;
    private final GroupService groupService;
    private final RedpacketBotService redpacketBotService;

    @Override
    @Transactional
    public RedpacketPushVO push(RedpacketPushDTO dto) {
        // 1. 解析目标群
        Long groupId = dto.getGroupId();
        if (groupId == null || groupId <= 0) {
            Long defaultGroup = props.getRedpacketBot() != null ? props.getRedpacketBot().getDefaultGroupId() : null;
            if (defaultGroup == null || defaultGroup <= 0) {
                throw new GlobalException(40004, "groupId 未传且未配置 boxim.redpacket-bot.default-group-id");
            }
            groupId = defaultGroup;
        }
        Group group = groupService.getById(groupId);
        if (group == null || Boolean.TRUE.equals(group.getDissolve())) {
            throw new GlobalException(40004, "目标群不存在: " + groupId);
        }
        if (Boolean.TRUE.equals(group.getIsBanned())) {
            throw new GlobalException(40004, "目标群已被封禁: " + groupId);
        }

        // 2. 幂等检查：通过 external_id 查询
        Redpacket existing = redpacketMapper.selectOne(new LambdaQueryWrapper<Redpacket>()
            .eq(Redpacket::getExternalId, dto.getExternalId()));
        if (existing != null) {
            if (!isSameRequest(existing, dto, groupId)) {
                throw new GlobalException(CODE_PARAM_CONFLICT, "externalId 已存在但参数不一致");
            }
            return buildVO(existing);
        }

        // 3. 落库 + 派发
        Redpacket rp = new Redpacket();
        rp.setExternalId(dto.getExternalId());
        rp.setGroupId(groupId);
        rp.setBotUserId(props.getRedpacketBot().getUserId());
        rp.setAmount(dto.getAmount().setScale(2, RoundingMode.HALF_UP));
        rp.setTotalCount(dto.getTotalCount());
        rp.setClaimedCount(0);
        rp.setTitle(dto.getTitle() == null || dto.getTitle().isEmpty() ? DEFAULT_TITLE : dto.getTitle());
        rp.setEligibilityRule(dto.getEligibilityRule() == null || dto.getEligibilityRule().isEmpty()
            ? DEFAULT_RULE : dto.getEligibilityRule());
        rp.setExtra(dto.getExtra() == null ? null : dto.getExtra().toJSONString());
        rp.setStatus(0);
        long now = System.currentTimeMillis();
        int expireMinutes = dto.getExpireMinutes() == null ? DEFAULT_EXPIRE_MINUTES : dto.getExpireMinutes();
        rp.setExpireAt(new Date(now + expireMinutes * 60_000L));
        rp.setCreatedAt(new Date(now));
        rp.setUpdatedAt(new Date(now));
        try {
            redpacketMapper.insert(rp);
        } catch (DuplicateKeyException e) {
            // 并发场景：另一并发请求刚刚 insert 成功，重新读取做幂等比较
            Redpacket race = redpacketMapper.selectOne(new LambdaQueryWrapper<Redpacket>()
                .eq(Redpacket::getExternalId, dto.getExternalId()));
            if (race != null && isSameRequest(race, dto, groupId)) {
                return buildVO(race);
            }
            throw new GlobalException(CODE_PARAM_CONFLICT, "externalId 已存在但参数不一致");
        }

        // 4. 调机器人发卡片消息
        GroupMessageVO msg = redpacketBotService.dispatchRedpacket(group, rp);

        // 5. 回填 message_id
        rp.setMessageId(msg.getId());
        rp.setUpdatedAt(new Date());
        redpacketMapper.updateById(rp);

        log.info("[redpacket-push] groupId={}, externalId={}, redpacketId={}, messageId={}",
            groupId, dto.getExternalId(), rp.getId(), msg.getId());
        return buildVO(rp);
    }

    private boolean isSameRequest(Redpacket existing, RedpacketPushDTO dto, Long groupId) {
        if (!Objects.equals(existing.getGroupId(), groupId)) return false;
        BigDecimal a = existing.getAmount() == null ? BigDecimal.ZERO : existing.getAmount();
        BigDecimal b = dto.getAmount() == null ? BigDecimal.ZERO : dto.getAmount();
        if (a.compareTo(b.setScale(2, RoundingMode.HALF_UP)) != 0) return false;
        if (!Objects.equals(existing.getTotalCount(), dto.getTotalCount())) return false;
        String existingExtra = existing.getExtra() == null ? "" : existing.getExtra();
        String dtoExtra = dto.getExtra() == null ? "" : dto.getExtra().toJSONString();
        if (!Objects.equals(existingExtra, dtoExtra)) {
            // 容错：fastjson 同对象不同字段顺序导致字符串不一致 → 解析后比较
            JSONObject e1 = existingExtra.isEmpty() ? new JSONObject() : JSON.parseObject(existingExtra);
            JSONObject e2 = dtoExtra.isEmpty() ? new JSONObject() : JSON.parseObject(dtoExtra);
            if (!Objects.equals(e1, e2)) return false;
        }
        return true;
    }

    private RedpacketPushVO buildVO(Redpacket rp) {
        RedpacketPushVO vo = new RedpacketPushVO();
        vo.setRedpacketId(String.valueOf(rp.getId()));
        vo.setMessageId(rp.getMessageId() == null ? null : String.valueOf(rp.getMessageId()));
        vo.setGroupId(String.valueOf(rp.getGroupId()));
        vo.setExpireAt(rp.getExpireAt() == null ? null : rp.getExpireAt().getTime());
        vo.setCreatedAt(rp.getCreatedAt() == null ? null : rp.getCreatedAt().getTime());
        return vo;
    }
}
