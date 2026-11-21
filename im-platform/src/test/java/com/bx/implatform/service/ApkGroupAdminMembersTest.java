package com.bx.implatform.service;

import com.bx.implatform.entity.ApkGroupAdmin;
import com.bx.implatform.entity.User;
import com.bx.implatform.vo.GroupMemberVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApkGroupAdminMembersTest {

    @Test
    void ownerComesFirstAndKeepsOriginalName() {
        ApkGroupAdmin owner = row(390L, 400409L, true);
        ApkGroupAdmin other = row(390L, 400173L, false);
        User wind = user(400409L, "UED/吉祥 群管-大风", "https://img/wind.jpg");
        User duo = user(400173L, "群管多米💜UED/吉祥官方助理", "https://img/duo.jpg");

        List<GroupMemberVO> members = ApkGroupAdminMembers.list("红包收米群", List.of(other, owner),
            Map.of(400409L, wind, 400173L, duo));

        assertEquals(List.of(400409L, 400173L), members.stream().map(GroupMemberVO::getUserId).toList());
        assertEquals("UED/吉祥 群管-大风", members.get(0).getShowNickName());
        assertEquals("https://img/wind.jpg", members.get(0).getHeadImage());
        assertEquals(Boolean.TRUE, members.get(0).getIsManager());
        assertEquals(Boolean.FALSE, members.get(0).getQuit());
        assertEquals(400409L, ApkGroupAdminMembers.ownerId(List.of(other, owner)));
    }

    private static ApkGroupAdmin row(long groupId, long userId, boolean owner) {
        ApkGroupAdmin row = new ApkGroupAdmin();
        row.setGroupId(groupId);
        row.setUserId(userId);
        row.setOwner(owner);
        return row;
    }

    private static User user(long id, String nick, String head) {
        User user = new User();
        user.setId(id);
        user.setNickName(nick);
        user.setHeadImageThumb(head);
        return user;
    }
}
