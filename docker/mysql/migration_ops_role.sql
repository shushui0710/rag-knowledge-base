-- 09-20 迁移：为"运维操作"引入最小角色概念
-- 背景：索引重建（POST /api/document/rebuild-index）是全局破坏性操作，修复前任何登录用户都能触发；
--       系统原本没有角色字段，无法做准入控制，故补一个最小 role 列。
-- 影响：user 表新增 1 列（有默认值，存量数据自动为 USER，无需回填）
--
-- 执行：docker exec -i rag-mysql mysql -uroot -p<密码> rag_kb < docker/mysql/migration_ops_role.sql

USE rag_kb;

ALTER TABLE `user`
    ADD COLUMN `role` VARCHAR(16) NOT NULL DEFAULT 'USER'
    COMMENT '角色: USER-普通用户 ADMIN-运维管理员';

-- 提权为运维管理员（按需执行，注册接口无法自助提权）：
-- UPDATE `user` SET `role`='ADMIN' WHERE `username`='<账号名>';
