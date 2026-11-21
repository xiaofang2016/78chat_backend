package com.bx.implatform.contant;

public final class RedisKey {

    /**
     * 用户状态 无值:空闲  1:正在忙
     */
    public static final String IM_USER_STATE = "im:user:state";

    /**
     * 用户cid
     */
    public static final String IM_USER_CID = "im:user:cid";


    /**
     * 用户语言
     */
    public static final String IM_USER_LOCALE = "im:user:locale";

    /**
     * 验证码
     */
    public static final String IM_CAPTCHA = "im:captcha";

    /**
     * 图形验证码
     */
    public static final String IM_CAPTCHA_IMAGE = "im:captcha:img";

    /**
     * 短信验证码
     */
    public static final String IM_CAPTCHA_SMS = "im:captcha:sms";

    /**
     * 邮箱验证码
     */
    public static final String IM_CAPTCHA_MAIL = "im:captcha:mail";

    /**
     * 私聊会话已读位置(已读最大id)
     */
    public static final String IM_PRIVATE_READED_POSITION = "im:readed:private:position";

    /**
     * 群聊会话已读位置(已读最大id)
     */
    public static final String IM_GROUP_READED_POSITION = "im:readed:group:position";

    /**
     * 私聊会话消息最大id
     */
    public static final String IM_PRIVATE_MESSAGE_MAX_ID = "im:message:private:max_id";

    /**
     * 群聊会话消息最大id
     */
    public static final String IM_GROUP_MESSAGE_MAX_ID = "im:message:group:max_id";

    /**
     * 私聊会话消息最大序列号
     */
    public static final String IM_PRIVATE_MESSAGE_MAX_SEQ = "im:message:private:max_seq";

    /**
     * 群聊会话消息最大id
     */
    public static final String IM_GROUP_MESSAGE_MAX_SEQ = "im:message:group:max_seq";


    /**
     * 已读系统消息位置(已读最大seqNo)
     */
    public static final String IM_SYSTEM_READED_POSITION = "im:readed:system:position";


    /**
     * 离线通知
     */
    public static final String IM_NOTIFY_OFFLINE_SESSION = "im:notify:offline";

    /**
     * 系统消息推送序列号
     */
    public static final String IM_SM_TASK_SEQ = "im:task:sm:seq";
    /**
     * webrtc 单人通话
     */
    public static final String IM_WEBRTC_PRIVATE_SESSION = "im:webrtc:private:session";
    /**
     * webrtc 群通话
     */
    public static final String IM_WEBRTC_GROUP_SESSION = "im:webrtc:group:session";

    /**
     * 用户被封禁消息队列
     */
    public static final String IM_QUEUE_USER_BANNED = "im:queue:user:banned";

    /**
     * 群聊被封禁消息队列
     */
    public static final String IM_QUEUE_GROUP_BANNED = "im:queue:group:banned";

    /**
     * 群聊解封消息队列
     */
    public static final String IM_QUEUE_GROUP_UNBAN = "im:queue:group:unban";

    /**
     * 缓存是否好友：bool
     */
    public static final String IM_CACHE_FRIEND = "im:cache:friend";

    /**
     * 缓存好友免打扰
     */
    public static final String IM_CACHE_FRIEND_DND = "im:cache:friend_dnd";

    /**
     * 缓存群聊信息
     */
    public static final String IM_CACHE_GROUP = "im:cache:group";

    /**
     * 缓存群聊免打扰
     */
    public static final String IM_CACHE_GROUP_DNS = "im:cache:group_dnd";

    /**
     * 缓存是否在黑名单中：bool
     */
    public static final String IM_CACHE_BLACKLIST = "im:cache:blacklist";
    /**
     * 缓存群聊成员id
     */
    public static final String IM_CACHE_GROUP_MEMBER_ID = "im:cache:group_member_ids";
    /**
     * 缓存表情专辑
     */
    public static final String IM_CACHE_STICKER_ALBUMS = "im:cache:sticker:albums";
    /**
     * 缓存表情包
     */
    public static final String IM_CACHE_STICKER_STICKERS = "im:cache:sticker:stickers";

    /**
     * 缓存表情包
     */
    public static final String IM_CACHE_MESSAGE_DELETION = "im:cache:message:deletion";
    /**
     * 分布式锁-群通话
     */
    public static final String IM_LOCK_RTC_GROUP = "im:lock:rtc:group";
    /**
     * 分布式锁-系统消息推送
     */
    public static final String IM_LOCK_SM_TASK = "im:lock:task:sm";

    /**
     * 分布式锁-清理过期文件
     */
    public static final String IM_LOCK_FILE_EXPIRE_TASK = "im:lock:task:file:expire";

    /**
     * 分布式锁-清理无效文件
     */
    public static final String IM_LOCK_FILE_INVALID_TASK = "im:lock:task:file:invalid";

    /**
     * 分布式锁-解除用户封禁任务
     */
    public static final String IM_LOCK_USER_UNBAN_TASK = "im:lock:task:user:unban";

    /**
     * 分布式锁-解除群聊封禁任务
     */
    public static final String IM_LOCK_GROUP_UNBAN_TASK = "im:lock:task:group:unban";

