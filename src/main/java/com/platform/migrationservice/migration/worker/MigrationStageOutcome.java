package com.platform.migrationservice.migration.worker;

import java.util.List;

/**
 * stage 성공 결과. 대상 페이지가 만들어졌으면 targetPageId를 돌려주고, 변환 손실은 issue로 남긴다.
 * 실패는 이 타입이 아니라 {@link MigrationStageException}으로 알린다.
 */
public record MigrationStageOutcome(Long targetPageId, List<MigrationStageIssue> issues) {

    public MigrationStageOutcome {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static MigrationStageOutcome ok() {
        return new MigrationStageOutcome(null, List.of());
    }

    public static MigrationStageOutcome ok(List<MigrationStageIssue> issues) {
        return new MigrationStageOutcome(null, issues);
    }

    public static MigrationStageOutcome page(Long targetPageId, List<MigrationStageIssue> issues) {
        return new MigrationStageOutcome(targetPageId, issues);
    }
}
