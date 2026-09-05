package com.platform.migrationservice.migration.ir;

import com.platform.migrationservice.migration.worker.MigrationStageIssue;

import java.util.List;

/** 변환 결과와 그 과정에서 잃은 것들. 손실은 본문에서 조용히 사라지지 않고 여기로 나온다. */
public record DocumentIrMarkdownResult(String markdown, List<MigrationStageIssue> issues) {

    public DocumentIrMarkdownResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
