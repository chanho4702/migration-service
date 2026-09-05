package com.platform.migrationservice.migration.dto;

import com.platform.migrationservice.migration.model.MigrationSource;

/** 화면에 보여주는 원본 요약. token 필드는 없다 — 한 번 넣은 토큰은 다시 나오지 않는다(기획 P8). */
public record MigrationSourceSummary(String baseUrl, String spaceKey, String spaceName,
                                     int discoveredCount) {

    public static MigrationSourceSummary from(MigrationSource source) {
        return new MigrationSourceSummary(source.getBaseUrl(), source.getSpaceKey(),
                source.getSourceSpaceName(), source.getDiscoveredCount());
    }
}
