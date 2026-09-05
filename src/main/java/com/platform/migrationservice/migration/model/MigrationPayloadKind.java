package com.platform.migrationservice.migration.model;

/**
 * 단계 사이에 넘기는 산출물의 종류. EXTRACT→SNAPSHOT, NORMALIZE→IR, MEDIA_COPY→MEDIA_MANIFEST,
 * RESOLVE→MARKDOWN.
 */
public enum MigrationPayloadKind {
    SNAPSHOT,
    IR,
    MARKDOWN,
    /** 스테이징해 둔 첨부 바이트의 좌표 목록(M2). RESOLVE가 이걸 보고 첨부 레코드를 만든다. */
    MEDIA_MANIFEST,
    /** 원본 댓글을 우리 마크다운으로 눕힌 목록(M3). 페이지가 생긴 뒤에야 쓸 수 있어 여기 둔다. */
    COMMENTS,
    /** 최신 N개 이전 버전의 본문(M3). 재시도가 남의 서버를 N번 더 긁지 않게 받아 둔다. */
    HISTORY
}
