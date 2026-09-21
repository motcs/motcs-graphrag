package com.motcs.core.auth.keys.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-21 星期一
 */
@Data
public class ApiKeyUsageCount implements Serializable {

    @Schema(title = "启用key总数")
    private Long activeKeys;

    @Schema(title = "删除key总数")
    private Long deletedKeys;

}
