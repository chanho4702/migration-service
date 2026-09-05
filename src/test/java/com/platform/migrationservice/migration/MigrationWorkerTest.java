package com.platform.migrationservice.migration;

import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.migration.worker.MigrationStageHandler;
import com.platform.migrationservice.migration.worker.MigrationStageHandlerRegistry;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.migration.worker.MigrationStageOutcome;
import com.platform.migrationservice.migration.worker.MigrationStageWork;
import com.platform.migrationservice.migration.worker.MigrationWorker;
import com.platform.migrationservice.migration.worker.MigrationWorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * worker 실행기 계약: 점유 → stage 전진 → 재시도/DLQ → job 마감.
 * 실제 connector 없이 stage handler를 프로그래밍 가능한 페이크로 대체해 실행 규칙만 고정한다.
 */
@SpringBootTest(properties = {
        "platform.migration.worker.max-attempts=2",
        "platform.migration.worker.retry-backoff=PT10S",
        "platform.migration.worker.retry-backoff-max=PT1M",
        "platform.migration.worker.lease=PT2M",
})
@ActiveProfiles("test")
@Import(MigrationWorkerTest.StageHandlers.class)
class MigrationWorkerTest {

    private static final String CHECKSUM = "b".repeat(64);
    private static final Instant T0 = Instant.parse("2026-08-18T09:00:00Z");

    @Autowired MigrationWorker worker;
    @Autowired MigrationWorkerService workerService;
    @Autowired MigrationStageHandlerRegistry registry;
    @Autowired ProgrammableStages stages;
    @Autowired MigrationJobRepository jobs;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationObjectMappingRepository mappings;

    /**
     * 스페이스·페이지 id는 위키의 것이다 — 이 서비스 DB에는 그 행이 없고 FK도 없다.
     * 이 테스트가 보는 것은 worker의 실행 규칙뿐이라 임의의 값으로 충분하다.
     */
    private static final long SPACE_ID = 4242L;
    private static final long PAGE_ID = 90001L;

    @BeforeEach
    void setUp() {
        stages.reset();
        issues.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        jobs.deleteAllInBatch();
    }

    @Test
    void 모든_stage를_지나면_item은_DONE이고_job은_COMPLETED로_마감된다() {
        stages.on(MigrationStage.RESOLVE, work -> MigrationStageOutcome.page(PAGE_ID, List.of()));
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.IMPORT);
        MigrationItem item = enqueue(job, "page-1");

        int processed = worker.drain(job.getId(), 20, () -> T0);

