-- 私聊正文扩容：支持「AI知识库」机器人单条约 50KB UTF-8（及部分多字节场景）
ALTER TABLE `im_private_message` MODIFY COLUMN `content` MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '发送内容';
