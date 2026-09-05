package com.platform.migrationservice.migration.report;

import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationStage;

import java.time.Instant;

public record MigrationDeadLetterResponse(Long itemId, String externalObjectId, MigrationStage stage,
                                          String lastErrorCode, int retryCount, Instant deadLetteredAt) {

    public static MigrationDeadLetterResponse from(MigrationItem item) {
        return new MigrationDeadLetterResponse(item.getId(), item.getExternalObjectId(), item.getStage(),
                item.getLastErrorCode(), item.getRetryCount(), item.getDeadLetteredAt());
    }
}
