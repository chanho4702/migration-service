package com.platform.migrationservice.migration.dto;

import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * sourceInstanceId는 이제 선택이다 — CONFLUENCE_DC는 원본 주소의 호스트가 곧 인스턴스 식별자라
 * 관리자에게 같은 값을 두 번 묻지 않는다. 비워 보내면 서버가 source.baseUrl에서 채운다.
 */
public record MigrationJobCreateRequest(
        @NotNull MigrationProvider provider,
        @Size(max = 255) String sourceInstanceId,
        @NotNull Long targetSpaceId,
        @NotNull MigrationJobMode mode,
        @Valid MigrationSourceRequest source) {
}
