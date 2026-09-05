package com.platform.migrationservice.migration.dto;

import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.report.MigrationJobResponse;

import java.time.Instant;
import java.util.List;

/**
 * 상세 화면용. 기존 MigrationJobResponse의 필드를 그대로 펼쳐 담고 source·counts만 더한다 —
 * 프론트 어댑터가 기존 shape을 그대로 읽을 수 있어야 한다.
 */
public record MigrationJobDetailResponse(Long id, MigrationProvider provider, String sourceInstanceId,
                                         Long targetSpaceId, MigrationJobMode mode,
                                         MigrationJobStatus status, long itemCount, Instant startedAt,
                                         Instant completedAt, Instant createdAt,
                                         MigrationSourceSummary source, MigrationJobCounts counts,
                                         /** 항목 표에 나오지 않는 손실(링크 정리 실패 등). 대개 빈 목록이다. */
                                         List<MigrationJobIssueResponse> jobIssues) {

    public static MigrationJobDetailResponse of(MigrationJobResponse job, MigrationSourceSummary source,
                                                MigrationJobCounts counts,
                                                List<MigrationJobIssueResponse> jobIssues) {
        return new MigrationJobDetailResponse(job.id(), job.provider(), job.sourceInstanceId(),
                job.targetSpaceId(), job.mode(), job.status(), job.itemCount(), job.startedAt(),
                job.completedAt(), job.createdAt(), source, counts,
                jobIssues == null ? List.of() : List.copyOf(jobIssues));
    }
}
