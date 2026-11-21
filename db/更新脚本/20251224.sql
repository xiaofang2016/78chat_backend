create table `im_message_deletion` (
  `id` bigint not null auto_increment primary key comment 'id',
  `user_id` bigint not null comment '用户id',
  `chat_type` tinyint not null comment '会话类型 1:私聊 2:群聊',
  `chat_id` bigint not null comment '好友id、群聊id',
  `message_id` bigint(20) comment '消息id',
  `delete_type` tinyint not null comment '删除类型 1:按消息删除 2:按会话删除',
  `delete_time` datetime default current_timestamp comment '消息删除时间',
  key `idx_user_id` (`user_id`)
) engine = innodb charset = utf8mb4 comment '消息删除记录';


ALTER TABLE `im_user` ADD COLUMN `unban_time` datetime default null comment '解除封禁时间';

ALTER TABLE `im_group` ADD COLUMN `unban_time` datetime default null comment '解除封禁时间';

create table `im_sticker_custom` (
  `id` bigint(20) not null auto_increment comment '表情id',
  `user_id` bigint(20) not null comment '用户id',
  `album_id` bigint(20) default null comment '专辑id',
  `sticker_id` bigint(20) default null comment '表情id',
  `name` varchar(64) default '' comment '表情名称',
  `image_url` varchar(128) not null comment '表情图片url',
  `thumb_url` varchar(128) not null comment '缩略图url',
  `width` int default null comment '图片宽度',
  `height` int default null comment '图片高度',
  `sort` int default 10000 comment '排序权重,序号小的展示在前面',
  `create_time` datetime default current_timestamp comment '创建时间',
  primary key (`id`),
  key `idx_user_id` (`user_id`)
) engine = innodb default charset = utf8mb4 comment = '用户自定义表情';