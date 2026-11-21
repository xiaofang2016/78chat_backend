package com.bx.implatform.service;

import com.bx.implatform.dto.RedpacketSendDTO;
import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.external.RedpacketRecordsVO;
import com.bx.implatform.vo.external.UserDailyQuotaVO;
import com.bx.implatform.vo.redpacket.RedpacketClaimResultVO;
import com.bx.implatform.vo.redpacket.RedpacketDetailVO;

/**
 * 用户视角的红包业务（前端 → box-im → aagames），见《红包与晒图对接接口规范-v1.1》§4.3 / §4.4 / §4.5。
 */
public interface UserRedpacketService {

    /**
     * 用户领取红包：调用 aagames 接口③，落本地流水，并在领完时重发卡片刷新状态。
     */
    /**
     * 群成员发红包。普通人、群主、管理员都可以发。金额从发红包的人自己的商户钱包扣。
     */
    GroupMessageVO send(Long userId, RedpacketSendDTO dto);

    RedpacketClaimResultVO claim(Long userId, Long redpacketId);

    /**
     * 拉取红包详情：调用 aagames 接口④，与本地 redpacket 主表合并。
     */
    RedpacketDetailVO records(Long userId, Long redpacketId);

    /**
     * 查询用户当日额度：直接转调 aagames 接口⑤（box-im 端 60 秒缓存）。
     */
    UserDailyQuotaVO quota(Long userId, String date);

    /**
     * 工具：把 aagames RedpacketRecordsVO 转成前端展示用 detail。
     */
    RedpacketDetailVO toDetail(Long redpacketId, RedpacketRecordsVO remote);
}
