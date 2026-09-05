package com.platform.migrationservice.migration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 연결 확인 요청. token은 요청 본문으로만 들어오고 어떤 응답에도 되돌아 나가지 않는다(기획 P8). */
public record ConfluenceDcProbeRequest(
        @NotBlank @Size(max = 512) String baseUrl,
        @NotBlank @Size(max = 255) String spaceKey,
        @NotBlank @Size(max = 4096) String token) {
}
