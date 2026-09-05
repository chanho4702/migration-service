package com.platform.migrationservice.migration.worker;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 지수 백오프 + 상한. 재시도 횟수가 상한을 넘으면 dead letter로 보낸다 — 무한 재시도는
 * rate limit을 악화시키고 job이 끝나지 않게 만든다.
 */
@Component
public class MigrationRetryPolicy {

    private final MigrationWorkerProperties properties;

    public MigrationRetryPolicy(MigrationWorkerProperties properties) {
        this.properties = properties;
    }

    /** retryCount는 지금까지 실패한 횟수다. 이번 실패까지 포함해 상한에 닿으면 재시도하지 않는다. */
    public boolean canRetry(int retryCount) {
        return retryCount + 1 < properties.maxAttempts();
    }

    public Instant nextAttemptAt(int retryCount, Instant now) {
        long base = properties.retryBackoff().toMillis();
        long max = properties.retryBackoffMax().toMillis();
        int exponent = Math.min(Math.max(retryCount, 0), 16);
        long delay = Math.min(max, base << exponent);
        return now.plus(Duration.ofMillis(Math.max(delay, base)));
    }
}
