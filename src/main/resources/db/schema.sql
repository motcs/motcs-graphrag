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
ALTER TABLE api_key
    ADD COLUMN is_delete TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）：1 已删除，Key 失效且管理列表不可见';


-- API Key 表（OpenAI 风格：只存 SHA-256 哈希与前缀掩码，明文仅创建时返回一次）
CREATE TABLE IF NOT EXISTS api_key
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    name         VARCHAR(100) NOT NULL COMMENT '用途备注',
    key_prefix   VARCHAR(40)  NOT NULL COMMENT 'Key前缀（展示掩码）',
    key_hash     CHAR(64)     NOT NULL COMMENT '完整Key的SHA-256哈希',
    tenant_code  VARCHAR(64) DEFAULT NULL COMMENT '绑定的租户编码（对话/上传文档归属）',
    system_type  VARCHAR(64) DEFAULT NULL COMMENT '绑定的系统类型（对话/上传文档归属）',
    enabled      TINYINT(1)  DEFAULT 1 COMMENT '是否启用 1启用 0停用',
    is_delete    TINYINT(1)  DEFAULT 0 COMMENT '是否已删除（软删除）：1 已删除',
    created_by   VARCHAR(64) DEFAULT 'admin' COMMENT '创建人',
    created_time DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_key_hash (key_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='API Key 表';

CREATE TABLE IF NOT EXISTS api_key_usage
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_key_id        BIGINT NOT NULL COMMENT 'API Key 主键ID',
    user_id           VARCHAR(100) DEFAULT '',
    session_id        VARCHAR(100) DEFAULT '',
    model             VARCHAR(100) DEFAULT '',
    prompt_tokens     INT          DEFAULT 0,
    completion_tokens INT          DEFAULT 0,
    total_tokens      INT          DEFAULT 0,
    created_time      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    KEY idx_usage_api_key (api_key_id),
    KEY idx_usage_created (created_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

create table tenant_config
(
    id           bigint auto_increment
        primary key,
    tenant_code  varchar(50)                          not null comment '租户编码（唯一）',
    tenant_name  varchar(100)                         not null comment '租户名称',
    enabled      tinyint(1) default 1                 null comment '是否启用',
    created_time datetime   default CURRENT_TIMESTAMP null,
    constraint uk_tenant_code
        unique (tenant_code)
)
    comment '租户配置（文档管理/对话租户下拉数据源）';

-- ============================================================
-- API Key 用量汇总表（按 Key 聚合的快照，供用量监控总览快速查询）
-- 每次 /keys/v1/chat 结束记录明细时同步累加本表；监控页直接查本表，
-- 不再每次全量扫描 api_key_usage 明细表内存聚合。
-- ============================================================
CREATE TABLE IF NOT EXISTS api_key_usage_summary
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    api_key_id        BIGINT   NOT NULL COMMENT 'API Key 主键ID（唯一）',
    total_calls       BIGINT   DEFAULT 0 COMMENT '累计调用次数',
    prompt_tokens     BIGINT   DEFAULT 0 COMMENT '累计输入 token',
    completion_tokens BIGINT   DEFAULT 0 COMMENT '累计输出 token',
    total_tokens      BIGINT   DEFAULT 0 COMMENT '累计总 token',
    last_used_at      DATETIME NULL COMMENT '最近调用时间',
    updated_time      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_summary_api_key (api_key_id),
    KEY idx_summary_total_tokens (total_tokens)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='API Key 用量汇总表（按 Key 聚合）';

-- ============================================================
-- 文档元数据表（MySQL R2DBC）：记录每个文档的上传元信息。
-- 文档列表/搜索/筛选直接查本表（快），Neo4j 只负责向量检索与知识图谱。
-- 删除文档时本表记录与 Neo4j 分片/向量/原始文件级联删除。
-- ============================================================
CREATE TABLE IF NOT EXISTS document_info
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    document_id      VARCHAR(64)  NOT NULL COMMENT '文档UUID（与Neo4j documentId一致）',
    doc_code         VARCHAR(100) NOT NULL COMMENT '文档业务编码（唯一）',
    tenant_code      VARCHAR(64)  NOT NULL COMMENT '租户编码',
    system_type      VARCHAR(64)  NOT NULL COMMENT '系统类型',
    file_name        VARCHAR(255) DEFAULT NULL COMMENT '原文件名（界面展示用）',
    stored_file_name VARCHAR(255) DEFAULT NULL COMMENT '本地存储文件名（docCode.后缀，切文/图谱/删除均用此名）',
    title            VARCHAR(255) DEFAULT NULL COMMENT '文档标题',
    description      TEXT COMMENT '文档描述',
    file_size        BIGINT       DEFAULT 0 COMMENT '文件大小（字节）',
    file_path        VARCHAR(500) DEFAULT NULL COMMENT '文件存储路径',
    status           VARCHAR(20)  DEFAULT 'PROCESSING' COMMENT '状态：PROCESSING/SUCCESS/FAILED',
    error_message    TEXT COMMENT '失败原因',
    chunk_count      INT          DEFAULT 0 COMMENT '分片数',
    enabled          TINYINT(1)   DEFAULT 0 COMMENT '是否启用参与检索',
    user_id          VARCHAR(64)  DEFAULT NULL COMMENT '上传者',
    created_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    updated_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    retry_count      INT          DEFAULT 0 COMMENT 'retry once when stuck',
    UNIQUE KEY uk_doc_code (doc_code),
    UNIQUE KEY uk_document_id (document_id),
    KEY idx_doc_tenant_system (tenant_code, system_type),
    KEY idx_doc_status (status),
    KEY idx_doc_created (created_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文档元数据表';

alter table document_info
    add retry_count INT DEFAULT 0 COMMENT 'retry once when stuck';

CREATE TABLE IF NOT EXISTS chat_session
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id     VARCHAR(64) NOT NULL COMMENT '用户编码',
    session_id  VARCHAR(64) NOT NULL COMMENT '会话ID（多轮对话分组）',
    title       VARCHAR(200) COMMENT '会话标题（每条记录冗余存储，取最后一条即可）',
    api_key_id  BIGINT COMMENT '创建该对话的API Key ID（空=登录用户创建）',
    tenant_code VARCHAR(64) COMMENT '租户编码',
    system_type VARCHAR(64) COMMENT '系统类型',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    INDEX idx_user_tenant_system (user_id, tenant_code, system_type),
    INDEX idx_user_tenant_system_api_key_id (user_id, tenant_code, system_type, api_key_id),
    INDEX idx_session_id (session_id),
    INDEX idx_api_key_id (api_key_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对话会话';

ALTER TABLE document_info
    ADD COLUMN stored_file_name VARCHAR(255) DEFAULT NULL COMMENT '本地存储文件名' AFTER file_name;

ALTER TABLE api_key
    ADD COLUMN is_delete TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）：1 已删除' AFTER created_by;