    /**
     * 分布式锁-添加好友
     */
    public static final String IM_LOCK_FRIEND_ADD = "im:lock:friend:add";

    /**
     * 分布式锁-进入群聊
     */
    public static final String IM_LOCK_GROUP_ENTER = "im:lock:group:enter";

    /**
     * 分布式锁-私聊会话消息最大序列号
     */
    public static final String IM_LOCK_PRIVATE_MESSAGE_MAX_SEQ = "im:lock:message:private:max_seq";

    /**
     * 分布式锁-群聊会话消息最大序列号
     */
    public static final String IM_LOCK_GROUP_MESSAGE_MAX_SEQ = "im:lock:message:group:max_seq";


    /**
     * 分布式锁-保存私聊会话消息
     */
    public static final String IM_LOCK_PRIVATE_MESSAGE_SAVE = "im:lock:message:private:save";

    /**
     * 分布式锁-保存群聊会话消息
     */
    public static final String IM_LOCK_GROUP_MESSAGE_SAVE = "im:lock:message:group:save";

    /**
     * 重复提交
     */
    public static final String IM_REPEAT_SUBMIT = "im:repeat:submit";

    /**
     * 登陆二维码
     */
    public static final String IM_LOGIN_QRCODE = "im:login:qrcode";

    /**
     * 群名片token
     */
    public static final String IM_GROUP_CARD_TOKEN = "im:group:card:token";


    /**
     * 群聊加入token
     */
    public static final String IM_GROUP_QRCODE_TOKEN = "im:group:qrcode:token";

    /**
     * 直播间公开群长期加群 token，key 后缀为 groupId
     */
    public static final String IM_GROUP_LIVE_JOIN = "im:group:live:join";

    /**
     * 说球帝播放页公开群 id
     */
    public static final String IM_SPORT_PUBLIC_GROUP = "im:sport:public:group";

    /**
     * 群成员最大版本号
     */
    public static final String IM_GROUP_MEMBER_MAX_VERSION = "im:group:member:max_version";


    /**
     * 好友信息最大版本号
     */
    public static final String IM_FRIEND_MAX_VERSION = "im:friend:max_version";

    /**
     * 用户每日添加好友计数
     */
    public static final String IM_FRIEND_APPLY_COUNT = "im:friend:apply:count";


    /**
     * app是否正在审核中
     */
    public static final String IM_APP_REVIEW = "im:app:review";


    /**
     * 禁止注册
     */
    public static final String IM_REG_DISABLE = "im:reg:disable";

    /**
     * 说球帝账号打开吉祥 / UED 门户的频控。
     * key 后缀: :{userId}，每分钟最多 10 次。
     */
    public static final String IM_SPORT_PORTAL_RATE = "im:sport:portal:rate";

    /**
     * 缓存用户的 aagames access_token，用于 box-im 反向调用 aagames 业务接口（接口③④⑤）
     * key 后缀: :{userId}
     * TTL 与 box-im JWT 同步（30 分钟左右）；JWT 过期则强制重登。
     */
    public static final String IM_AAGAMES_USER_TOKEN = "im:aagames:user:token";

    /**
     * 长寿命副本：与 aagames access_token 自身真实剩余时长对齐，专供 AI 客服 function calling 使用。
     * 与 {@link #IM_AAGAMES_USER_TOKEN} 同值，但 TTL 更长（不被 box-im JWT 30 分钟过期截断）。
     * key 后缀: :{userId}
     */
    public static final String IM_AAGAMES_USER_TOKEN_LT = "im:aagames:user:token:lt";

    /**
     * 缓存用户当日红包额度（接口⑤ 响应体）
     * key 后缀: :{userId}:{yyyy-MM-dd}
     */
    public static final String IM_AAGAMES_USER_QUOTA = "im:aagames:user:quota";

    /**
     * AI 客服私聊会话最近话题（活动/工具上下文），供多轮追问延续语义。
     * key 后缀: :{userId}:{botUserId}
     */
    public static final String IM_AI_CS_LAST_TOPIC = "im:ai-cs:last-topic";

    /**
     * 直播主播是否允许陌生人私聊（bool），由 live-api 查询后缓存。
     * key 后缀: :{userId}
     */
    public static final String IM_CACHE_LIVE_ANCHOR_PM = "im:cache:live_anchor:pm";

    // ---------- 与 aagames_api 共用的 Redis key（database 须一致） ----------

    /** aagames 按 uid 缓存的 token（SSO 后为 IM token；游戏登录后可能为游戏 token）。后缀 :{userId} */
    public static final String AAGAMES_USER_TOKEN_BY_UID = "aagames:user:token";

    /** IM 专用 token 详情，前缀 + token */
    public static final String AAGAMES_IM_ACCESS = "aagames:im:access:";

    /** IM 专用 token 按 uid 索引（与 PHP BoximImTokenService USER_KEY 一致）。后缀 :{userId} */
    public static final String AAGAMES_IM_USER = "aagames:im:user:";

    /** 游戏多会话 token → userId，前缀 + token */
    public static final String AAGAMES_SESSION_TOKEN = "aagames:user:session:token:";

    /** 用户全部有效游戏会话 token 集合，后缀 :{userId} */
    public static final String AAGAMES_SESSION_INDEX = "aagames:user:session:index:";


}
