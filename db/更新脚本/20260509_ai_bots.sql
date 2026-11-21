-- v1.1：AI 客服机器人用户（小红 / 小绿）
-- 执行：mysql -uroot -p123456 im_platform < db/更新脚本/20260509_ai_bots.sql
--
-- 说明：
-- - 这里使用固定 ID，避免在不同环境下自增 ID 不一致导致前端/配置引用错位
-- - 如你的环境已占用这些 ID，可自行换成更大的未占用值，并同步更新 application-*.yml / im-web 的 env

SET @BOT_XIAOHONG_ID = 900001;
SET @BOT_XIAOLV_ID  = 900002;

INSERT INTO `im_user` (`id`, `user_name`, `nick_name`, `password`, `sex`, `type`, `signature`, `status`)
VALUES
(@BOT_XIAOHONG_ID, 'bot_xiaohong', '小红', 'bot_disabled_login', 0, 1, '我是小红（机器人）', 0),
(@BOT_XIAOLV_ID,  'bot_xiaolv',  '小绿', 'bot_disabled_login', 0, 1, '我是小绿（机器人）', 0)
ON DUPLICATE KEY UPDATE
  `user_name` = VALUES(`user_name`),
  `nick_name` = VALUES(`nick_name`),
  `signature` = VALUES(`signature`),
  `status` = VALUES(`status`);

