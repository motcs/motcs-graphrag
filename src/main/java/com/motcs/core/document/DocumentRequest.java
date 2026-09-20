package com.motcs.core.document;

import com.motcs.commons.utils.ParameterSql;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 文档查询请求 DTO（统计、列表等 GET 接口统一接参）
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Schema(description = "文档查询请求参数")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRequest implements Serializable {

    @Schema(description = "租户编码", example = "410725")
    private String tenantCode;

    @Schema(description = "系统类型", example = "congress")
    private String systemType;

    @Schema(description = "用户编码", example = "user001")
    private String userId;

    @Schema(description = "文件名或标题", example = "user001")
    private String keyword;

    @Schema(description = "状态", example = "user001")
    private String status;

    /**
     * 查询文档分页列表（管理表格用）：支持租户/系统筛选 + 文件名标题关键字 + 状态筛选 + 分页。
     *
     * @return 分页结果（content + totalElements + totalPages + number + size）
     * 构建文档查询条件 SQL
     */
    public ParameterSql buildDocWhereSql() {
        StringJoiner whereSql = new StringJoiner(" AND ");
        Map<String, Object> params = new HashMap<>();
        if (StringUtils.hasLength(this.getTenantCode())) {
            whereSql.add("tenant_code = :tenantCode");
            params.put("tenantCode", this.getTenantCode());
        }
        if (StringUtils.hasLength(this.getSystemType())) {
            whereSql.add("system_type = :systemType");
            params.put("systemType", this.getSystemType());
        }
        if (StringUtils.hasLength(this.getStatus())) {
            whereSql.add("status = :status");
            params.put("status", this.getStatus());
        }
        if (StringUtils.hasLength(this.getKeyword())) {
            whereSql.add("(file_name LIKE :keyword OR title LIKE :keyword)");
            params.put("keyword", "%" + this.getKeyword() + "%");
        }
        return ParameterSql.of(whereSql, params);
    }

}
