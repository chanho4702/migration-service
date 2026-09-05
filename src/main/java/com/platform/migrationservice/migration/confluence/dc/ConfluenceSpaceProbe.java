package com.platform.migrationservice.migration.confluence.dc;

/** 연결 확인 결과. pageCount는 사이트가 총계를 주지 않으면 null이다. */
public record ConfluenceSpaceProbe(String spaceName, String homepageId, Integer pageCount) {
}
