package com.motcs.config;

import com.motcs.core.knowledge.graph.GraphRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时自动迁移老数据到 chat_session 主表
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-20 星期六
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class ChatSessionMigrationRunner implements ApplicationRunner {

    private final GraphRagService graphRagService;

    @Override
    public void run(@NonNull ApplicationArguments args) {
        log.info("开始自动迁移老聊天记录到 chat_session 主表...");
        this.graphRagService.syncChatSessions()
                .doOnSuccess((Long count) -> log.info("老聊天记录迁移完成，共同步 {} 个会话", count))
                .doOnError(e -> log.error("老聊天记录迁移失败: {}", e.getMessage(), e))
                .subscribe();
    }

}
