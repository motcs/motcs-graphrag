package com.motcs.core.knowledge.record.unanswered;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-10-12 星期一
 */
@Data
@Schema(title = "无法回答的问题统计")
public class UnansweredStatics implements Serializable {

    @Schema(description = "最后统计时间")
    private LocalDateTime lastTime;

    @Schema(description = "问题")
    private String question;

    @Schema(description = "问题提交次数")
    private Long cnt;

}
