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