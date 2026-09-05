package com.platform.migrationservice.migration.dto;

import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationSource;

import java.time.Instant;

/** 관리자 목록의 한 줄. 원본이 없는 job(예전 NOTION job)은 뒤 두 필드가 비어 있다. */
public record MigrationJobSummary(Long id, MigrationProvider provider, Long targetSpaceId,
                                  MigrationJobMode mode, MigrationJobStatus status, Instant createdAt,
                                  Integer discoveredCount, String sourceSpaceKey) {

    public static MigrationJobSummary from(MigrationJob job, MigrationSource source) {
        return new MigrationJobSummary(job.getId(), job.getProvider(), job.getTargetSpaceId(),
                job.getMode(), job.getStatus(), job.getCreatedAt(),
                source == null ? null : source.getDiscoveredCount(),
                source == null ? null : source.getSpaceKey());
    }
}
