package com.motcs.core.auth.keys.usage;

import com.motcs.commons.utils.ParameterSql;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.util.ObjectUtils;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-20 星期日
 */
@Data
public class ApiKeyUsageRequest implements Serializable {

    @Schema(description = "API Key 主键ID")
    @Column("api_key_id")
    private Long apiKeyId;

    public ParameterSql buildWhereSql() {
        StringJoiner whereSql = new StringJoiner(" AND ");
        Map<String, Object> params = new HashMap<>();
        if (!ObjectUtils.isEmpty(this.getApiKeyId())) {
            whereSql.add("api_key_id = :apiKeyId");
            params.put("apiKeyId", this.getApiKeyId());
        }
        return ParameterSql.of(whereSql, params);
    }

}
