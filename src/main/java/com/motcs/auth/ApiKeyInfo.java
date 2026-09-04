package com.motcs.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 生成 API Key 的返回信息（明文 Key 仅此一次，展示后即丢弃）
 */
public record ApiKeyInfo(
        @Schema(description = "主键ID") Long id,
        @Schema(description = "用途备注") String name,
        @Schema(description = "完整 Key（明文，仅创建时返回一次）") String key,
        @Schema(description = "前缀掩码") String prefix,
        @Schema(description = "是否启用") Boolean enabled,
        @Schema(description = "创建时间") LocalDateTime createdTime) {

    public static ApiKeyInfo of(ApiKey e, String plainKey) {
        return new ApiKeyInfo(e.getId(), e.getName(), plainKey, e.getKeyPrefix(), e.getEnabled(), e.getCreatedTime());
    }
}
