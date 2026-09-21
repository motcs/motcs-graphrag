package com.motcs.core.knowledge.record.session;

import com.motcs.commons.ContextUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.domain.Pageable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-21 星期一
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatSessionRequest extends ChatSession {

    private Map<String, Object> params;

    public String whereSql(Pageable pageable) {
        StringBuilder builder = new StringBuilder("SELECT * FROM chat_session WHERE user_id = :userId");
        params = new HashMap<>();
        params.put("userId", this.getUserId());
        if (StringUtils.hasLength(this.getTenantCode())) {
            builder.append(" and tenant_code = :tenantCode");
            params.put("tenantCode", this.getTenantCode());
        }
        if (StringUtils.hasLength(this.getSystemType())) {
            builder.append(" and system_type = :systemType");
            params.put("systemType", this.getSystemType());
        }
        if (!ObjectUtils.isEmpty(this.getApiKeyId())) {
            builder.append(" and api_key_id = :apiKeyId");
            params.put("apiKeyId", this.getApiKeyId());
        }
        return builder.append(" ").append(ContextUtil.applyPage(pageable)).toString();
    }

    public String countSql() {
        StringBuilder countBuilder = new StringBuilder("SELECT count(*) FROM chat_session WHERE user_id = :userId");
        if (StringUtils.hasLength(this.getTenantCode())) {
            countBuilder.append(" and tenant_code = :tenantCode");
        }
        if (StringUtils.hasLength(this.getSystemType())) {
            countBuilder.append(" and system_type = :systemType");
        }
        if (!ObjectUtils.isEmpty(this.getApiKeyId())) {
            countBuilder.append(" and api_key_id = :apiKeyId");
        }
        return countBuilder.toString();
    }

}
