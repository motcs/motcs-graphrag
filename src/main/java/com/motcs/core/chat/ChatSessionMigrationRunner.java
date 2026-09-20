package com.motcs.core.chat;

import com.motcs.core.knowledge.graph.GraphRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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
        this.graphRagService.hasAnySession().flatMap(has -> {
            if (has) {
                log.debug("chat_session 主表已有数据，跳过自动迁移");
                return Mono.empty();
            }
            log.debug("chat_session 主表为空，开始自动迁移老聊天记录...");
            return this.graphRagService.syncChatSessions()
                    .doOnSuccess(count -> log.debug("老聊天记录迁移完成，共同步 {} 个会话", count));
        }).doOnError(e -> log.error("老聊天记录迁移失败: {}", e.getMessage(), e)).subscribe();
    }

}
