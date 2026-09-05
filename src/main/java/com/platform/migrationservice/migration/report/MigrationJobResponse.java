package com.platform.migrationservice.migration.report;

import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.model.MigrationProvider;

import java.time.Instant;

public record MigrationJobResponse(Long id, MigrationProvider provider, String sourceInstanceId,
                                   Long targetSpaceId, MigrationJobMode mode, MigrationJobStatus status,
                                   long itemCount, Instant startedAt, Instant completedAt, Instant createdAt) {

    public static MigrationJobResponse from(MigrationJob job, long itemCount) {
        return new MigrationJobResponse(job.getId(), job.getProvider(), job.getSourceInstanceId(),
                job.getTargetSpaceId(), job.getMode(), job.getStatus(), itemCount,
                job.getStartedAt(), job.getCompletedAt(), job.getCreatedAt());
    }
}
