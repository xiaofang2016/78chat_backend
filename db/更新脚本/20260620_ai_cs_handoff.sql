-- AI 客服人工接管：会话状态 + 人工代发消息标记（人工消息不进入 GPT 上下文）
-- 执行：mysql -uroot -p im_platform < db/更新脚本/20260620_ai_cs_handoff.sql

CREATE TABLE IF NOT EXISTS `im_ai_cs_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bot_user_id` bigint NOT NULL COMMENT 'AI 客服机器人 im_user.id',
  `customer_user_id` bigint NOT NULL COMMENT '客户 im_user.id',
  `mode` tinyint NOT NULL DEFAULT 0 COMMENT '0=AI自动 1=人工',
  `operator_id` bigint DEFAULT NULL COMMENT '当前接管管理员 im_user.id',
  `handoff_reason` varchar(64) DEFAULT NULL COMMENT 'keyword/auto_no_answer/manual',
  `last_user_msg_id` bigint DEFAULT NULL,
  `last_msg_at` datetime DEFAULT NULL,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bot_customer` (`bot_user_id`, `customer_user_id`),
  KEY `idx_bot_mode_time` (`bot_user_id`, `mode`, `last_msg_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI客服会话接管状态';

CREATE TABLE IF NOT EXISTS `im_ai_cs_human_message` (
  `message_id` bigint NOT NULL COMMENT 'im_private_message.id，管理员代发',
  `bot_user_id` bigint NOT NULL,
  `customer_user_id` bigint NOT NULL,
  `operator_id` bigint NOT NULL,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`message_id`),
  KEY `idx_bot_customer` (`bot_user_id`, `customer_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工代发消息（不喂给GPT）';
