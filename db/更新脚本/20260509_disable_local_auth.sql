-- =============================================================
--  v3.1.0 用户体系打通：box-im 完全禁用本地登录注册
--  - aagames_api 成为用户主权威源（j_users）
--  - im_user.id 必须等于 j_users.id：取消自增主键
--  - 清空所有用户相关数据（测试账号 + 衍生数据）
--  - 后续用户仅通过 /api/login/external 登录，由 box-im 调
--    aagames internal/auth/validate 校验后 lazy upsert
--
--  执行前请确保 im-platform / im-server 已停机！
--  执行：mysql -uroot -p im_platform < 20260509_disable_local_auth.sql
-- =============================================================

USE `im_platform`;

SET @OLD_FK = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 清空用户主表与所有用户绑定数据
TRUNCATE TABLE `im_user`;
TRUNCATE TABLE `im_friend`;
TRUNCATE TABLE `im_friend_request`;
TRUNCATE TABLE `im_private_message`;
TRUNCATE TABLE `im_group`;
TRUNCATE TABLE `im_group_member`;
TRUNCATE TABLE `im_group_message`;
TRUNCATE TABLE `im_system_message`;
TRUNCATE TABLE `im_sm_push_task`;
TRUNCATE TABLE `im_user_blacklist`;
TRUNCATE TABLE `im_user_complaint`;
TRUNCATE TABLE `im_realname_auth`;
TRUNCATE TABLE `im_message_deletion`;
TRUNCATE TABLE `im_sticker_custom`;
-- 表情包公共数据 / 文件 / 公司 / 敏感词 不属于用户范畴，保留
-- 如需一并清，自行追加：
-- TRUNCATE TABLE `im_file_info`;
-- TRUNCATE TABLE `im_company`;

-- 2. 取消 im_user.id 自增（由 box-im 在 lazy upsert 时显式 setId(j_users.id)）
ALTER TABLE `im_user`
    MODIFY `id` BIGINT NOT NULL COMMENT 'id（与 aagames_api j_users.id 一致，禁止自增）';

-- 3. 兼容性：放宽 user_name / phone / email 唯一约束的 NOT NULL 限制
--    aagames_api 中部分用户没有 phone/email，box-im upsert 时这些字段会写入 NULL
--    user_name 仍然必填且唯一（来自 j_users.username）
ALTER TABLE `im_user`
    MODIFY `password` VARCHAR(255) NOT NULL DEFAULT '__EXTERNAL_AUTH__'
        COMMENT '密码（externally authenticated，永不参与本地校验）';

-- phone / email 已经是 NOT NULL=false 的 default null，保持不变

SET FOREIGN_KEY_CHECKS = @OLD_FK;

-- 4. 校验
SELECT 'im_user'      AS tbl, COUNT(*) AS rows_after FROM `im_user`
UNION ALL SELECT 'im_friend',          COUNT(*) FROM `im_friend`
UNION ALL SELECT 'im_friend_request',  COUNT(*) FROM `im_friend_request`
UNION ALL SELECT 'im_private_message', COUNT(*) FROM `im_private_message`
UNION ALL SELECT 'im_group',           COUNT(*) FROM `im_group`
UNION ALL SELECT 'im_group_member',    COUNT(*) FROM `im_group_member`
UNION ALL SELECT 'im_group_message',   COUNT(*) FROM `im_group_message`;

-- 期望全部输出 0。
