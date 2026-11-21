package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bx.implatform.dto.FriendRemarkDTO;
import com.bx.implatform.entity.ChatPref;
import com.bx.implatform.entity.FavoriteTag;
import com.bx.implatform.entity.Feedback;
import com.bx.implatform.entity.Group;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.entity.MessageFavorite;
import com.bx.implatform.entity.Redpacket;
import com.bx.implatform.entity.RedpacketRecord;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.ChatPrefMapper;
import com.bx.implatform.mapper.FavoriteTagMapper;
import com.bx.implatform.mapper.FeedbackMapper;
import com.bx.implatform.mapper.GroupMapper;
import com.bx.implatform.mapper.MessageFavoriteMapper;
import com.bx.implatform.mapper.RedpacketMapper;
import com.bx.implatform.mapper.RedpacketRecordMapper;
import com.bx.implatform.service.GroupMemberService;
import com.bx.implatform.vo.FriendVO;
import com.bx.implatform.vo.GroupVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MineDeskService {

    public static final String USER = "user";
    public static final String GROUP = "group";

    private static final Set<String> KINDS = Set.of("text", "image", "video", "file", "link");
    private static final int OPEN_LIMIT = 50;

    private final ChatPrefMapper prefs;
    private final MessageFavoriteMapper favorites;
    private final FavoriteTagMapper favoriteTags;
    private final FeedbackMapper feedbacks;
    private final GroupMapper groups;
    private final GroupMemberService groupMembers;
    private final RedpacketMapper redpackets;
    private final RedpacketRecordMapper records;

    public void saveUserCard(Long userId, FriendRemarkDTO dto) {
        if (dto.getFriendId() == null) {
            return;
        }
        ChatPref row = load(userId, USER, dto.getFriendId());
        if (dto.getRemarkNickName() != null) {
            row.setRemark(cut(dto.getRemarkNickName(), 32));
        }
        if (dto.getPhone() != null) {
            row.setPhone(cut(dto.getPhone(), 25));
        }
        if (dto.getDescription() != null) {
            row.setDescription(cut(dto.getDescription(), 200));
        }
        if (dto.getStarred() != null) {
            row.setStarred(dto.getStarred() ? 1 : 0);
        }
        store(row);
    }

    public void saveUserFlag(Long userId, Long peerId, Boolean dnd, Boolean top) {
        if (peerId == null) {
            return;
        }
        ChatPref row = load(userId, USER, peerId);
        if (dnd != null) {
            row.setIsDnd(dnd ? 1 : 0);
        }
        if (top != null) {
            row.setIsTop(top ? 1 : 0);
        }
        store(row);
    }

    public void saveGroupFlag(Long userId, Long groupId, Boolean dnd, Boolean top) {
        if (groupId == null) {
            return;
        }
        ChatPref row = load(userId, GROUP, groupId);
        if (dnd != null) {
            row.setIsDnd(dnd ? 1 : 0);
        }
        if (top != null) {
            row.setIsTop(top ? 1 : 0);
        }
        store(row);
    }

    public void saveGroupAudio(Long userId, Long groupId, Boolean enabled) {
        if (groupId == null || enabled == null) {
            throw new GlobalException("参数不对");
        }
        ChatPref row = load(userId, GROUP, groupId);
        row.setAudioOn(enabled ? 1 : 0);
        store(row);
    }

    public List<Long> dndPeers(Long userId, String kind) {
        LambdaQueryWrapper<ChatPref> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatPref::getUserId, userId);
        wrapper.eq(ChatPref::getKind, kind);
        wrapper.eq(ChatPref::getIsDnd, 1);
        wrapper.select(ChatPref::getPeerId);
        List<Long> ids = new ArrayList<>();
        for (ChatPref row : prefs.selectList(wrapper)) {
            if (row.getPeerId() != null) {
                ids.add(row.getPeerId());
            }
        }
        return ids;
    }

    public Map<String, Object> userMark(Long userId, Long peerId) {
        return markView(find(userId, USER, peerId), peerId);
    }

    public List<Map<String, Object>> userMarks(Long userId) {
        return listMarks(userId, USER);
    }

    /**
     * 群列表和群资料读成员行。原站群往往没有成员行，置顶和免打扰记在偏好里，这里补回去。
     */
    public void paintGroups(Long userId, List<GroupVO> groups) {
        if (userId == null || groups == null || groups.isEmpty()) {
            return;
        }
        Map<Long, ChatPref> flags = flags(userId, GROUP);
        for (GroupVO group : groups) {
            if (group == null || group.getId() == null) {
                continue;
            }
            paint(group, flags.get(group.getId()));
        }
    }

    public void paintFriends(Long userId, List<FriendVO> friends) {
        if (userId == null || friends == null || friends.isEmpty()) {
            return;
        }
        Map<Long, ChatPref> flags = flags(userId, USER);
        for (FriendVO friend : friends) {
            if (friend == null || friend.getId() == null) {
                continue;
            }
            paint(friend, flags.get(friend.getId()));
        }
    }

    static void paint(FriendVO friend, ChatPref row) {
        if (friend == null || row == null) {
            return;
        }
        if (row.getIsTop() != null) {
            friend.setIsTop(row.getIsTop() == 1);
        }
        if (row.getIsDnd() != null) {
            friend.setIsDnd(row.getIsDnd() == 1);
        }
    }

    static void paint(GroupVO group, ChatPref row) {
        if (group == null || row == null) {
            return;
        }
        if (row.getIsTop() != null) {
            group.setIsTop(row.getIsTop() == 1);
        }
        if (row.getIsDnd() != null) {
            group.setIsDnd(row.getIsDnd() == 1);
        }
    }

    private Map<Long, ChatPref> flags(Long userId, String kind) {
        Map<Long, ChatPref> flags = new HashMap<>();
        for (ChatPref row : prefs.selectList(Wrappers.<ChatPref>lambdaQuery()
            .eq(ChatPref::getUserId, userId)
            .eq(ChatPref::getKind, kind))) {
            if (row.getPeerId() != null) {
                flags.put(row.getPeerId(), row);
            }
        }
        return flags;
    }

    public Map<String, Object> groupPref(Long userId, Long groupId) {
        ChatPref row = find(userId, GROUP, groupId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("groupId", groupId);
        view.put("isTop", on(row, ChatPref::getIsTop));
        view.put("isDnd", on(row, ChatPref::getIsDnd));
        view.put("audioOn", row == null || row.getAudioOn() == null || row.getAudioOn() == 1);
        return view;
    }

    public List<Map<String, Object>> groupPrefs(Long userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatPref row : list(userId, GROUP)) {
            rows.add(groupPref(userId, row.getPeerId()));
        }
        return rows;
    }

    public void transfer(Long userId, Long groupId, Long targetId) {
        if (groupId == null || targetId == null) {
            throw new GlobalException("参数不对");
        }
        Group group = groups.selectById(groupId);
        if (group == null || Boolean.TRUE.equals(group.getDissolve())) {
            throw new GlobalException("群不存在");
        }
        if (!userId.equals(group.getOwnerId())) {
            throw new GlobalException("只有群主可以转让");
        }
        if (userId.equals(targetId)) {
            return;
        }
        GroupMember member = groupMembers.findByGroupAndUserId(groupId, targetId);
        if (member == null || Boolean.TRUE.equals(member.getQuit())) {
            throw new GlobalException("对方不在群里");
        }
        group.setOwnerId(targetId);
        groups.updateById(group);
    }

    public Map<String, Object> addFavorite(Long userId, String kind, String content, String title, String url,
                                           String sourceId, List<String> tags) {
        String normalized = kind == null ? "" : kind.trim();
        if (!KINDS.contains(normalized)) {
            throw new GlobalException("收藏类型不对");
        }
        String body = content == null ? "" : content.trim();
        String link = url == null ? "" : url.trim();
        if ("text".equals(normalized) && body.isEmpty()) {
            throw new GlobalException("收藏内容为空");
        }
        if (!"text".equals(normalized) && link.isEmpty()) {
            throw new GlobalException("收藏地址为空");
        }
        String source = sourceId == null || sourceId.isBlank() ? null : cut(sourceId.trim(), 64);
        if (source != null) {
            MessageFavorite existing = favorites.selectOne(Wrappers.<MessageFavorite>lambdaQuery()
                .eq(MessageFavorite::getUserId, userId)
                .eq(MessageFavorite::getSourceId, source)
                .last("limit 1"));
            if (existing != null) {
                if (tags != null) {
                    List<String> cleaned = cleanTags(tags);
                    existing.setTags(writeTags(cleaned));
                    favorites.updateById(existing);
                    rememberTags(userId, cleaned);
                }
                return favoriteView(existing);
            }
        }
        List<String> cleaned = cleanTags(tags);
        MessageFavorite row = new MessageFavorite();
        row.setUserId(userId);
        row.setKind(normalized);
        row.setContent(cut(body, 2000));
        row.setTitle(cut(title == null ? "" : title.trim(), 128));
        row.setUrl(cut(link, 512));
        row.setSourceId(source);
        row.setTags(writeTags(cleaned));
        row.setCreateTime(new Date());
        favorites.insert(row);
        rememberTags(userId, cleaned);
        return favoriteView(row);
    }

    public Map<String, Object> updateFavoriteTags(Long userId, Long id, List<String> tags) {
        MessageFavorite row = favorites.selectById(id);
        if (row == null || !userId.equals(row.getUserId())) {
            throw new GlobalException("收藏不存在");
        }
        List<String> cleaned = cleanTags(tags);
        row.setTags(writeTags(cleaned));
        favorites.updateById(row);
        rememberTags(userId, cleaned);
        return favoriteView(row);
    }

    public List<String> listTags(Long userId) {
        List<FavoriteTag> rows = favoriteTags.selectList(Wrappers.<FavoriteTag>lambdaQuery()
            .eq(FavoriteTag::getUserId, userId)
            .orderByAsc(FavoriteTag::getId)
            .last("limit 100"));
        List<String> names = new ArrayList<>();
        for (FavoriteTag row : rows) {
            if (row.getName() != null && !row.getName().isBlank()) {
                names.add(row.getName());
            }
        }
        return names;
    }

    public List<String> addTag(Long userId, String name) {
        List<String> cleaned = cleanTags(List.of(name == null ? "" : name));
        if (cleaned.isEmpty()) {
            throw new GlobalException("标签为空");
        }
        rememberTags(userId, cleaned);
        return listTags(userId);
    }

    public void removeFavorite(Long userId, Long id) {
        MessageFavorite row = favorites.selectById(id);
        if (row == null || !userId.equals(row.getUserId())) {
            throw new GlobalException("收藏不存在");
        }
        favorites.deleteById(id);
    }

    public List<Map<String, Object>> listFavorites(Long userId) {
        List<MessageFavorite> rows = favorites.selectList(Wrappers.<MessageFavorite>lambdaQuery()
            .eq(MessageFavorite::getUserId, userId)
            .orderByDesc(MessageFavorite::getId)
            .last("limit 100"));
        List<Map<String, Object>> views = new ArrayList<>();
        for (MessageFavorite row : rows) {
            views.add(favoriteView(row));
        }
        return views;
    }

    public Map<String, Object> addFeedback(Long userId, String content, String contact, List<String> images) {
        String body = content == null ? "" : content.trim();
        if (body.isEmpty()) {
            throw new GlobalException("反馈内容为空");
        }
        Feedback row = new Feedback();
        row.setUserId(userId);
        row.setContent(cut(body, 1000));
        row.setContact(cut(contact == null ? "" : contact.trim(), 64));
        row.setImages(joinImages(images));
        row.setCreateTime(new Date());
        feedbacks.insert(row);
        return feedbackView(row);
    }

    public List<Map<String, Object>> openRedpackets(Long userId, Long groupId) {
        if (groupId == null) {
            throw new GlobalException("群id为空");
        }
        Date now = new Date();
        List<Redpacket> rows = redpackets.selectList(Wrappers.<Redpacket>lambdaQuery()
            .eq(Redpacket::getGroupId, groupId)
            .eq(Redpacket::getStatus, 0)
            .and(w -> w.isNull(Redpacket::getExpireAt).or().gt(Redpacket::getExpireAt, now))
            .orderByDesc(Redpacket::getId)
            .last("limit " + OPEN_LIMIT));
        List<Long> ids = new ArrayList<>();
        for (Redpacket row : rows) {
            ids.add(row.getId());
        }
        Set<Long> claimed = claimedBy(userId, ids);
        List<Map<String, Object>> views = new ArrayList<>();
        for (Redpacket row : rows) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.getId());
            view.put("groupId", row.getGroupId());
            view.put("messageId", row.getMessageId());
            view.put("senderId", row.getBotUserId());
            view.put("title", row.getTitle() == null ? "" : row.getTitle());
            view.put("amount", money(row.getAmount()));
            view.put("totalCount", row.getTotalCount() == null ? 0 : row.getTotalCount());
            view.put("claimedCount", row.getClaimedCount() == null ? 0 : row.getClaimedCount());
            view.put("expireAt", row.getExpireAt());
            view.put("claimed", claimed.contains(row.getId()));
            views.add(view);
        }
        return views;
    }

    public Map<String, Object> redpacketSummary(Long userId) {
        List<Redpacket> sent = redpackets.selectList(Wrappers.<Redpacket>lambdaQuery()
            .eq(Redpacket::getBotUserId, userId)
            .ne(Redpacket::getStatus, 3));
        BigDecimal sentAmount = BigDecimal.ZERO;
        for (Redpacket row : sent) {
            if (row.getAmount() != null) {
                sentAmount = sentAmount.add(row.getAmount());
            }
        }
        List<RedpacketRecord> got = records.selectList(Wrappers.<RedpacketRecord>lambdaQuery()
            .eq(RedpacketRecord::getUserId, userId)
            .eq(RedpacketRecord::getStatus, 1));
        BigDecimal gotAmount = BigDecimal.ZERO;
        for (RedpacketRecord row : got) {
            if (row.getAmount() != null) {
                gotAmount = gotAmount.add(row.getAmount());
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sentCount", sent.size());
        view.put("sentAmount", money(sentAmount));
        view.put("receivedCount", got.size());
        view.put("receivedAmount", money(gotAmount));
        return view;
    }

    private Set<Long> claimedBy(Long userId, List<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<RedpacketRecord> rows = records.selectList(Wrappers.<RedpacketRecord>lambdaQuery()
            .eq(RedpacketRecord::getUserId, userId)
            .eq(RedpacketRecord::getStatus, 1)
            .in(RedpacketRecord::getRedpacketId, ids));
        java.util.HashSet<Long> claimed = new java.util.HashSet<>();
        for (RedpacketRecord row : rows) {
            claimed.add(row.getRedpacketId());
        }
        return claimed;
    }

    private List<Map<String, Object>> listMarks(Long userId, String kind) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatPref row : list(userId, kind)) {
            rows.add(markView(row, row.getPeerId()));
        }
        return rows;
    }

    private List<ChatPref> list(Long userId, String kind) {
        return prefs.selectList(Wrappers.<ChatPref>lambdaQuery()
            .eq(ChatPref::getUserId, userId)
            .eq(ChatPref::getKind, kind)
            .orderByDesc(ChatPref::getId)
            .last("limit 200"));
    }

    private Map<String, Object> markView(ChatPref row, Long peerId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("friendId", peerId);
        view.put("remarkNickName", row == null || row.getRemark() == null ? "" : row.getRemark());
        view.put("phone", row == null || row.getPhone() == null ? "" : row.getPhone());
        view.put("description", row == null || row.getDescription() == null ? "" : row.getDescription());
        view.put("starred", on(row, ChatPref::getStarred));
        view.put("isTop", on(row, ChatPref::getIsTop));
        view.put("isDnd", on(row, ChatPref::getIsDnd));
        return view;
    }

    private Map<String, Object> favoriteView(MessageFavorite row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("kind", row.getKind());
        view.put("content", row.getContent() == null ? "" : row.getContent());
        view.put("title", row.getTitle() == null ? "" : row.getTitle());
        view.put("url", row.getUrl() == null ? "" : row.getUrl());
        view.put("sourceId", row.getSourceId() == null ? "" : row.getSourceId());
        view.put("tags", readTags(row.getTags()));
        view.put("createTime", row.getCreateTime());
        return view;
    }

    private List<String> cleanTags(List<String> tags) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (tags == null) {
            return new ArrayList<>();
        }
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String name = tag.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > 16) {
                throw new GlobalException("标签不能大于16字符");
            }
            names.add(name);
            if (names.size() > 20) {
                throw new GlobalException("标签最多20个");
            }
        }
        return new ArrayList<>(names);
    }

    private String writeTags(List<String> tags) {
        return tags == null || tags.isEmpty() ? "" : JSON.toJSONString(tags);
    }

    private List<String> readTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JSONArray array = JSON.parseArray(raw);
            List<String> names = new ArrayList<>();
            if (array == null) {
                return names;
            }
            for (int i = 0; i < array.size(); i++) {
                String name = array.getString(i);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void rememberTags(Long userId, List<String> names) {
        for (String name : names) {
            Long count = favoriteTags.selectCount(Wrappers.<FavoriteTag>lambdaQuery()
                .eq(FavoriteTag::getUserId, userId)
                .eq(FavoriteTag::getName, name));
            if (count != null && count > 0) {
                continue;
            }
            FavoriteTag row = new FavoriteTag();
            row.setUserId(userId);
            row.setName(name);
            row.setCreateTime(new Date());
            favoriteTags.insert(row);
        }
    }

    private Map<String, Object> feedbackView(Feedback row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("content", row.getContent());
        view.put("contact", row.getContact() == null ? "" : row.getContact());
        view.put("images", row.getImages() == null ? "" : row.getImages());
        view.put("createTime", row.getCreateTime());
        return view;
    }

    private ChatPref find(Long userId, String kind, Long peerId) {
        if (peerId == null) {
            return null;
        }
        return prefs.selectOne(Wrappers.<ChatPref>lambdaQuery()
            .eq(ChatPref::getUserId, userId)
            .eq(ChatPref::getKind, kind)
            .eq(ChatPref::getPeerId, peerId)
            .last("limit 1"));
    }

    private ChatPref load(Long userId, String kind, Long peerId) {
        ChatPref row = find(userId, kind, peerId);
        if (row != null) {
            return row;
        }
        row = new ChatPref();
        row.setUserId(userId);
        row.setKind(kind);
        row.setPeerId(peerId);
        row.setStarred(0);
        row.setIsTop(0);
        row.setIsDnd(0);
        row.setAudioOn(1);
        row.setCreateTime(new Date());
        return row;
    }

    private void store(ChatPref row) {
        row.setUpdateTime(new Date());
        if (row.getId() == null) {
            prefs.insert(row);
        } else {
            prefs.updateById(row);
        }
    }

    private static boolean on(ChatPref row, java.util.function.Function<ChatPref, Integer> flag) {
        return row != null && flag.apply(row) != null && flag.apply(row) == 1;
    }

    private static String joinImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        int kept = 0;
        for (String image : images) {
            if (image == null || image.isBlank() || kept >= 3) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(cut(image.trim(), 400));
            kept++;
        }
        return cut(joined.toString(), 1500);
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