        assertThat(processed).isEqualTo(MigrationStage.values().length - 1);
        assertThat(items.findById(item.getId()).orElseThrow())
                .satisfies(saved -> {
                    assertThat(saved.getStage()).isEqualTo(MigrationStage.DONE);
                    assertThat(saved.getStatus()).isEqualTo(MigrationItemStatus.COMPLETED);
                    assertThat(saved.getTargetPageId()).isEqualTo(PAGE_ID);
                    assertThat(saved.getClaimedBy()).isNull();
                });
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(MigrationJobStatus.COMPLETED);
        assertThat(mappings.findBySourceKey(MigrationObjectMapping.sourceKeyFor(
                MigrationProvider.NOTION, "workspace-acme", "page-1")))
                .get()
                .satisfies(mapping -> {
                    assertThat(mapping.getTargetPageId()).isEqualTo(PAGE_ID);
                    assertThat(mapping.getLastJobId()).isEqualTo(job.getId());
                });
    }

    @Test
    void 재시도_가능한_실패는_백오프_뒤에만_다시_집힌다() {
        stages.failOnce(MigrationStage.NORMALIZE, MigrationStageException.retryable("NOTION_RATE_LIMITED"));
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.IMPORT);
        MigrationItem item = enqueue(job, "page-2");

        worker.drain(job.getId(), 20, () -> T0);

        MigrationItem waiting = items.findById(item.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(MigrationItemStatus.RETRY_WAIT);
        assertThat(waiting.getStage()).isEqualTo(MigrationStage.NORMALIZE);
        assertThat(waiting.getRetryCount()).isEqualTo(1);
        assertThat(waiting.getLastErrorCode()).isEqualTo("NOTION_RATE_LIMITED");
        assertThat(waiting.getNextAttemptAt()).isEqualTo(T0.plusSeconds(10));

        assertThat(worker.processOne(job.getId(), () -> T0.plusSeconds(9))).isFalse();

        worker.drain(job.getId(), 20, () -> T0.plusSeconds(10));

        assertThat(items.findById(item.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationItemStatus.COMPLETED);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(MigrationJobStatus.COMPLETED);
    }

    @Test
    void 재시도_상한을_넘긴_item은_dead_letter가_되고_job은_FAILED다() {
        stages.on(MigrationStage.EXTRACT, work -> {
            throw MigrationStageException.retryable("NOTION_TIMEOUT");
        });
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.IMPORT);
        MigrationItem item = enqueue(job, "page-3");

        worker.drain(job.getId(), 20, () -> T0);
        worker.drain(job.getId(), 20, () -> T0.plus(Duration.ofMinutes(1)));

        MigrationItem dead = items.findById(item.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(MigrationItemStatus.DEAD_LETTER);
        assertThat(dead.getRetryCount()).isEqualTo(1);
        assertThat(dead.getLastErrorCode()).isEqualTo("NOTION_TIMEOUT");
        assertThat(dead.getDeadLetteredAt()).isNotNull();
        assertThat(issues.findByJobIdOrderByIdAsc(job.getId()))
                .singleElement()
                .satisfies(issue -> assertThat(issue.getCode()).isEqualTo("NOTION_TIMEOUT"));
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(MigrationJobStatus.FAILED);
    }

    @Test
    void handler가_없는_provider는_재시도하지_않고_바로_dead_letter다() {
        // W29에서 CONFLUENCE_DC 5단계 handler가 붙어 provider 단위로는 빈 자리가 없다.
        // 등록되지 않은 조합은 registry가 비재시도 실패로 막는다는 계약만 남는다.
        assertThat(registry.find(MigrationProvider.CONFLUENCE_DC, MigrationStage.DONE)).isEmpty();
        assertThatThrownBy(() -> registry.require(MigrationProvider.CONFLUENCE_DC, MigrationStage.DONE))
                .isInstanceOfSatisfying(MigrationStageException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(MigrationStageHandlerRegistry.HANDLER_UNAVAILABLE);
                    assertThat(e.isRetryable()).isFalse();
                });

        stages.on(MigrationStage.EXTRACT, work -> {
            throw MigrationStageException.permanent(MigrationStageHandlerRegistry.HANDLER_UNAVAILABLE);
        });
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.DRY_RUN);
        MigrationItem item = enqueue(job, "content-10001");

        worker.drain(job.getId(), 20, () -> T0);

        MigrationItem dead = items.findById(item.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(MigrationItemStatus.DEAD_LETTER);
        assertThat(dead.getRetryCount()).isZero();
        assertThat(dead.getLastErrorCode()).isEqualTo(MigrationStageHandlerRegistry.HANDLER_UNAVAILABLE);
    }

    @Test
    void lease가_만료된_RUNNING_item은_다른_노드가_회수한다() {
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.IMPORT);
        MigrationItem item = enqueue(job, "page-4");
        assertThat(workerService.claimNext(job.getId(), T0)).isPresent();

        assertThat(workerService.reclaimExpiredLeases(T0.plusSeconds(60))).isZero();
        assertThat(workerService.reclaimExpiredLeases(T0.plus(Duration.ofMinutes(2)))).isEqualTo(1);

        MigrationItem reclaimed = items.findById(item.getId()).orElseThrow();
        assertThat(reclaimed.getStatus()).isEqualTo(MigrationItemStatus.RETRY_WAIT);
        assertThat(reclaimed.getLastErrorCode()).isEqualTo(MigrationWorkerService.LEASE_EXPIRED);
        assertThat(reclaimed.getClaimedBy()).isNull();
        assertThat(reclaimed.getLeaseExpiresAt()).isNull();
    }

    @Test
    void DRY_RUN은_대상_페이지를_연결해도_object_map을_남기지_않는다() {
        stages.on(MigrationStage.RESOLVE, work -> MigrationStageOutcome.page(PAGE_ID, List.of()));
        stages.on(MigrationStage.VERIFY, work -> MigrationStageOutcome.ok(
                List.of(MigrationStageIssue.warning("UNSUPPORTED_BLOCK", "/blocks/3"))));
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.DRY_RUN);
        enqueue(job, "page-5");

        worker.drain(job.getId(), 20, () -> T0);

        assertThat(mappings.count()).isZero();
        assertThat(issues.summarize(job.getId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.code()).isEqualTo("UNSUPPORTED_BLOCK");
                    assertThat(summary.occurrences()).isEqualTo(1);
                });
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(MigrationJobStatus.COMPLETED);
    }

    @Test
    void lease를_넘긴_시도의_결과는_새_점유를_건드리지_못한다() {
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.IMPORT);
        MigrationItem item = enqueue(job, "page-6");
        MigrationStageWork stale = workerService.claimNext(job.getId(), T0).orElseThrow();

        // 노드가 응답 없이 늘어지는 사이 lease가 만료돼 다른 노드가 회수하고 다시 집었다.
        workerService.reclaimExpiredLeases(T0.plus(Duration.ofMinutes(2)));
        MigrationStageWork fresh = workerService
                .claimNext(job.getId(), T0.plus(Duration.ofMinutes(3))).orElseThrow();
        assertThat(fresh.claimToken()).isNotEqualTo(stale.claimToken());

        workerService.recordSuccess(stale.itemId(), stale.claimToken(),
                MigrationStageOutcome.ok(), T0.plus(Duration.ofMinutes(3)));

        MigrationItem current = items.findById(item.getId()).orElseThrow();
        assertThat(current.getStage()).isEqualTo(MigrationStage.EXTRACT);
        assertThat(current.getStatus()).isEqualTo(MigrationItemStatus.RUNNING);
        assertThat(current.getClaimToken()).isEqualTo(fresh.claimToken());
    }

    @Test
    void 계속_죽는_노드의_item은_lease_회수_상한에서_dead_letter가_된다() {
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.IMPORT);
        MigrationItem item = enqueue(job, "page-7");

        // max-attempts=2 — 첫 회수는 재시도 대기, 두 번째 회수에서 끝낸다.
        workerService.claimNext(job.getId(), T0);
        assertThat(workerService.reclaimExpiredLeases(T0.plus(Duration.ofMinutes(2)))).isEqualTo(1);
        assertThat(items.findById(item.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationItemStatus.RETRY_WAIT);

        workerService.claimNext(job.getId(), T0.plus(Duration.ofMinutes(10)));
        assertThat(workerService.reclaimExpiredLeases(T0.plus(Duration.ofMinutes(20)))).isEqualTo(1);

        MigrationItem dead = items.findById(item.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(MigrationItemStatus.DEAD_LETTER);
        assertThat(dead.getLastErrorCode()).isEqualTo(MigrationWorkerService.LEASE_EXPIRED);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(MigrationJobStatus.FAILED);
    }

    @Test
    void 취소된_job은_새로_집지도_진행_중이던_결과를_반영하지도_않는다() {
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.IMPORT);
        MigrationItem item = enqueue(job, "page-8");
        MigrationStageWork work = workerService.claimNext(job.getId(), T0).orElseThrow();

        MigrationJob cancelled = jobs.findById(job.getId()).orElseThrow();
        cancelled.cancel(T0.plusSeconds(1));
        jobs.saveAndFlush(cancelled);

        workerService.recordSuccess(work.itemId(), work.claimToken(),
                MigrationStageOutcome.ok(), T0.plusSeconds(2));

        assertThat(items.findById(item.getId()).orElseThrow().getStage()).isEqualTo(MigrationStage.EXTRACT);
        assertThat(workerService.claimNext(job.getId(), T0.plusSeconds(3))).isEmpty();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(MigrationJobStatus.CANCELLED);
    }

    @Test
    void item이_없는_job도_마감된다() {
        MigrationJob job = startedJob(MigrationProvider.NOTION, MigrationJobMode.DRY_RUN);

        assertThat(worker.drain(job.getId(), 20, () -> T0)).isZero();

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(MigrationJobStatus.COMPLETED);
    }

    private MigrationJob startedJob(MigrationProvider provider, MigrationJobMode mode) {
        MigrationJob job = jobs.save(MigrationJob.create(provider, "workspace-acme", SPACE_ID, 7L, mode));
        job.start(T0.minusSeconds(1));
        return jobs.saveAndFlush(job);
    }

    private MigrationItem enqueue(MigrationJob job, String externalObjectId) {
        return items.saveAndFlush(MigrationItem.pending(job.getId(), externalObjectId, "1", CHECKSUM,
                "imports/" + externalObjectId + ".json"));
    }

    /** stage별 동작을 테스트가 바꿔 끼우는 페이크. 기본값은 "그냥 성공"이다. */
    static class ProgrammableStages {
        private final Map<MigrationStage, Function<MigrationStageWork, MigrationStageOutcome>> behaviors =
                new EnumMap<>(MigrationStage.class);
        final List<MigrationStageWork> seen = new ArrayList<>();

        void reset() {
            behaviors.clear();
            seen.clear();
        }

        void on(MigrationStage stage, Function<MigrationStageWork, MigrationStageOutcome> behavior) {
            behaviors.put(stage, behavior);
        }

        void failOnce(MigrationStage stage, MigrationStageException failure) {
            behaviors.put(stage, work -> {
                behaviors.remove(stage);
                throw failure;
            });
        }

        MigrationStageOutcome handle(MigrationStageWork work) {
            seen.add(work);
            Function<MigrationStageWork, MigrationStageOutcome> behavior = behaviors.get(work.stage());
            return behavior == null ? MigrationStageOutcome.ok() : behavior.apply(work);
        }
    }

    private record FakeStageHandler(MigrationProvider provider, MigrationStage stage, ProgrammableStages stages)
            implements MigrationStageHandler {

        @Override
        public MigrationStageOutcome handle(MigrationStageWork work) {
            return stages.handle(work);
        }
    }

    @TestConfiguration
    static class StageHandlers {

        @Bean
        ProgrammableStages programmableStages() {
            return new ProgrammableStages();
        }

        @Bean
        MigrationStageHandler notionExtract(ProgrammableStages stages) {
            return new FakeStageHandler(MigrationProvider.NOTION, MigrationStage.EXTRACT, stages);
        }

        @Bean
        MigrationStageHandler notionNormalize(ProgrammableStages stages) {
            return new FakeStageHandler(MigrationProvider.NOTION, MigrationStage.NORMALIZE, stages);
        }

        @Bean
        MigrationStageHandler notionMediaCopy(ProgrammableStages stages) {
            return new FakeStageHandler(MigrationProvider.NOTION, MigrationStage.MEDIA_COPY, stages);
        }

        @Bean
        MigrationStageHandler notionResolve(ProgrammableStages stages) {
            return new FakeStageHandler(MigrationProvider.NOTION, MigrationStage.RESOLVE, stages);
        }

        @Bean
        MigrationStageHandler notionVerify(ProgrammableStages stages) {
            return new FakeStageHandler(MigrationProvider.NOTION, MigrationStage.VERIFY, stages);
        }
    }
}
