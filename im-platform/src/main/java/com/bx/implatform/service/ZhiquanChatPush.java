package com.bx.implatform.service;

import com.bx.imclient.IMClient;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.IMGroupMessage;
import com.bx.imcommon.model.IMUserInfo;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.vo.GroupMessageVO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 抓取写进 zhiquan_chat 的新消息，推到已经打开页面的人。
 * 不走原来的定时任务开关，避免和旧接口一起把过期清理再做一遍。
 * 登录时的最近 100 条仍由离线接口给，这里只推之后出现的。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zhiquan.chat.enabled", havingValue = "true", matchIfMissing = true)
public class ZhiquanChatPush {

    private static final int INTERVAL_MILLIS = 15_000;
    private static final int BATCH = 20;

    private final ZhiquanChatStore store;
    private final IMClient imClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<Long, String> cursor = new ConcurrentHashMap<>();
    private final Map<Long, Long> nextSeq = new ConcurrentHashMap<>();
    private final Thread thread;

    public ZhiquanChatPush(ZhiquanChatStore store, IMClient imClient) {
        this.store = store;
        this.imClient = imClient;
        this.thread = new Thread(this::loop, "zhiquan-chat-push");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        thread.interrupt();
    }

    private void loop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                pushOnce();
            } catch (Exception e) {
                log.warn("推送新聊天失败: {}", e.toString());
            }
            try {
                Thread.sleep(INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pushOnce() {
        for (Map.Entry<Long, String> group : store.groupKeys().entrySet()) {
            Long groupId = group.getKey();
            List<Long> viewers = store.viewers(groupId);
            if (viewers.isEmpty()) {
                if (cursor.containsKey(groupId)) {
                    List<ZhiquanChatStore.Incoming> tip = store.incoming(groupId, group.getValue(), null, 1);
                    if (!tip.isEmpty() && tip.get(0).sortKey() != null) {
                        cursor.put(groupId, tip.get(0).sortKey());
                    }
                }
                continue;
            }
            store.keepViewers(groupId);
            List<ZhiquanChatStore.Incoming> rows = store.incoming(groupId, group.getValue(), cursor.get(groupId), BATCH);
            if (rows.isEmpty()) {
                continue;
            }
            if (rows.size() == 1 && rows.get(0).message() == null) {
                cursor.put(groupId, rows.get(0).sortKey());
                continue;
            }
            for (ZhiquanChatStore.Incoming row : rows) {
                if (row.sortKey() == null || row.sortKey().isBlank()) {
                    continue;
                }
                if (row.message() == null || row.liveDelivered()) {
                    cursor.put(groupId, row.sortKey());
                    continue;
                }
                GroupMessageVO vo = row.message();
                vo.setSeqNo(nextSeq.compute(groupId, (id, old) -> old == null
                    ? (long) store.cachedCount(groupId) + 1 : old + 1));
                if (!MessageStatus.RECALL.code().equals(vo.getStatus())) {
                    vo.setStatus(MessageStatus.PENDING.code());
                }
                send(viewers, vo);
                cursor.put(groupId, row.sortKey());
            }
        }
    }

    private void send(List<Long> viewers, GroupMessageVO vo) {
        Long senderId = vo.getSendId() == null ? 0L : vo.getSendId();
        IMGroupMessage<GroupMessageVO> message = new IMGroupMessage<>();
        message.setSender(new IMUserInfo(senderId, IMTerminalType.WEB.code()));
        message.setRecvIds(viewers);
        message.setSendToSelf(false);
        message.setSendResult(false);
        message.setData(vo);
        imClient.sendGroupMessage(message);
    }
}
