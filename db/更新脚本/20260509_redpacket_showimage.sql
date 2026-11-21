-- v1.1 阶段2：红包 + 晒图业务表
-- 执行：mysql -uroot -p123456 im_platform < db/更新脚本/20260509_redpacket_showimage.sql

-- ----------------------------
-- 红包主表
-- ----------------------------
DROP TABLE IF EXISTS `im_redpacket`;
CREATE TABLE `im_redpacket` (
  `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT 'id',
  `external_id`       varchar(64)  NOT NULL COMMENT '第三方业务侧唯一键(幂等)',
  `group_id`          bigint       NOT NULL COMMENT '所属群 id',
  `message_id`        bigint       DEFAULT NULL COMMENT '群里那条卡片消息 id (im_group_message.id)',
  `bot_user_id`       bigint       NOT NULL COMMENT '机器人用户 id (j_users.id)',
  `amount`            decimal(10,2) NOT NULL COMMENT '单个红包金额(元)',
  `total_count`       int          NOT NULL COMMENT '名额数',
  `claimed_count`     int          NOT NULL DEFAULT 0 COMMENT '已领数(box-im 端流水, 最终以第三方为准)',
  `title`             varchar(64)  DEFAULT '恭喜发财，大吉大利' COMMENT '卡片祝福语',
  `eligibility_rule`  varchar(32)  NOT NULL DEFAULT 'any' COMMENT 'any / valid_show_poster / daily_quota / third_party',
  `extra`             text         COMMENT '业务透传 JSON',
  `status`            tinyint      NOT NULL DEFAULT 0 COMMENT '0:claiming 1:finished 2:expired 3:rolled_back',
  `expire_at`         datetime(3)  NOT NULL COMMENT '过期时间',
  `created_at`        datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`        datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_external_id` (`external_id`),
  KEY `idx_group_id_status` (`group_id`,`status`),
  KEY `idx_expire_at` (`expire_at`)
) ENGINE = InnoDB CHARSET = utf8mb4 COMMENT = '红包派发主表';

-- ----------------------------
-- 红包领取流水
-- ----------------------------
DROP TABLE IF EXISTS `im_redpacket_record`;
CREATE TABLE `im_redpacket_record` (
  `id`            bigint        NOT NULL AUTO_INCREMENT COMMENT 'id',
  `redpacket_id`  bigint        NOT NULL COMMENT 'im_redpacket.id',
  `external_id`   varchar(64)   NOT NULL COMMENT '冗余 redpacket.external_id, 便于查询',
  `claim_id`      varchar(64)   NOT NULL COMMENT 'box-im 本次领取唯一 id, 给第三方做幂等',
  `user_id`       bigint        NOT NULL COMMENT '领取者 id (j_users.id)',
  `user_name`     varchar(64)   NOT NULL DEFAULT '' COMMENT '领取者昵称',
  `user_avatar`   varchar(255)  DEFAULT '' COMMENT '领取者头像',
  `amount`        decimal(10,2) DEFAULT 0 COMMENT '实际领取金额(以第三方返回为准)',
  `status`        tinyint       NOT NULL DEFAULT 0 COMMENT '0:pending 1:success 2:failed 3:rolled_back',
  `fail_reason`   varchar(255)  DEFAULT '' COMMENT '失败原因(message)',
  `claimed_at`    datetime(3)   DEFAULT NULL COMMENT '第三方记录的实际领取时间',
  `created_at`    datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_redpacket_user` (`redpacket_id`,`user_id`),
  UNIQUE KEY `uk_claim_id` (`claim_id`),
  KEY `idx_user_id_created_at` (`user_id`,`created_at`)
) ENGINE = InnoDB CHARSET = utf8mb4 COMMENT = '红包领取流水';

-- ----------------------------
-- 晒图识别记录
-- ----------------------------
DROP TABLE IF EXISTS `im_show_image`;
CREATE TABLE `im_show_image` (
  `id`               bigint        NOT NULL AUTO_INCREMENT COMMENT 'id',
  `event_id`         varchar(64)   NOT NULL COMMENT 'box-im 生成的识别事件唯一 id',
  `message_id`       bigint        NOT NULL COMMENT '原图片那条群消息 id',
  `group_id`         bigint        NOT NULL COMMENT '群 id',
  `user_id`          bigint        NOT NULL COMMENT '发图用户 id',
  `user_name`        varchar(64)   NOT NULL DEFAULT '' COMMENT '发图用户昵称',
  `user_avatar`      varchar(255)  DEFAULT '' COMMENT '发图用户头像',
  `image_url`        varchar(512)  NOT NULL COMMENT '图片 URL',
  `ocr_text`         text          COMMENT 'OCR 全文',
  `fields`           text          COMMENT 'AI 抽取的结构化 JSON',
  `is_valid`         tinyint(1)    DEFAULT NULL COMMENT 'AI 自判是否为有效晒图: NULL=待识别, 1=是, 0=否',
  `valid_reason`     varchar(255)  DEFAULT '' COMMENT 'AI 给出的判定原因',
  `recognize_status` tinyint       NOT NULL DEFAULT 0 COMMENT '0:pending 1:ok 2:failed',
  `notify_status`    tinyint       NOT NULL DEFAULT 0 COMMENT '0:pending 1:ok 2:failed 3:giveup',
  `notify_try_count` int           NOT NULL DEFAULT 0 COMMENT '推送重试次数',
  `recognized_at`    datetime(3)   DEFAULT NULL COMMENT '识别完成时间',
  `notified_at`      datetime(3)   DEFAULT NULL COMMENT '推送成功时间',
  `created_at`       datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`       datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_group_user_valid` (`group_id`,`user_id`,`is_valid`),
  KEY `idx_notify_status` (`notify_status`)
) ENGINE = InnoDB CHARSET = utf8mb4 COMMENT = '晒图识别记录';
