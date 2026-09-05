package com.platform.migrationservice.migration.report;

import java.util.List;
import java.util.Map;

/**
 * dry-run과 실제 import가 같은 형태로 내는 보고서. 개수는 status/stage 집계로, 손실은 code별
 * 집계로 본다. dead letter는 재실행 전에 사람이 판단해야 하므로 목록으로 노출한다.
 */
public record MigrationReportResponse(MigrationJobResponse job,
                                      Map<String, Long> itemsByStatus,
                                      Map<String, Long> itemsByStage,
                                      List<MigrationIssueSummary> issues,
                                      List<MigrationDeadLetterResponse> deadLetters) {
}
