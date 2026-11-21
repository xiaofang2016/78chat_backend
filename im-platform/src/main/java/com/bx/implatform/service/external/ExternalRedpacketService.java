package com.bx.implatform.service.external;

import com.bx.implatform.dto.external.RedpacketPushDTO;
import com.bx.implatform.vo.external.RedpacketPushVO;

/**
 * 红包对外业务（接口①派发；后续接口③④⑤陆续在此扩展）。
 */
public interface ExternalRedpacketService {

    /**
     * 接口①：第三方 → box-im 推送红包派发请求。
     * 幂等：相同 externalId 重复请求 → 参数完全一致返回上次结果，否则抛 40005。
     */
    RedpacketPushVO push(RedpacketPushDTO dto);
}
