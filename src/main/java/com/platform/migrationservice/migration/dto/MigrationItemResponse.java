package com.platform.migrationservice.migration.dto;

import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.model.MigrationStage;

import java.time.Instant;

public record MigrationItemResponse(Long id, Long jobId, String externalObjectId, String sourceVersion,
                                    MigrationStage stage, MigrationItemStatus status, int retryCount,
                                    Instant nextAttemptAt, Long targetPageId, String lastErrorCode) {

    public static MigrationItemResponse from(MigrationItem item) {
        return new MigrationItemResponse(item.getId(), item.getJobId(), item.getExternalObjectId(),
                item.getSourceVersion(), item.getStage(), item.getStatus(), item.getRetryCount(),
                item.getNextAttemptAt(), item.getTargetPageId(), item.getLastErrorCode());
    }
}
