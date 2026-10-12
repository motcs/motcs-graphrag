-- 老库补列：API Key 用量明细增加缓存 token（已存在时执行报错可忽略）
ALTER TABLE api_key_usage
    ADD COLUMN cache_tokens INT DEFAULT 0 COMMENT '缓存命中 token';

-- 老库补列：用量汇总表增加缓存 token（已存在时执行报错可忽略）
ALTER TABLE api_key_usage_summary
    ADD COLUMN cache_tokens INT DEFAULT 0 COMMENT '累计缓存命中 token';

-- 老库补列：用量汇总表增加缓存花费（已存在时执行报错可忽略）
ALTER TABLE api_key_usage_summary
    ADD COLUMN cache_cost DECIMAL(14, 6) DEFAULT 0 COMMENT '累计缓存花费';

-- 老库补列：对话会话用量表增加缓存花费（已存在时执行报错可忽略）
ALTER TABLE chat_session_usage
    ADD COLUMN cache_cost DECIMAL(14, 6) DEFAULT 0 COMMENT '累计缓存花费';

UPDATE chat_session_usage
SET cache_cost = ROUND(
        cache_tokens / 1000.0 * CASE
                                    WHEN session_id IN
                                         (SELECT session_id FROM chat_usage_record WHERE model = 'deepseek-v4.1-flash')
                                        THEN 0.0002
                                    WHEN session_id IN (SELECT session_id
                                                        FROM chat_usage_record
                                                        WHERE model = 'deepseek-v4-flash-0731') THEN 0.0003
                                    WHEN session_id IN
                                         (SELECT session_id FROM chat_usage_record WHERE model = 'glm-5.3-flash')
                                        THEN 0.00023
                                    WHEN session_id IN
                                         (SELECT session_id FROM chat_usage_record WHERE model LIKE 'ernie%')
                                        THEN 0.0002
                                    ELSE 0 END, 6)
WHERE cache_cost = 0
  AND cache_tokens > 0;

-- 同步历史文档存储文件名：stored_file_name 为空时，用 docCode + fileName 扩展名拼接（幂等）
-- 例：docCode=20260918D0032、fileName=委员移动端操作文档（政协）.doc -> 20260918D0032.doc
UPDATE document_info
SET stored_file_name = CONCAT(doc_code, '.', SUBSTRING_INDEX(file_name, '.', -1))
WHERE (stored_file_name IS NULL OR stored_file_name = '')
  AND doc_code IS NOT NULL AND doc_code <> ''
  AND file_name IS NOT NULL AND file_name <> ''
  AND file_name LIKE '%.%';

-- 无法回复问题明细表：记录本地知识库无法回答的问题（供后台定期统计）
CREATE TABLE IF NOT EXISTS unanswered_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question TEXT NOT NULL COMMENT '无法回答的用户问题',
    user_id VARCHAR(128) DEFAULT NULL COMMENT '用户ID',
    session_id VARCHAR(64) DEFAULT NULL COMMENT '会话ID',
    tenant_code VARCHAR(64) DEFAULT NULL COMMENT '租户编码',
    system_type VARCHAR(64) DEFAULT NULL COMMENT '系统类型',
    status VARCHAR(16) DEFAULT 'OPEN' COMMENT '状态：OPEN=待处理 ANSWERED=已解决',
    answer TEXT DEFAULT NULL COMMENT 'AI回答内容',
    reasoning TEXT DEFAULT NULL COMMENT 'AI思考过程',
    sources TEXT DEFAULT NULL COMMENT 'AI回答引用文档(JSON数组)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    KEY idx_uq_tenant_time (tenant_code, create_time),
    KEY idx_uq_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地知识库无法回复的问题';

-- 老库补列：AI回答内容与思考过程（已存在时执行报错可忽略）
ALTER TABLE unanswered_question
    ADD COLUMN answer TEXT DEFAULT NULL COMMENT 'AI回答内容';
ALTER TABLE unanswered_question
    ADD COLUMN reasoning TEXT DEFAULT NULL COMMENT 'AI思考过程';
ALTER TABLE unanswered_question
    ADD COLUMN sources TEXT DEFAULT NULL COMMENT 'AI回答引用文档(JSON数组)';
