-- 用户公开聊天链接（类似 t.me/username）
ALTER TABLE `im_user`
  ADD COLUMN `talk_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否允许陌生人通过链接私聊' AFTER `is_manual_approve`,
  ADD COLUMN `talk_slug` varchar(32) DEFAULT NULL COMMENT '公开聊天链接标识，唯一' AFTER `talk_enabled`;

ALTER TABLE `im_user`
  ADD UNIQUE KEY `uk_talk_slug` (`talk_slug`);
