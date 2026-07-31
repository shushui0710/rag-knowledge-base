-- RAG智能知识库问答系统 - 数据库初始化脚本

CREATE DATABASE IF NOT EXISTS rag_kb DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rag_kb;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码(加密)',
    `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` INT DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 文档表
CREATE TABLE IF NOT EXISTS `document` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL COMMENT '所属用户',
    `title` VARCHAR(200) NOT NULL COMMENT '文档标题',
    `file_name` VARCHAR(200) NOT NULL COMMENT '文件名',
    `file_type` VARCHAR(20) NOT NULL COMMENT '文件类型(pdf/docx/md/txt)',
    `file_size` BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    `category` VARCHAR(50) DEFAULT '其他' COMMENT '文档分类',
    `minio_path` VARCHAR(500) DEFAULT NULL COMMENT 'MinIO存储路径',
    `chunk_count` INT DEFAULT 0 COMMENT '分块数量',
    `embedding_status` INT DEFAULT 0 COMMENT '向量化状态: 0-待入库 1-已入库 2-入库失败',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` INT DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档表';

-- 文档分块表
CREATE TABLE IF NOT EXISTS `document_chunk` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `document_id` BIGINT NOT NULL COMMENT '所属文档',
    `chunk_index` INT NOT NULL COMMENT '分块序号',
    `content` TEXT NOT NULL COMMENT '分块文本内容',
    `char_count` INT DEFAULT 0 COMMENT '字符数',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_document_id` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分块表';

-- 对话会话表
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL COMMENT '所属用户',
    `title` VARCHAR(100) DEFAULT '新对话' COMMENT '会话标题',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` INT DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话表';

-- 对话消息表
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `session_id` BIGINT NOT NULL COMMENT '所属会话',
    `role` VARCHAR(20) NOT NULL COMMENT '角色(user/assistant)',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `sources` TEXT DEFAULT NULL COMMENT '来源引用(JSON)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表';

-- 测试用户请通过注册接口创建：POST /api/auth/register
-- 密码现在使用BCrypt加密，不能直接INSERT明文密码
