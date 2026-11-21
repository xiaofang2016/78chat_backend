package com.bx.implatform.service;

import com.bx.implatform.vo.AiCsSessionVO;
import com.bx.implatform.vo.PrivateMessageVO;

import java.util.List;
import java.util.Set;

public interface AiCsHandoffService {

    boolean isHandoffEnabled();

    boolean isHumanMode(Long botUserId, Long customerUserId);

    void onUserMessageToBot(Long botUserId, Long customerUserId, Long messageId, String content);

    /** 按钮「接管」期间为 true：交还前不触发 AI（输入发送的临时人工不受影响） */
    boolean shouldSkipAiReply(Long botUserId, Long customerUserId);

    void reportAdminTyping(Long botUserId, Long customerUserId, Long operatorId);

    /** 工作台在线监控心跳（机器人在看板页 / 正在查看某会话） */
    void reportAdminWatching(Long botUserId, Long customerUserId, Long operatorId);

    List<AiCsSessionVO> listSessions(Long botUserId, String filter, int page, int size);

    List<PrivateMessageVO> listMessages(Long botUserId, Long customerUserId, Long beforeId, int limit);

    void takeover(Long botUserId, Long customerUserId, Long operatorId);

    void release(Long botUserId, Long customerUserId, Long operatorId);

    void manualHandoff(Long botUserId, Long customerUserId, Long operatorId);

    Long replyAsBot(Long botUserId, Long customerUserId, Long operatorId, String content, Integer type, String localId);

    void transferToHuman(Long botUserId, Long customerUserId, String reason, Long triggerMsgId);

    /** 该会话中标记为人工代发的 messageId，GPT 上下文须排除 */
    Set<Long> humanMessageIds(Long botUserId, Long customerUserId);

    boolean isAiCsBot(Long botUserId);
}
