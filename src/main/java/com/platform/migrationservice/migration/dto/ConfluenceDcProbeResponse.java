package com.platform.migrationservice.migration.dto;

/** pageCount는 사이트가 총계를 주지 않으면 null이다 — 화면은 "발견 후 확인"으로 표시한다. */
public record ConfluenceDcProbeResponse(String spaceName, String homepageId, Integer pageCount) {
}
