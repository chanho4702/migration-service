package com.platform.migrationservice.migration.dto;

import com.platform.migrationservice.migration.model.MigrationIssue;
import com.platform.migrationservice.migration.model.MigrationIssueSeverity;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 어느 항목에도 매달리지 않은 손실 한 건. 항목 표(`/items`)에는 나오지 않으므로 잡 상세가
 * 직접 보여 준다 — 링크 정리 실패가 여기로 온다.
 */
@Schema(description = "잡 단위 손실. 항목 표에 나오지 않는 실패(예: 링크 정리)를 상세 화면이 보여 준다.")
public record MigrationJobIssueResponse(
        @Schema(description = "심각도", example = "ERROR") MigrationIssueSeverity severity,
        @Schema(description = "손실 코드", example = "LINK_FIXUP_FAILED") String code,
        @Schema(description = "어디서 났는가", example = "page:1042") String sourcePath,
        @Schema(description = "같은 실패가 반복된 횟수", example = "1") int occurrences) {

    public static MigrationJobIssueResponse from(MigrationIssue issue) {
        return new MigrationJobIssueResponse(issue.getSeverity(), issue.getCode(),
                issue.getSourcePath(), issue.getOccurrenceCount());
    }
}
