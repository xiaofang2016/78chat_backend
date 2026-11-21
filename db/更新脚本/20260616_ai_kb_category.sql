-- AI 知识库分类（可运营配置字段结构）
CREATE TABLE IF NOT EXISTS `im_ai_kb_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL COMMENT '分类代码，唯一，创建后不可改',
  `label` varchar(128) NOT NULL DEFAULT '' COMMENT '分类显示名',
  `fields_json` json NOT NULL COMMENT '字段定义 JSON 数组',
  `sort_order` int NOT NULL DEFAULT 0,
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_status_sort` (`status`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI知识库分类配置';

-- 默认四类（若已存在则跳过）
INSERT INTO `im_ai_kb_category` (`code`, `label`, `fields_json`, `sort_order`, `status`)
SELECT 'ACTIVITY', '活动知识库', JSON_ARRAY(
  JSON_OBJECT('key','activityName','label','活动名称','required',true,'inputType','text'),
  JSON_OBJECT('key','activityTime','label','活动时间','required',true,'inputType','text'),
  JSON_OBJECT('key','activityRules','label','活动规则','required',true,'inputType','textarea'),
  JSON_OBJECT('key','activityLink','label','活动链接','required',false,'inputType','url'),
  JSON_OBJECT('key','activityStatus','label','活动状态','required',true,'inputType','select','options',JSON_ARRAY('未开始','进行中','已结束'))
), 1, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `im_ai_kb_category` WHERE `code` = 'ACTIVITY');

INSERT INTO `im_ai_kb_category` (`code`, `label`, `fields_json`, `sort_order`, `status`)
SELECT 'RECHARGE', '充值知识库', JSON_ARRAY(
  JSON_OBJECT('key','rechargeMethod','label','充值方式','required',true,'inputType','text'),
  JSON_OBJECT('key','minAmount','label','最低充值金额','required',true,'inputType','text'),
  JSON_OBJECT('key','arrivalTime','label','到账时间','required',true,'inputType','text')
), 2, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `im_ai_kb_category` WHERE `code` = 'RECHARGE');

INSERT INTO `im_ai_kb_category` (`code`, `label`, `fields_json`, `sort_order`, `status`)
SELECT 'WITHDRAW', '提现知识库', JSON_ARRAY(
  JSON_OBJECT('key','withdrawRules','label','提现规则','required',true,'inputType','textarea'),
  JSON_OBJECT('key','turnoverRules','label','流水规则','required',true,'inputType','textarea'),
  JSON_OBJECT('key','arrivalTime','label','到账时间','required',true,'inputType','text')
), 3, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `im_ai_kb_category` WHERE `code` = 'WITHDRAW');

INSERT INTO `im_ai_kb_category` (`code`, `label`, `fields_json`, `sort_order`, `status`)
SELECT 'APP', 'APP知识库', JSON_ARRAY(
  JSON_OBJECT('key','downloadUrl','label','下载地址','required',true,'inputType','url'),
  JSON_OBJECT('key','installGuide','label','安装教程','required',true,'inputType','textarea'),
  JSON_OBJECT('key','versionNotes','label','版本更新说明','required',false,'inputType','textarea')
), 4, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `im_ai_kb_category` WHERE `code` = 'APP');
