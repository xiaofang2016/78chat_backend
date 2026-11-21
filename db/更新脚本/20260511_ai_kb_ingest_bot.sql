-- 「AI知识库」录入专用机器人（私聊发文本 → OpenAI Embedding 入库）
-- 执行：mysql -uroot -p im_platform < db/更新脚本/20260511_ai_kb_ingest_bot.sql
--
-- ID 与 ai-cs.knowledge-ingest-bot.user-id 对齐（默认 900003）；若占用请改 SQL + yml + 前端 env

SET @BOT_KB_ID = 900003;

INSERT INTO `im_user` (`id`, `user_name`, `nick_name`, `password`, `sex`, `type`, `signature`, `status`)
VALUES (
  @BOT_KB_ID,
  'bot_ai_kb',
  'AI知识库',
  'bot_disabled_login',
  0,
  1,
  '向我发送文章正文即可录入知识库（仅管理员账号可用）',
  0
)
ON DUPLICATE KEY UPDATE
  `user_name` = VALUES(`user_name`),
  `nick_name` = VALUES(`nick_name`),
  `signature` = VALUES(`signature`),
  `status` = VALUES(`status`);
