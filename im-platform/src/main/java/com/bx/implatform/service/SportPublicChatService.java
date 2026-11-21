package com.bx.implatform.service;

import com.bx.implatform.session.UserSession;
import com.bx.implatform.vo.SportChatSessionVO;

public interface SportPublicChatService {

    /**
     * 进入说球帝体育聊天室。传了 matchId 时，每场比赛一个群；不传则进总群「说球帝」。
     */
    SportChatSessionVO enter(UserSession session, Long matchId);
}
