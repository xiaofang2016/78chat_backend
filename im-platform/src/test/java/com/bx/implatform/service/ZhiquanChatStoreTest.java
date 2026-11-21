package com.bx.implatform.service;

import com.bx.implatform.vo.GroupMessageVO;
import com.bx.implatform.vo.GroupVO;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhiquanChatStoreTest {

    @Test
    void mapsGroupMessageAndMeta() {
        Map<String, AttributeValue> group = new HashMap<>();
        group.put("groupId", AttributeValue.fromN("1240"));
        group.put("name", AttributeValue.fromS("世界杯"));
        group.put("ownerId", AttributeValue.fromN("9"));
        group.put("isDnd", AttributeValue.fromBool(true));
        group.put("headImage", AttributeValue.fromS("https://img/a.png"));
        group.put("remarkGroupName", AttributeValue.fromS("备注"));

        GroupVO vo = ZhiquanChatStore.toGroup(group);
        assertEquals(1240L, vo.getId());
        assertEquals("备注", vo.getShowGroupName());
        assertEquals("https://img/a.png", vo.getHeadImageThumb());
        assertFalse(vo.getQuit());
        assertEquals(Boolean.TRUE, vo.getIsDnd());

        Map<String, AttributeValue> message = new HashMap<>();
        message.put("groupId", AttributeValue.fromN("1240"));
        message.put("msgId", AttributeValue.fromN("88"));
        message.put("seqNo", AttributeValue.fromN("3"));
        message.put("sendId", AttributeValue.fromN("7"));
        message.put("sendNickName", AttributeValue.fromS("甲"));
        message.put("type", AttributeValue.fromN("0"));
        message.put("content", AttributeValue.fromS("你好"));
        message.put("sendTime", AttributeValue.fromN("1710000000000"));
        message.put("status", AttributeValue.fromN("2"));

        GroupMessageVO mapped = ZhiquanChatStore.toMessage(message);
        assertEquals(88L, mapped.getId());
        assertEquals(1240L, mapped.getGroupId());
        assertEquals(3L, mapped.getSeqNo());
        assertEquals("你好", mapped.getContent());
        assertEquals(1710000000000L, mapped.getSendTime().getTime());
        assertEquals(Integer.valueOf(2), mapped.getStatus());
        assertEquals("ddb-1240-3", mapped.getLocalId());

        Map<String, AttributeValue> meta = Map.of("groupIds", AttributeValue.fromL(List.of(
            AttributeValue.fromS("1240"),
            AttributeValue.fromN("9"))));
        assertEquals(List.of(1240L, 9L), ZhiquanChatStore.groupIds(meta));

        Map<String, AttributeValue> apkGroup = new HashMap<>();
        apkGroup.put("gid", AttributeValue.fromS("0000000390"));
        apkGroup.put("name", AttributeValue.fromS("红包收米群"));
        Map<String, AttributeValue> raw = new HashMap<>();
        raw.put("g_notice", AttributeValue.fromS("温馨提醒：不要通过微信转账"));
        raw.put("g_notice_show_config", AttributeValue.fromS(
            "{\"allowClose\":1,\"closeReshowHours\":24,\"enabled\":1}"));
        apkGroup.put("raw", AttributeValue.fromM(raw));
        GroupVO apk = ZhiquanChatStore.toGroup(apkGroup);
        assertEquals(390L, apk.getId());
        assertEquals("红包收米群", apk.getShowGroupName());
        assertEquals("温馨提醒：不要通过微信转账", apk.getNotice());
        assertEquals(Boolean.TRUE, apk.getNoticeAllowClose());
        assertEquals(Integer.valueOf(24), apk.getNoticeReshowHours());
        assertEquals(null, apk.getOwnerId());

        raw.put("g_owner_user_uid", AttributeValue.fromS("400315"));
        apkGroup.put("raw", AttributeValue.fromM(raw));
        assertEquals(400315L, ZhiquanChatStore.toGroup(apkGroup).getOwnerId());

        Map<String, AttributeValue> hiddenRaw = new HashMap<>(raw);
        hiddenRaw.put("g_notice_show_config", AttributeValue.fromS("{\"enabled\":0}"));
        apkGroup.put("raw", AttributeValue.fromM(hiddenRaw));
        GroupVO hidden = ZhiquanChatStore.toGroup(apkGroup);
        assertEquals(null, hidden.getNotice());

        Map<String, AttributeValue> apkMessage = new HashMap<>();
        apkMessage.put("gid", AttributeValue.fromS("0000000390"));
        apkMessage.put("time", AttributeValue.fromN("1793492389481"));
        apkMessage.put("nickName", AttributeValue.fromS("幸福"));
        apkMessage.put("sendUid", AttributeValue.fromS("6584908"));
        apkMessage.put("msgType", AttributeValue.fromS("90"));
        apkMessage.put("content", AttributeValue.fromS("在"));
        apkMessage.put("fp", AttributeValue.fromS("2dfa4043ec48f3e8"));
        GroupMessageVO apkMsg = ZhiquanChatStore.toMessage(apkMessage);
        assertEquals(390L, apkMsg.getGroupId());
        assertEquals(1793492389481L, apkMsg.getId());
        assertEquals("幸福", apkMsg.getSendNickName());
        assertEquals(Integer.valueOf(0), apkMsg.getType());
        assertEquals("在", apkMsg.getContent());
        assertTrue(ZhiquanChatStore.isClaimNotice("领取了红包"));
        assertTrue(ZhiquanChatStore.isClaimNotice("我今天真好看已领取了红包"));
        assertFalse(ZhiquanChatStore.isClaimNotice("似有似无，等12/200人已领取了红包"));
        assertEquals("group#8447#msg#0000000000000099#1234567890123456789",
            ZhiquanChatStore.messageSk("8447", 99, "1234567890123456789"));
        assertEquals("4567890123456789", ZhiquanChatStore.legacyLocalTail("1234567890123456789"));

        Map<String, AttributeValue> recalled = new HashMap<>();
        recalled.put("gid", AttributeValue.fromS("8447"));
        recalled.put("time", AttributeValue.fromN("1710000000000"));
        recalled.put("msgType", AttributeValue.fromS("91"));
        recalled.put("content", AttributeValue.fromS("{\"fpForMessage\":\"abc\"}"));
        recalled.put("originContent", AttributeValue.fromS("你好"));
        recalled.put("originType", AttributeValue.fromS("0"));
        recalled.put("nickName", AttributeValue.fromS("甲"));
        recalled.put("sendUid", AttributeValue.fromS("7"));
        GroupMessageVO kept = ZhiquanChatStore.toMessage(recalled);
        assertEquals("你好", kept.getContent());
        assertEquals(Integer.valueOf(0), kept.getType());
    }

    @Test
    void skipsMessagesWhenResumingGroupScan() {
        assertEquals("8447", ZhiquanChatStore.groupIdFromSk("group#8447"));
        assertEquals("8447", ZhiquanChatStore.groupIdFromSk("group#8447#msg#0000000000000099#abc"));
        assertEquals("", ZhiquanChatStore.groupIdFromSk("friend#1"));
        String resume = ZhiquanChatStore.afterGroup("group#8447#msg#0000000000000099#abc");
        assertEquals("group#8447#~", resume);
        assertTrue("group#8447#msg#0000000000000099#abc".compareTo(resume) < 0);
        assertTrue(resume.compareTo("group#84470") < 0);
        assertTrue(resume.compareTo("group#8448") < 0);
    }

    @Test
    void keepsMessageWrittenWhileCacheRefreshes() {
        GroupMessageVO older = message(1L, 1_000L);
        GroupMessageVO loaded = message(2L, 2_000L);
        GroupMessageVO fresh = message(3L, 3_000L);
        List<GroupMessageVO> page = ZhiquanChatStore.mergePage(List.of(older, loaded), List.of(older, fresh), 100);
        assertEquals(List.of(1L, 2L, 3L), page.stream().map(GroupMessageVO::getId).toList());
        assertEquals(List.of(), ZhiquanChatStore.mergePage(List.of(), List.of(fresh), 100));
        List<GroupMessageVO> trimmed = ZhiquanChatStore.mergePage(List.of(loaded), List.of(fresh), 1);
        assertEquals(List.of(3L), trimmed.stream().map(GroupMessageVO::getId).toList());
    }

    private static GroupMessageVO message(Long id, long time) {
        GroupMessageVO vo = new GroupMessageVO();
        vo.setId(id);
        vo.setSendTime(new Date(time));
        return vo;
    }
}
