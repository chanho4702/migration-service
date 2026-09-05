package com.platform.migrationservice.migration.dto;

import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.report.MigrationJobResponse;

import java.time.Instant;

/**
 * 상세 화면용. 기존 MigrationJobResponse의 필드를 그대로 펼쳐 담고 source·counts만 더한다 —
 * 프론트 어댑터가 기존 shape을 그대로 읽을 수 있어야 한다.
 */
public record MigrationJobDetailResponse(Long id, MigrationProvider provider, String sourceInstanceId,
                                         Long targetSpaceId, MigrationJobMode mode,
                                         MigrationJobStatus status, long itemCount, Instant startedAt,
                                         Instant completedAt, Instant createdAt,
                                         MigrationSourceSummary source, MigrationJobCounts counts) {

    public static MigrationJobDetailResponse of(MigrationJobResponse job, MigrationSourceSummary source,
                                                MigrationJobCounts counts) {
        return new MigrationJobDetailResponse(job.id(), job.provider(), job.sourceInstanceId(),
                job.targetSpaceId(), job.mode(), job.status(), job.itemCount(), job.startedAt(),
                job.completedAt(), job.createdAt(), source, counts);
    }
}
