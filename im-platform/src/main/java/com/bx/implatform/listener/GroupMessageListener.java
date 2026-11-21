package com.bx.implatform.listener;

import com.bx.imclient.annotation.IMListener;
import com.bx.imclient.listener.MessageListener;
import com.bx.imcommon.enums.IMListenerType;
import com.bx.imcommon.enums.IMSendCode;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.IMSendResult;
import com.bx.imcommon.util.ThreadPoolExecutorFactory;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.service.OfflineNotifyService;
import com.bx.implatform.vo.GroupMessageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Slf4j
@IMListener(type = IMListenerType.GROUP_MESSAGE)
public class GroupMessageListener implements MessageListener<GroupMessageVO> {

    @Lazy
    @Autowired
    private OfflineNotifyService offlineNotifyService;

    private final ScheduledThreadPoolExecutor EXECUTOR = ThreadPoolExecutorFactory.getThreadPoolExecutor();

    @Override
    public void process(List<IMSendResult<GroupMessageVO>> results) {
        // 推送离线通知
        EXECUTOR.execute(() -> sendOfflineNotify(results));
    }

    private void sendOfflineNotify(List<IMSendResult<GroupMessageVO>> results) {
        // 针对APP离线用户进行离线通知
        List<IMSendResult<GroupMessageVO>> notifyResults = new ArrayList<>(results.size());
        for (IMSendResult<GroupMessageVO> result : results) {
            MessageType messageType = MessageType.fromCode(result.getData().getType());
            if (result.getCode().equals(IMSendCode.NOT_ONLINE.code()) && result.getReceiver().getTerminal()
                .equals(IMTerminalType.APP.code()) && messageType.isNormal()) {
                // 积压超过2分钟，说明系统很繁忙，就不进行推送了
                if (System.currentTimeMillis() - result.getData().getSendTime().getTime()  < 2 * 60 * 1000) {
                    notifyResults.add(result);
                }
            }
        }
        // 推送
        if (!notifyResults.isEmpty()) {
            offlineNotifyService.sendGroupOfflineNotify(notifyResults);
        }
    }
}
