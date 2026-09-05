package com.platform.migrationservice.migration.normalization;

import com.platform.migrationservice.migration.model.MigrationIssueSeverity;

/**
 * migration_issue로 영속화할 수 있는 본문 비포함 정규화 진단이다.
 */
public record MigrationNormalizationIssue(
        MigrationIssueSeverity severity,
        String code,
        String sourcePath) {
}
