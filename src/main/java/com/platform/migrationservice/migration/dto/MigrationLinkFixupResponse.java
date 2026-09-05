package com.platform.migrationservice.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 링크 정리 재실행 결과.
 *
 * 다시 눌러도 안전하다 — 이미 정리된 문서에는 임시 링크가 남아 있지 않아 손대지 않는다.
 * 그래서 두 번째 실행의 touched는 대개 0이고, 그것이 "고칠 것이 없다"는 뜻이다.
 */
@Schema(description = "링크 정리 재실행 결과. 이미 정리된 문서는 건너뛰므로 다시 눌러도 안전하다.")
public record MigrationLinkFixupResponse(
        @Schema(description = "잡 id", example = "12") long jobId,
        @Schema(description = "본문이 실제로 바뀐 문서 수", example = "3") int touched,
        @Schema(description = "이번에도 정리하지 못한 문서 수", example = "0") int failed) {
}
