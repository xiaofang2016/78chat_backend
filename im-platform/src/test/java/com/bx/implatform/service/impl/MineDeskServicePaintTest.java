package com.bx.implatform.service.impl;

import com.bx.implatform.entity.ChatPref;
import com.bx.implatform.vo.FriendVO;
import com.bx.implatform.vo.GroupVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MineDeskServicePaintTest {

    @Test
    void prefFlagsReplaceTheMemberRow() {
        GroupVO group = new GroupVO();
        group.setId(390L);
        group.setIsTop(false);
        group.setIsDnd(false);
        ChatPref row = new ChatPref();
        row.setIsTop(1);
        MineDeskService.paint(group, row);
        assertEquals(Boolean.TRUE, group.getIsTop());
        assertEquals(Boolean.FALSE, group.getIsDnd());
    }

    @Test
    void blankPrefLeavesTheGroupAlone() {
        GroupVO group = new GroupVO();
        group.setIsTop(true);
        group.setIsDnd(false);
        MineDeskService.paint(group, null);
        assertEquals(Boolean.TRUE, group.getIsTop());
        assertEquals(Boolean.FALSE, group.getIsDnd());
    }

    @Test
    void friendPrefReplacesTheFriendRow() {
        FriendVO friend = new FriendVO();
        friend.setIsTop(false);
        friend.setIsDnd(true);
        ChatPref row = new ChatPref();
        row.setIsDnd(0);
        MineDeskService.paint(friend, row);
        assertEquals(Boolean.FALSE, friend.getIsTop());
        assertEquals(Boolean.FALSE, friend.getIsDnd());
    }
}
