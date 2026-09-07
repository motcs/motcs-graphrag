-- ============================================================
-- Motcs Commons 系统管理表结构
-- 字符集：utf8mb4，排序规则：utf8mb4_unicode_ci
-- ============================================================

CREATE DATABASE IF NOT EXISTS motcs DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE motcs;

-- 对话记录表
CREATE TABLE IF NOT EXISTS chat_message
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id     VARCHAR(64) NOT NULL COMMENT '用户编码',
    session_id  VARCHAR(64) NOT NULL COMMENT '会话ID（多轮对话分组）',
    title       VARCHAR(200) COMMENT '会话标题（每条记录冗余存储，取最后一条即可）',
    question    TEXT COMMENT '用户提问',
    answer      TEXT COMMENT 'AI回答',
    reasoning   MEDIUMTEXT COMMENT 'AI思考内容',
    sources     JSON COMMENT '引用知识库来源（JSON数组）',
    tenant_code VARCHAR(64) COMMENT '租户编码',
    system_type VARCHAR(64) COMMENT '系统类型',
    api_key_id  BIGINT COMMENT '创建该对话的API Key ID（空=登录用户创建）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_tenant_system (user_id, tenant_code, system_type),
    INDEX idx_session_id (session_id),
    INDEX idx_api_key_id (api_key_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对话记录表';

-- 会话摘要表（每个会话只存一份，用于长对话压缩上下文）
CREATE TABLE IF NOT EXISTS chat_session_summary
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    session_id         VARCHAR(64) NOT NULL UNIQUE COMMENT '会话ID',
    summary            TEXT COMMENT '历史对话摘要内容',
    last_message_count INT      DEFAULT 0 COMMENT '上次摘要时的消息总数（用于判断是否需要重新摘要）',
    created_time       DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_session_id (session_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='会话摘要表';

-- 兼容已有数据库：新增字段（已存在则跳过，continue-on-error）
ALTER TABLE chat_message
    ADD COLUMN title VARCHAR(200) COMMENT '会话标题（每条记录冗余存储，取最后一条即可）';

ALTER TABLE chat_message
    ADD COLUMN reasoning MEDIUMTEXT COMMENT 'AI思考内容';

-- 兼容已有数据库：API Key 归属列（已存在则跳过，continue-on-error）
ALTER TABLE chat_message
    ADD COLUMN api_key_id BIGINT COMMENT '创建该对话的API Key ID（空=登录用户创建）';

ALTER TABLE chat_message
    ADD INDEX idx_api_key_id (api_key_id);

-- 老库兼容：api_key 表补租户/系统列（重复执行由 continue-on-error 吞掉）
ALTER TABLE api_key
    ADD COLUMN tenant_code VARCHAR(64) DEFAULT NULL COMMENT '绑定的租户编码（对话/上传文档归属）';
ALTER TABLE api_key
    ADD COLUMN system_type VARCHAR(64) DEFAULT NULL COMMENT '绑定的系统类型（对话/上传文档归属）';



-- API Key 表（OpenAI 风格：只存 SHA-256 哈希与前缀掩码，明文仅创建时返回一次）
CREATE TABLE IF NOT EXISTS api_key
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    name         VARCHAR(100) NOT NULL COMMENT '用途备注',
    key_prefix   VARCHAR(40)  NOT NULL COMMENT 'Key前缀（展示掩码）',
    key_hash     CHAR(64)     NOT NULL COMMENT '完整Key的SHA-256哈希',
    tenant_code  VARCHAR(64)  DEFAULT NULL COMMENT '绑定的租户编码（对话/上传文档归属）',
    system_type  VARCHAR(64)  DEFAULT NULL COMMENT '绑定的系统类型（对话/上传文档归属）',
    enabled      TINYINT(1)   DEFAULT 1 COMMENT '是否启用 1启用 0停用',
    created_by   VARCHAR(64)  DEFAULT 'admin' COMMENT '创建人',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_key_hash (key_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='API Key 表';
