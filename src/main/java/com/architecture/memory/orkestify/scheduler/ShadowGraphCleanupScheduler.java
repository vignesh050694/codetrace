package com.architecture.memory.orkestify.scheduler;

import com.architecture.memory.orkestify.service.graph.ShadowGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to clean up expired shadow graphs.
 * Runs every hour to remove shadow graphs past their TTL.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ShadowGraphCleanupScheduler {

    private final ShadowGraphService shadowGraphService;

    @Scheduled(fixedRate = 3600000) // every hour
    public void cleanupExpiredShadowGraphs() {
        log.debug("Running shadow graph cleanup...");
        try {
            int cleaned = shadowGraphService.cleanupExpiredShadowGraphs();
            if (cleaned > 0) {
                log.info("Shadow graph cleanup completed: {} expired graphs removed", cleaned);
            }
        } catch (Exception e) {
            log.error("Shadow graph cleanup failed: {}", e.getMessage(), e);
        }
    }
}
