package com.platform.migrationservice.migration.confluence.dc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * DC 추출 파라미터.
 *
 * maxAttachmentBytes 기본값 100MB는 "한 파일을 통째로 메모리에 담아도 되는 크기"의 상한이다 —
 * 더 큰 파일은 옮기지 않고 보고서에 남긴다(ATTACHMENT_TOO_LARGE). 스트리밍 복사로 바꾸기 전에
 * 이 값을 올리면 워커가 OOM으로 죽는다.
 *
 * readTimeout은 worker lease(기본 5분)보다 짧아야 한다 — 더 길면 handler가 아직 응답을 기다리는
 * 동안 다른 노드가 같은 item을 회수해 같은 페이지를 두 번 긁는다.
 */
@Component
public record ConfluenceDcProperties(
        @Value("${platform.migration.dc.connect-timeout:PT10S}") Duration connectTimeout,
        @Value("${platform.migration.dc.read-timeout:PT60S}") Duration readTimeout,
        @Value("${platform.migration.dc.max-pages:5000}") int maxPages,
        @Value("${platform.migration.dc.page-size:100}") int pageSize,
        @Value("${platform.migration.dc.child-page-size:200}") int childPageSize,
        @Value("${platform.migration.dc.max-attachment-bytes:104857600}") long maxAttachmentBytes,
        /**
         * 함께 옮길 지난 버전 수(M3, 기획 P3 기본 10). 0이면 현재본만 옮긴다.
         * 버전 하나가 원본 왕복 한 번이라, 500페이지 × 10버전이면 5000번을 더 부른다 —
         * 올릴 때는 원본 사이트가 감당할 수 있는지부터 본다.
         */
        @Value("${platform.migration.dc.history-versions:10}") int historyVersions,
        /** 지난 버전 본문 하나의 상한. 넘으면 그 버전만 건너뛴다(HISTORY_VERSION_SKIPPED). */
        @Value("${platform.migration.dc.max-history-version-bytes:2097152}") long maxHistoryVersionBytes,
        /** 댓글 목록 한 묶음 크기. 페이지 목록과 같은 DC limit 상한을 받는다. */
        @Value("${platform.migration.dc.comment-page-size:100}") int commentPageSize) {

    public ConfluenceDcProperties {
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be at least 1");
        }
        if (pageSize < 1 || pageSize > 200) {
            // DC는 limit 상한을 사이트 설정으로 두는데, 200을 넘겨 보내면 조용히 잘려 페이지네이션
            // 종료 판정(size < limit)이 영원히 성립하지 않는다.
            throw new IllegalArgumentException("pageSize must be between 1 and 200");
        }
        if (childPageSize < 1 || childPageSize > 200) {
            // 자식 목록도 같은 상한을 받는다 — 넘겨 보내면 조용히 잘려 형제 순서의 뒷부분이 빈다.
            throw new IllegalArgumentException("childPageSize must be between 1 and 200");
        }
        if (maxAttachmentBytes < 1) {
            throw new IllegalArgumentException("maxAttachmentBytes must be positive");
        }
        if (historyVersions < 0) {
            throw new IllegalArgumentException("historyVersions must not be negative");
        }
        if (maxHistoryVersionBytes < 1) {
            throw new IllegalArgumentException("maxHistoryVersionBytes must be positive");
        }
        if (commentPageSize < 1 || commentPageSize > 200) {
            // 댓글 목록도 같은 상한을 받는다 — 넘겨 보내면 조용히 잘려 뒷부분 댓글이 사라진다.
            throw new IllegalArgumentException("commentPageSize must be between 1 and 200");
        }
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
    }
}
