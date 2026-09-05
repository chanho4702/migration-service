package com.platform.migrationservice.migration.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * worker 실행 파라미터. lease는 한 item의 최대 처리 시간이자 노드 장애 회수 지연이므로
 * stage handler의 타임아웃보다 넉넉해야 한다.
 */
@Component
public record MigrationWorkerProperties(
        @Value("${platform.migration.worker.worker-id:}") String workerId,
        @Value("${platform.migration.worker.lease:PT5M}") Duration lease,
        @Value("${platform.migration.worker.retry-backoff:PT30S}") Duration retryBackoff,
        @Value("${platform.migration.worker.retry-backoff-max:PT30M}") Duration retryBackoffMax,
        @Value("${platform.migration.worker.max-attempts:5}") int maxAttempts,
        @Value("${platform.migration.worker.batch-size:25}") int batchSize) {

    public MigrationWorkerProperties {
        workerId = workerId == null || workerId.isBlank() ? defaultWorkerId() : workerId;
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
    }

    private static String defaultWorkerId() {
        String host = System.getenv("HOSTNAME");
        String base = host == null || host.isBlank() ? "migration-service" : host;
        String id = base + "-" + Long.toHexString(ProcessHandle.current().pid());
        return id.length() <= 64 ? id : id.substring(id.length() - 64);
    }
}
