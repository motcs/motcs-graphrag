package com.motcs.core.document;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 租户配置表（MySQL R2DBC）：管理端文档管理/对话页的租户下拉框数据源。
 * <p>
 * 说明：租户 0（超管全局租户）不落表，由接口在列表头部固定附加"0 - 全部租户"选项；
 * 业务租户在此表维护，后续所有租户下拉内容均从该表获取。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Schema(description = "租户配置")
@Table("tenant_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantConfig {

    @Id
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "租户编码（唯一）")
    @Column("tenant_code")
    private String tenantCode;

    @Schema(description = "租户名称")
    @Column("tenant_name")
    private String tenantName;

    @Schema(description = "是否启用")
    @Column("enabled")
    private Boolean enabled;

    @Schema(description = "创建时间")
    @Column("created_time")
    private LocalDateTime createdTime;

}
