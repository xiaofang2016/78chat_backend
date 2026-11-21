ALTER TABLE `im_private_message` add index `idx_send_recv_id` (`send_id`,`recv_id`);


CREATE TABLE `im_sticker_album` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '专辑ID',
  `name` VARCHAR(100) NOT NULL COMMENT '专辑名称',
  `logo_url` VARCHAR(128) NOT NULL COMMENT '专辑logo',
  `sort` INT DEFAULT 0 COMMENT '排序权重',
  `status` TINYINT DEFAULT 1 COMMENT '状态 0:下架 1:上架',
  `description` VARCHAR(1024) COMMENT '专辑描述',
  `deleted` TINYINT DEFAULT 0 COMMENT '删除标识  0：正常   1：已删除',
  `creator` BIGINT COMMENT '创建者',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表情包专辑';


CREATE TABLE `im_sticker` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '表情ID',
  `album_id` BIGINT(20) NOT NULL COMMENT '专辑ID',
  `name` VARCHAR(64) NOT NULL COMMENT '表情名称',
  `image_url` varchar(128) NOT NULL COMMENT '表情图片URL',
  `thumb_url` varchar(128) DEFAULT NULL COMMENT '缩略图URL',
  `width` int DEFAULT NULL COMMENT '图片宽度',
  `height` int DEFAULT NULL COMMENT '图片高度',
  `sort` int DEFAULT 0 COMMENT '排序权重',
  `deleted` TINYINT DEFAULT 0 COMMENT '删除标识  0：正常   1：已删除',
  `creator` BIGINT COMMENT '创建者',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_album_id` (`album_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表情包';


CREATE TABLE `im_realname_auth` (
	`id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
	`user_id` BIGINT NOT NULL COMMENT '用户ID',
	`real_name` VARCHAR(50) NOT NULL COMMENT '真实姓名',
	`id_card_number` VARCHAR(30) NOT NULL COMMENT '证件号码',
	`id_card_front` VARCHAR(255) COMMENT '身份证正面照片URL',
	`id_card_back` VARCHAR(255) COMMENT '身份证反面照片URL',
	`auth_status` TINYINT DEFAULT 1 COMMENT '认证状态：1-待审核，2-已认证，3-认证失败',
	`audit_admin_id` BIGINT COMMENT '审核管理员ID',
	`audit_time` DATETIME COMMENT '审核时间',
	`fail_reason` VARCHAR(500) COMMENT '审核未通过原因',
	`auth_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发起认证时间',
	`create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
	PRIMARY KEY (`id`),
	KEY `idx_user_id` (`user_id`),
	KEY `idx_auth_time` (`auth_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户实名认证';


ALTER TABLE `im_user` ADD COLUMN `auth_status` tinyint DEFAULT 0 comment '实名认证状态：0-未认证  1-审核中 2-已认证 3-认证失败';

