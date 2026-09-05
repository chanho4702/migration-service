package com.platform.migrationservice.migration.worker;

import com.platform.migrationservice.migration.model.MigrationIssue;
import com.platform.migrationservice.migration.model.MigrationIssueSeverity;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어느 항목에도 매달 수 없는 실패를 잡에 남긴다(V2의 item_id NULL 행).
 *
 * 별도 트랜잭션인 이유가 두 가지다. 부르는 쪽(링크 정리 pass)이 잡 마감 커밋 **뒤에** 돌아
 * 열린 트랜잭션이 없고, 기록이 실패하더라도 이미 끝난 이관을 되돌리면 안 된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrationJobIssueWriter {

    /** 잡이 끝난 뒤의 링크 정리가 실패했다 — 문서는 옮겨졌지만 문서 사이 링크가 원본을 가리킨다. */
    public static final String LINK_FIXUP_FAILED = "LINK_FIXUP_FAILED";

    private final MigrationIssueRepository issues;

    /**
     * 같은 실패가 반복되면 발생 수만 올린다. 기록 자체가 실패해도 삼킨다 — 여기서 예외를 올리면
     * 보고서 한 줄 때문에 잡 마감이나 재실행이 무너진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long jobId, MigrationIssueSeverity severity, String code, String sourcePath) {
        try {
            String issueKey = MigrationIssue.issueKeyFor(code, sourcePath);
            issues.findByJobIdAndItemIdIsNullAndIssueKey(jobId, issueKey)
                    .ifPresentOrElse(
                            existing -> {
                                existing.incrementOccurrence();
                                issues.save(existing);
                            },
                            () -> issues.saveAndFlush(
                                    MigrationIssue.ofJob(jobId, severity, code, sourcePath)));
        } catch (RuntimeException exception) {
            log.warn("잡 단위 손실을 기록하지 못했다: job={} code={} path={}",
                    jobId, code, sourcePath, exception);
        }
    }
}
