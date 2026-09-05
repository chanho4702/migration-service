package com.platform.migrationservice.migration.worker;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * tick은 신호만 보내고 실제 처리는 전용 스레드에서 한다. `@Scheduled` 기본 스케줄러는 스레드가
 * 하나뿐이라, 여기서 stage handler(네트워크 호출)를 직접 돌리면 느린 import 하나가 첨부
 * reconciliation 같은 다른 주기 작업까지 멈춰 세운다. 이전 tick이 아직 돌고 있으면 건너뛴다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "platform.migration.worker.enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class MigrationWorkerScheduler {

    private final MigrationWorker worker;

    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "migration-worker");
        thread.setDaemon(true);
        return thread;
    });

    @Scheduled(
            fixedDelayString = "${platform.migration.worker.interval:PT10S}",
            initialDelayString = "${platform.migration.worker.initial-delay:PT30S}")
    public void run() {
        if (!inFlight.compareAndSet(false, true)) {
            log.debug("이전 migration worker tick이 아직 처리 중이라 건너뛴다");
            return;
        }
        executor.execute(() -> {
            try {
                int processed = worker.runOnce(Instant::now);
                if (processed > 0) {
                    log.info("migration worker tick 완료: processed={}", processed);
                }
            } catch (RuntimeException e) {
                log.error("migration worker tick 실패", e);
            } finally {
                inFlight.set(false);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
