package com.platform.migrationservice.migration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** job 생성 시 함께 저장하는 원본 접속 정보. provider=CONFLUENCE_DC면 필수다. */
public record MigrationSourceRequest(
        @NotBlank @Size(max = 512) String baseUrl,
        @NotBlank @Size(max = 255) String spaceKey,
        @NotBlank @Size(max = 4096) String token) {
}
