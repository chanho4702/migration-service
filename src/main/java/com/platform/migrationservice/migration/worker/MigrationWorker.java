package com.platform.migrationservice.migration.worker;

import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 점유 → stage 실행 → 결과 기록을 잇는 실행기. 이 클래스에는 트랜잭션이 없다 — stage handler가
 * 외부 API를 호출하는 동안 트랜잭션을 열어두면 커넥션 풀과 lease가 함께 말라붙는다.
 *
 * 시각은 tick 시작값을 재사용하지 않고 {@link Supplier}로 매번 새로 읽는다. handler 하나가 오래
 * 걸리면 그 뒤의 점유는 이미 만료된 lease를 받게 되고, 다른 노드가 즉시 회수해 같은 일을 두 번 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrationWorker {

    /** stage handler가 던진 예상 밖 예외에 붙이는 코드. 재시도 대상으로 본다. */
    public static final String UNEXPECTED_FAILURE = "STAGE_UNEXPECTED_FAILURE";

    private final MigrationWorkerService worker;
    private final MigrationStageHandlerRegistry handlers;
    private final MigrationJobRepository jobs;
    private final MigrationWorkerProperties properties;

    /** RUNNING job을 오래된 순서로 훑어 한 tick 분량을 처리한다. 처리한 item 수를 돌려준다. */
    public int runOnce(Supplier<Instant> clock) {
        worker.reclaimExpiredLeases(clock.get());
        List<MigrationJob> running = jobs.findByStatusOrderByCreatedAtAscIdAsc(MigrationJobStatus.RUNNING);
        int processed = 0;
        for (MigrationJob job : running) {
            processed += drain(job.getId(), properties.batchSize(), clock);
        }
        return processed;
    }

    /** 한 job에서 최대 maxItems개를 처리한다. 더 집을 item이 없으면 조기 종료한다. */
    public int drain(long jobId, int maxItems, Supplier<Instant> clock) {
        int processed = 0;
        while (processed < maxItems) {
            if (!processOne(jobId, clock)) {
                break;
            }
            processed++;
        }
        if (processed == 0) {
            // 남은 item이 없어 한 건도 집지 못한 job은 여기서 마감된다(item이 0개인 job 포함).
            worker.finalizeJobIfDrained(jobId, clock.get());
        }
        return processed;
    }

    /** item 하나를 처리한다. 집을 item이 없으면 false. */
    public boolean processOne(long jobId, Supplier<Instant> clock) {
        Optional<MigrationStageWork> claimed = worker.claimNext(jobId, clock.get());
        if (claimed.isEmpty()) {
            return false;
        }
        MigrationStageWork work = claimed.get();
        try {
            MigrationStageHandler handler = handlers.require(work.provider(), work.stage());
            MigrationStageOutcome outcome = handler.handle(work);
            worker.recordSuccess(work.itemId(), work.claimToken(), outcome, clock.get());
        } catch (MigrationStageException e) {
            log.warn("migration stage 실패: job={}, item={}, stage={}, code={}, retryable={}",
                    work.jobId(), work.itemId(), work.stage(), e.getCode(), e.isRetryable());
            worker.recordFailure(work.itemId(), work.claimToken(), e.getCode(), e.isRetryable(), clock.get());
        } catch (RuntimeException e) {
            log.error("migration stage 예외: job={}, item={}, stage={}",
                    work.jobId(), work.itemId(), work.stage(), e);
            worker.recordFailure(work.itemId(), work.claimToken(), UNEXPECTED_FAILURE, true, clock.get());
        }
        return true;
    }
}
