-- AI 知识库：OpenAI Embeddings 向量片段存储（余弦相似度检索，适合中小规模文档）
CREATE TABLE IF NOT EXISTS `im_ai_kb_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_title` varchar(512) NOT NULL DEFAULT '' COMMENT '文档/来源标题',
  `chunk_index` int NOT NULL DEFAULT '0' COMMENT '在同源文档中的序号',
  `chunk_text` mediumtext NOT NULL COMMENT '文本片段',
  `embedding_json` longtext NOT NULL COMMENT 'embedding 向量(JSON float 数组)，与 openai.embedding-model 一致',
  `embedding_dims` int NOT NULL DEFAULT '0' COMMENT '向量维度',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_source_title` (`source_title`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 知识库片段与向量';
