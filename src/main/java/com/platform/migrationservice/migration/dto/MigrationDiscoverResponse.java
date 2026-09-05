package com.platform.migrationservice.migration.dto;

/**
 * discovered = 원본에서 본 페이지 수, enqueued = 이번에 새로 담은 수,
 * skipped = 이미 담겨 있어 건너뛴 수. 재발견이 멱등이라는 것이 이 세 수로 드러난다.
 */
public record MigrationDiscoverResponse(int discovered, int enqueued, int skipped) {
}
