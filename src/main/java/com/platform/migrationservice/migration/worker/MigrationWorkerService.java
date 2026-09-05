package com.platform.migrationservice.migration.worker;

import com.platform.migrationservice.migration.confluence.link.MigrationLinkFixupService;
import com.platform.migrationservice.migration.model.MigrationIssue;
import com.platform.migrationservice.migration.model.MigrationIssueSeverity;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * worker의 트랜잭션 단위. 점유·결과 기록은 짧은 트랜잭션으로 끊고, 실제 stage 실행(네트워크 I/O)은
 * {@link MigrationWorker}가 트랜잭션 밖에서 돌린다 — 외부 호출 동안 DB 커넥션과 행 잠금을 잡고 있으면
 * 노드 하나가 전체 job을 막는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationWorkerService {

    /** 노드가 죽어 lease가 만료된 item을 회수했을 때 남기는 코드. */
    public static final String LEASE_EXPIRED = "WORKER_LEASE_EXPIRED";

    private static final EnumSet<MigrationItemStatus> ACTIVE =
            EnumSet.of(MigrationItemStatus.PENDING, MigrationItemStatus.RETRY_WAIT, MigrationItemStatus.RUNNING);

    private final MigrationJobRepository jobs;
    private final MigrationItemRepository items;
    private final MigrationIssueRepository issues;
    private final MigrationObjectMappingWriter objectMappings;
    private final MigrationRetryPolicy retryPolicy;
    private final MigrationWorkerProperties properties;
    private final MigrationLinkFixupService linkFixup;
    private final MigrationJobIssueWriter jobIssues;

    /**
     * 처리할 item 하나를 점유한다. 같은 item을 두 노드가 동시에 노리면 조건부 UPDATE에서 한쪽만
     * 이기고, 진 쪽은 다음 후보로 넘어간다.
     */
    @Transactional
    public Optional<MigrationStageWork> claimNext(long jobId, Instant now) {
        // job 행을 잠근 뒤 상태를 본다. 잠그지 않으면 RUNNING을 읽은 직후 cancel이 커밋돼
        // 취소된 job의 item을 집고 handler를 시작한다.
        MigrationJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (job == null || job.getStatus() != MigrationJobStatus.RUNNING) {
            return Optional.empty();
        }
        List<Long> candidates = items.findClaimableIds(jobId, MigrationItemStatus.PENDING,
                MigrationItemStatus.RETRY_WAIT, now, PageRequest.of(0, properties.batchSize()));
        for (Long candidateId : candidates) {
            String claimToken = UUID.randomUUID().toString();
            int claimed = items.claim(candidateId, properties.workerId(), claimToken,
                    now.plus(properties.lease()), now, MigrationItemStatus.RUNNING,
                    MigrationItemStatus.PENDING, MigrationItemStatus.RETRY_WAIT);
            if (claimed == 0) {
                continue;
            }
            MigrationItem item = items.findById(candidateId).orElseThrow();
            return Optional.of(toWork(job, item));
        }
        return Optional.empty();
    }

    /** stage 성공 기록 — 다음 단계로 전진하고, 마지막 단계면 외부 object map을 갱신한다. */
    @Transactional
    public void recordSuccess(long itemId, String claimToken, MigrationStageOutcome outcome, Instant now) {
        MigrationItem item = items.findById(itemId).orElseThrow();
        MigrationJob job = jobs.findById(item.getJobId()).orElseThrow();
        if (!ownsClaim(item, job, claimToken)) {
            return;
        }
        recordIssues(item, outcome.issues());
        if (outcome.targetPageId() != null) {
            item.bindTargetPage(outcome.targetPageId());
        }
        MigrationStage next = MigrationStage.values()[item.getStage().ordinal() + 1];
        item.completeStage(next);
        items.saveAndFlush(item);

        if (next == MigrationStage.DONE) {
            upsertObjectMapping(job, item);
        }
        finalizeJobIfDrained(job, now);
    }

    /** stage 실패 기록 — 재시도 가능하면 백오프, 아니면 dead letter와 ERROR issue. */
    @Transactional
    public void recordFailure(long itemId, String claimToken, String errorCode, boolean retryable, Instant now) {
        MigrationItem item = items.findById(itemId).orElseThrow();
        MigrationJob job = jobs.findById(item.getJobId()).orElseThrow();
        if (!ownsClaim(item, job, claimToken)) {
            return;
        }
        if (retryable && retryPolicy.canRetry(item.getRetryCount())) {
            item.scheduleRetry(errorCode, retryPolicy.nextAttemptAt(item.getRetryCount(), now));
            items.saveAndFlush(item);
            return;
        }
        deadLetter(item, job, errorCode, now);
    }

    /**
     * lease가 만료된 RUNNING item을 회수한다. 노드가 죽어 결과를 기록하지 못한 item이 영원히
     * RUNNING으로 남는 것을 막는 유일한 경로다. 회수도 한 번의 시도로 세므로, 계속 죽는 노드가
     * 같은 item을 무한히 되살리지 않는다.
     */
    @Transactional
    public int reclaimExpiredLeases(Instant now) {
        List<MigrationItem> stuck = items.findByStatusAndLeaseExpiresAtLessThanEqualOrderByIdAsc(
                MigrationItemStatus.RUNNING, now, PageRequest.of(0, properties.batchSize()));
        int reclaimed = 0;
        for (MigrationItem item : stuck) {
            if (!item.isLeaseExpired(now)) {
                continue;
            }
            if (retryPolicy.canRetry(item.getRetryCount())) {
                item.releaseExpiredLease(LEASE_EXPIRED, now, retryPolicy.nextAttemptAt(item.getRetryCount(), now));
                items.save(item);
            } else {
                deadLetter(item, jobs.findById(item.getJobId()).orElseThrow(), LEASE_EXPIRED, now);
            }
            reclaimed++;
        }
        if (reclaimed > 0) {
            log.warn("만료된 migration lease {}건을 회수했다", reclaimed);
        }
        return reclaimed;
    }

    /** 남은 처리 대상이 없으면 job을 마감한다. dead letter가 하나라도 있으면 FAILED다. */
    @Transactional
    public Optional<MigrationJobStatus> finalizeJobIfDrained(long jobId, Instant now) {
        return jobs.findById(jobId).flatMap(job -> finalizeJobIfDrained(job, now));
    }

    private Optional<MigrationJobStatus> finalizeJobIfDrained(MigrationJob job, Instant now) {
        if (job.getStatus() != MigrationJobStatus.RUNNING
                || items.existsByJobIdAndStatusIn(job.getId(), ACTIVE)) {
            return Optional.empty();
        }
        boolean hasDeadLetter = !items
                .findByJobIdAndStatusOrderByIdAsc(job.getId(), MigrationItemStatus.DEAD_LETTER).isEmpty();
        if (hasDeadLetter) {
            job.fail(now);
        } else {
            job.complete(now);
        }
        jobs.saveAndFlush(job);
        scheduleLinkFixup(job.getId());
        return Optional.of(job.getStatus());
    }

    /**
     * 잡이 끝났으니 임시 링크를 마저 잇는다(M2 §4.2).
     *
     * **커밋 뒤에** 돈다. 여기서 바로 부르면 문서 수백 건을 고치는 동안 job 행을 잠근 채로 있게 되고,
     * 정리 중 예외 하나가 잡의 마감 자체를 롤백한다 — 옮기기는 다 끝났는데 상태가 RUNNING으로
     * 남는 것이 가장 나쁜 결말이다. 그래서 커밋이 확정된 뒤 별도 트랜잭션으로 돌리고, 실패는 삼킨다.
     */
    private void scheduleLinkFixup(long jobId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runLinkFixup(jobId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runLinkFixup(jobId);
            }
        });
    }

    /**
     * pass 전체가 터진 경우다(문서별 실패는 pass 안에서 따로 기록한다). 잡의 결말은 바꾸지 않되
     * 보고서에는 남긴다 — 로그로만 두면 "완료"인데 문서 사이 링크가 원본 사이트로 튕기는 상태를
     * 관리자가 알 길이 없다. 고친 뒤 `POST /api/migration/{id}/link-fixup`으로 다시 돌린다.
     */
    private void runLinkFixup(long jobId) {
        try {
            linkFixup.run(jobId);
        } catch (RuntimeException exception) {
            log.warn("이관 링크 정리에 실패했다 — 잡 결과는 그대로 둔다: job={}", jobId, exception);
            jobIssues.record(jobId, MigrationIssueSeverity.ERROR,
                    MigrationJobIssueWriter.LINK_FIXUP_FAILED, "job:" + jobId);
        }
    }

    /**
     * 결과를 낸 시도가 아직 그 item의 주인인지 확인한다. handler가 lease보다 오래 걸리면 다른 노드가
     * 이미 같은 item을 회수해 새로 점유했을 수 있고, 그때 옛 시도의 결과를 반영하면 단계가 통째로
     * 건너뛰어지거나 새 점유의 lease가 풀린다.
     */
    private boolean ownsClaim(MigrationItem item, MigrationJob job, String claimToken) {
        if (job.getStatus() == MigrationJobStatus.RUNNING
                && item.getStatus() == MigrationItemStatus.RUNNING
                && item.getClaimToken() != null && item.getClaimToken().equals(claimToken)) {
            return true;
        }
        log.warn("유효하지 않은 점유의 결과를 버린다: item={}, itemStatus={}, jobStatus={}",
                item.getId(), item.getStatus(), job.getStatus());
        return false;
    }

    private void deadLetter(MigrationItem item, MigrationJob job, String errorCode, Instant now) {
        item.deadLetter(errorCode, now);
        items.saveAndFlush(item);
        recordIssues(item, List.of(MigrationStageIssue.error(errorCode, item.getExternalObjectId())));
        finalizeJobIfDrained(job, now);
    }

    private void recordIssues(MigrationItem item, List<MigrationStageIssue> reported) {
        for (MigrationStageIssue issue : reported) {
            String issueKey = MigrationIssue.issueKeyFor(issue.code(), issue.sourcePath());
            Optional<MigrationIssue> existing = issues.findByItemIdAndIssueKey(item.getId(), issueKey);
            if (existing.isPresent()) {
                existing.get().incrementOccurrence();
                issues.save(existing.get());
                continue;
            }
            issues.save(MigrationIssue.of(item.getJobId(), item.getId(),
                    issue.severity(), issue.code(), issue.sourcePath()));
        }
    }

    /**
     * DRY_RUN은 대상 페이지를 만들지 않으므로 object map도 남기지 않는다 — 남기면 다음 실제 import가
     * 이미 옮긴 것으로 착각한다.
     */
    private void upsertObjectMapping(MigrationJob job, MigrationItem item) {
        if (job.getMode() != MigrationJobMode.IMPORT || item.getTargetPageId() == null) {
            return;
        }
        try {
            objectMappings.upsert(job.getProvider(), job.getSourceInstanceId(), item.getExternalObjectId(),
                    item.getSourceVersion(), item.getSourceChecksum(), item.getTargetPageId(), job.getId());
        } catch (DataIntegrityViolationException e) {
            // 다른 job이 같은 원본을 먼저 넣었다. 새 트랜잭션에서 다시 부르면 update 경로로 수렴한다.
            objectMappings.upsert(job.getProvider(), job.getSourceInstanceId(), item.getExternalObjectId(),
                    item.getSourceVersion(), item.getSourceChecksum(), item.getTargetPageId(), job.getId());
        }
    }

    private MigrationStageWork toWork(MigrationJob job, MigrationItem item) {
        return new MigrationStageWork(job.getId(), item.getId(), item.getClaimToken(), job.getProvider(),
                job.getSourceInstanceId(),
                job.getMode(), job.getTargetSpaceId(), job.getRequestedBy(), item.getStage(),
                item.getExternalObjectId(),
                item.getSourceVersion(), item.getSourceChecksum(), item.getPayloadRef(),
                item.getTargetPageId(), item.getSiblingOrder(), item.getRetryCount() + 1);
    }
}
