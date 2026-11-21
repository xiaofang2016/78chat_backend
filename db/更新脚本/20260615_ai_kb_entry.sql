-- AI 知识库结构化条目（运营后台维护，同步向量库 im_ai_kb_chunk）
CREATE TABLE IF NOT EXISTS `im_ai_kb_entry` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category` varchar(32) NOT NULL COMMENT 'ACTIVITY|RECHARGE|WITHDRAW|APP',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `title` varchar(256) NOT NULL DEFAULT '' COMMENT '列表展示标题',
  `fields_json` json NOT NULL COMMENT '分类字段 JSON',
  `chunk_source` varchar(512) NOT NULL DEFAULT '' COMMENT '向量库 source_title，格式 kb-entry-{id}',
  `sort_order` int NOT NULL DEFAULT 0,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chunk_source` (`chunk_source`),
  KEY `idx_category_status` (`category`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI知识库结构化条目';
