package com.platform.migrationservice.migration.dto;

import java.util.Map;

/** 진행률 계산용 집계. 키는 MigrationItemStatus·MigrationStage의 이름 그대로다. */
public record MigrationJobCounts(Map<String, Long> byStatus, Map<String, Long> byStage) {
}
