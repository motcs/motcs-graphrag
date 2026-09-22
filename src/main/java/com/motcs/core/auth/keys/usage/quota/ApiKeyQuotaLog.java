package com.motcs.core.auth.keys.usage.quota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * API Key 额度变更流水：每次设置额度 / 追加额度单独记录。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-22 星期二
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("api_key_quota_log")
public class ApiKeyQuotaLog implements Serializable {

    @Id
    private Long id;

    @Column("api_key_id")
    private Long apiKeyId;

    /**
     * SET=设置新额度（已用清零重算）；ADD=追加额度
     */
    @Column("type")
    private String type;

    /**
     * 本次变更金额（SET 为新额度值，ADD 为追加额）
     */
    @Column("amount")
    private Double amount;

    /**
     * 变更后总额度
     */
    @Column("balance_after")
    private Double balanceAfter;

    /**
     * 变更后已用额度
     */
    @Column("used_after")
    private Double usedAfter;

    @Column("remark")
    private String remark;

    @Column("created_time")
    private LocalDateTime createdTime;
}
