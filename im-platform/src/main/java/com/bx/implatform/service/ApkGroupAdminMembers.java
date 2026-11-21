package com.bx.implatform.service;

import cn.hutool.core.util.StrUtil;
import com.bx.implatform.entity.ApkGroupAdmin;
import com.bx.implatform.entity.User;
import com.bx.implatform.vo.GroupMemberVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 原站群管理员在成员列表里的样子。群主排在前面，其余管理员按编号。
 */
public final class ApkGroupAdminMembers {

    private ApkGroupAdminMembers() {
    }

    public static List<GroupMemberVO> list(String groupName, List<ApkGroupAdmin> rows, Map<Long, User> users) {
        List<GroupMemberVO> vos = new ArrayList<>();
        if (rows == null || users == null) {
            return vos;
        }
        String name = groupName == null ? "" : groupName;
        for (ApkGroupAdmin row : rows) {
            if (row == null || row.getUserId() == null) {
                continue;
            }
            User user = users.get(row.getUserId());
            if (user == null || StrUtil.isBlank(user.getNickName())) {
                continue;
            }
            GroupMemberVO vo = new GroupMemberVO();
            vo.setUserId(user.getId());
            vo.setShowNickName(user.getNickName());
            vo.setRemarkNickName("");
            String head = StrUtil.blankToDefault(user.getHeadImageThumb(), user.getHeadImage());
            vo.setHeadImage(head == null ? "" : head);
            vo.setCompanyName("");
            vo.setIsManager(true);
            vo.setIsMuted(false);
            vo.setQuit(false);
            vo.setShowGroupName(name);
            vo.setRemarkGroupName("");
            vo.setVersion(0L);
            vos.add(vo);
        }
        vos.sort(Comparator
            .comparing((GroupMemberVO vo) -> !isOwner(vo.getUserId(), rows))
            .thenComparing(GroupMemberVO::getUserId, Comparator.nullsLast(Long::compareTo)));
        return vos;
    }

    public static Long ownerId(List<ApkGroupAdmin> rows) {
        if (rows == null) {
            return null;
        }
        Long owner = null;
        for (ApkGroupAdmin row : rows) {
            if (row != null && Boolean.TRUE.equals(row.getOwner()) && row.getUserId() != null) {
                owner = row.getUserId();
            }
        }
        return owner;
    }

    private static boolean isOwner(Long userId, List<ApkGroupAdmin> rows) {
        for (ApkGroupAdmin row : rows) {
            if (row != null && userId.equals(row.getUserId()) && Boolean.TRUE.equals(row.getOwner())) {
                return true;
            }
        }
        return false;
    }
}
