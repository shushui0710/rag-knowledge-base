-- 第6周数据库迁移脚本
-- 如果你已经有运行中的MySQL数据库（第1-5周创建的），执行此脚本添加分类字段
--
-- 执行方式：
--   docker exec -i rag-mysql mysql -uroot -prag123456 rag_kb < docker/mysql/migration_week6.sql
-- 或在MySQL客户端中直接执行

USE rag_kb;

-- 文档表添加分类字段
ALTER TABLE `document` ADD COLUMN `category` VARCHAR(50) DEFAULT '其他' COMMENT '文档分类' AFTER `file_size`;

-- 验证
DESCRIBE `document`;
