package com.platform.migrationservice.migration.report;

import com.platform.migrationservice.migration.model.MigrationItemStatus;

public record MigrationStatusCount(MigrationItemStatus status, long total) {
}
