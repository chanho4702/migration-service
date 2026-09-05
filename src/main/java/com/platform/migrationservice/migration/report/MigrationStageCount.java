package com.platform.migrationservice.migration.report;

import com.platform.migrationservice.migration.model.MigrationStage;

public record MigrationStageCount(MigrationStage stage, long total) {
}
