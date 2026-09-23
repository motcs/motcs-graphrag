select *
from (SELECT k.id                             AS id,
             k.name                           AS name,
             k.key_prefix                     AS key_prefix,
             k.tenant_code                    AS tenant_code,
             k.system_type                    AS system_type,
             k.enabled                        AS enabled,
             k.is_delete                      AS is_delete,
             COALESCE(s.total_calls, 0)       AS total_calls,
             COALESCE(s.prompt_tokens, 0)     AS prompt_tokens,
             COALESCE(s.completion_tokens, 0) AS completion_tokens,
             COALESCE(s.total_tokens, 0)      AS total_tokens,
             COALESCE(s.input_cost, 0)        AS input_cost,
             COALESCE(s.output_cost, 0)       AS output_cost,
             COALESCE(s.cache_tokens, 0)      AS cache_tokens,
             COALESCE(s.cache_cost, 0)        AS cache_cost,
             s.last_used_at                   AS last_used_at
      FROM api_key k
               LEFT JOIN api_key_usage_summary s ON
          s.api_key_id = k.id
      order by COALESCE(s.total_tokens, 0) desc, id) t
ORDER BY (output_cost + input_cost) DESC
limit 10 offset 0