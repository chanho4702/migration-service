package com.platform.migrationservice.migration.report;

import com.platform.migrationservice.migration.model.MigrationIssueSeverity;

/**
 * 같은 code가 여러 item·여러 위치에서 나오므로 distinct 건수와 총 발생 수를 함께 센다.
 *
 * sampleSourcePath는 그 code가 난 위치 중 하나(사전순 첫 번째)다. 건수만으로는 관리자가
 * 판단할 수 없기 때문이다 — `MACRO_OPAQUE` 3건이 `macro:jira`인지 `macro:excerpt`인지에 따라
 * 손을 대야 할지가 갈린다. 대표 하나만 주고 전체 목록은 항목 표(`/items`)로 넘긴다.
 */
public record MigrationIssueSummary(MigrationIssueSeverity severity, String code,
                                    long distinctPaths, long occurrences, String sampleSourcePath) {
}
