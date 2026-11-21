package com.bx.implatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bx.implatform.dto.ChatDeleteDTO;
import com.bx.implatform.dto.GroupMessageDTO;
import com.bx.implatform.dto.GroupMessageHistoryDTO;
import com.bx.implatform.dto.MessageDeleteDTO;
import com.bx.implatform.entity.GroupMessage;
import com.bx.implatform.vo.GroupMessageVO;

import java.util.List;

public interface GroupMessageService extends IService<GroupMessage> {

    /**
     * 发送群聊消息(高并发接口，查询mysql接口都要进行缓存)
     *
     * @param dto 群聊消息
     * @return 群聊id
     */
    GroupMessageVO sendMessage(GroupMessageDTO dto);

    /**
     * 撤回消息
     *
     * @param id 消息id
     */
    GroupMessageVO recallMessage(Long id);

    /**
     * 拉取离线消息，只能拉取最近1个月的消息
     *
     * @param minId 消息起始id
     */
    List<GroupMessageVO> loadOffineMessage(Long minId);


    /**
     * 消息已读,同步其他终端，清空未读数量
     *
     * @param groupId   群聊
     * @param messageId 消息id
     */
    void readedMessage(Long groupId, Long messageId);

    /**
     * 查询群里消息已读用户id列表
     *
     * @param groupId   群里id
     * @param messageId 消息id
     * @return 已读用户id集合
     */
    List<Long> findReadedUsers(Long groupId, Long messageId);

    /**
     * 删除消息
     *
     * @param dto
     */
    void deleteMessage(MessageDeleteDTO dto);

    /**
     * 删除会话
     *
     * @param dto dto
     */
    void deleteChat(ChatDeleteDTO dto);



    /**
     * 保存消息
     * @param message
     */
    void saveMessage(GroupMessage message);

    /**
     * 拉取历史消息
     *
     * @param dto dto
     */
    List<GroupMessageVO> loadHistoryMessage(GroupMessageHistoryDTO dto);

    /**
     * 直播间游客只读：最近文字类消息
     */
    List<GroupMessageVO> loadGuestMessages(Long groupId, int limit);

    /**
     * 直播间系统推广文案（live-api 定时推送）
     */
    GroupMessageVO sendLivePromoMessage(Long groupId, String contentHtml);

    /**
     * 直播间进出提示（欢迎/离开）
     */
    GroupMessageVO sendLivePresenceMessage(Long groupId, String nick, String action, Integer viewerCount);

    /**
     * 直播间游客发言（展示 guestNick，不走 IM 登录）
     */
    GroupMessageVO sendLiveGuestMessage(Long groupId, String guestNick, String content);

    /**
     * 共享群里已经落库的消息再写入 zhiquan_chat，并推给正在看的人。
     *
     * @param vo 已保存的群消息
     */
    void publishToShared(GroupMessageVO vo);

    /**
     * 在 zhiquan_chat 的群里记一条领取红包的文字。
     *
     * @param groupId 群 id
     * @param userId 领取人
     * @param nick 领取人昵称
     */
    void recordRedpacketClaim(Long groupId, Long userId, String nick);

}
