package com.platform.migrationservice.migration.dto;

import java.util.List;

/** 실패 항목 표의 한 페이지. total은 필터를 적용한 뒤의 전체 건수다. */
public record MigrationItemPageResponse(List<MigrationItemResponse> items, int page, int size, long total) {
}
