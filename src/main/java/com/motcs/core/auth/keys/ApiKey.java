package com.motcs.core.auth.keys;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * API Key 实体（MySQL R2DBC 存储）
 * 参考 OpenAI API Key 设计：数据库只存 SHA-256 哈希，明文仅在创建时返回一次，
 * 列表/详情只展示前缀掩码，不可再次查看完整 Key。
 */
@Schema(description = "API Key（仅存哈希，明文创建时返回一次）")
@Table("api_key")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKey {

    @Id
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "用途备注", example = "第三方系统对接")
    @Column("name")
    private String name;

    @Schema(description = "Key 前缀（仅展示用）", example = "sk-Ab3Xy7...")
    @Column("key_prefix")
    private String keyPrefix;

    @Schema(description = "完整 Key 的 SHA-256 哈希")
    @JsonIgnore
    @Column("key_hash")
    private String keyHash;

    @Schema(description = "绑定的租户编码（对话/上传文档归属）", example = "410725")
    @Column("tenant_code")
    private String tenantCode;

    @Schema(description = "绑定的系统类型（对话/上传文档归属）", example = "congress")
    @Column("system_type")
    private String systemType;

    @Schema(description = "是否启用", example = "true")
    @Column("enabled")
    private Boolean enabled;

    @Schema(description = "创建人", example = "admin")
    @Column("created_by")
    private String createdBy;

    @Schema(description = "创建时间")
    @Column("created_time")
    private LocalDateTime createdTime;
}
