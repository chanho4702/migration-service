package com.platform.migrationservice.migration.worker;

import com.platform.migrationservice.migration.model.MigrationIssueSeverity;

/**
 * stage handler가 보고하는 손실·경고 한 건. 원본 본문이 아니라 stable code와 source path만 담는다.
 */
public record MigrationStageIssue(MigrationIssueSeverity severity, String code, String sourcePath) {

    public MigrationStageIssue {
        if (severity == null) {
            throw new IllegalArgumentException("severity is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("sourcePath is required");
        }
    }

    /** 손실이 아니라 알림 — dry-run이 "이만큼 옮길 예정"을 보고할 때 쓴다. */
    public static MigrationStageIssue info(String code, String sourcePath) {
        return new MigrationStageIssue(MigrationIssueSeverity.INFO, code, sourcePath);
    }

    public static MigrationStageIssue warning(String code, String sourcePath) {
        return new MigrationStageIssue(MigrationIssueSeverity.WARNING, code, sourcePath);
    }

    public static MigrationStageIssue error(String code, String sourcePath) {
        return new MigrationStageIssue(MigrationIssueSeverity.ERROR, code, sourcePath);
    }
}
