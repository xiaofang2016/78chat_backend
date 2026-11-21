package com.bx.implatform.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ai-cs")
public class AiCustomerServiceProperties {

    /** 是否启用 AI 客服自动回复 */
    private boolean enabled = false;

    /** AI 客服主机器人用户 ID（im_user.id），用于"自动回复"逻辑 */
    private Long botUserId = 0L;

    /**
     * 全部 AI 机器人用户 ID 列表（含主机器人）。
     * 出现在该列表中的接收方：① 私聊免好友校验  ② 文本消息会触发 AI 自动回复。
     */
    private List<Long> botUserIds = Collections.emptyList();

    /** 系统提示词 */
    private String systemPrompt = "你是盒子IM的自动客服。用中文回答，简洁、礼貌、一步步引导用户解决问题。";

    private double temperature = 0.2;

    private int maxTokens = 800;

    /** 注入给模型的「当前时间」时区，默认北京时间 UTC+8 */
    private String timezone = "Asia/Shanghai";

    /**
     * 发给 OpenAI 的近期私聊轮数上限（仅 TEXT / 活动卡片）。
     * 0 表示不注入历史（不推荐，追问会丢上下文）。
     */
    private int historyMaxMessages = 12;

    /** 历史消息总字符预算，超出后停止追加更早的消息 */
    private int historyMaxChars = 6000;

    /** Redis 缓存 last_topic 的 TTL（秒），默认 24 小时 */
    private long lastTopicTtlSeconds = 86400L;

    /**
     * 「AI知识库」录入机器人（YAML：{@code ai-cs.knowledge-ingest-bot}）。
     * 必须挂在同一 {@code ai-cs} 树下并配合 {@link NestedConfigurationProperty}，否则单独前缀 Bean 可能绑定失败。
     */
    @NestedConfigurationProperty
    private KnowledgeIngestBot knowledgeIngestBot = new KnowledgeIngestBot();

    /** 人工接管（YAML：{@code ai-cs.handoff}） */
    @NestedConfigurationProperty
    private Handoff handoff = new Handoff();

    @Data
    public static class Handoff {

        private boolean enabled = true;

        /** 管理员输入心跳 Redis TTL（秒） */
        private int adminTypingTtlSeconds = 5;

        /** 管理员停止输入后，再等多久无新输入则发 AI（秒） */
        private int adminTypingQuietSeconds = 2;

        /** 管理员正在输入时，最多等待人工回复的时长（秒）；仅监控在线时生效 */
        private int adminReplyMaxWaitSeconds = 20;

        /** 工作台在线监控心跳 TTL（秒） */
        private int adminWatchTtlSeconds = 12;

        /** 转人工后发给客户的固定话术（仅管理员在线监控且判定需转人工时） */
        private String transferNotice = "您的问题已转接人工客服，请稍候，客服人员将尽快为您回复。";

        /** 知识库相似度偏低且 AI 拒答时（仅管理员监控中）可转人工 */
        private double kbMinScore = 0.72;
    }

    @Data
    public static class KnowledgeIngestBot {

        /** 是否启用：免好友 + 白名单录入 */
        private boolean enabled = false;

        /** 机器人 im_user.id */
        private Long userId = 0L;

        /** 允许录入的用户 id 列表 */
        private List<Long> adminUserIds = Collections.emptyList();
    }
}

