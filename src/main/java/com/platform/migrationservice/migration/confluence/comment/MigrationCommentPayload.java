package com.platform.migrationservice.migration.confluence.comment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * EXTRACT가 받아 둔 원본 댓글 목록(`migration_payload(COMMENTS)`).
 *
 * 댓글은 페이지가 있어야 매달 수 있고(page_comment.page_id) 페이지는 RESOLVE에서야 생긴다.
 * 그 사이에 원본 응답을 들고 있을 자리가 필요해서 payload에 둔다 — RESOLVE에서 다시 받으면
 * 재시도마다 남의 서버를 또 긁는다.
 *
 * 본문은 이미 우리 마크다운이다. 인라인 댓글의 인용문(`> 원문: "..."`)도 여기서 붙어 들어온다 —
 * 강등 판단은 원본 응답을 보는 이 시점에만 할 수 있고, dry-run도 그 손실을 보고해야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationCommentPayload(int version, List<Entry> comments) {

    /** 지금 쓰는 형식 번호. 바꿀 일이 생기면 올리고 읽는 쪽에서 갈라 본다. */
    public static final int VERSION = 1;

    public MigrationCommentPayload {
        comments = comments == null ? List.of() : List.copyOf(comments);
    }

    public static MigrationCommentPayload of(List<Entry> comments) {
        return new MigrationCommentPayload(VERSION, comments);
    }

    public static MigrationCommentPayload empty() {
        return new MigrationCommentPayload(VERSION, List.of());
    }

    /**
     * @param id         원본 댓글 id — 재실행 멱등의 키다(object map `comment:{id}`)
     * @param parentId   원본 부모 댓글 id. 최상위면 null
     * @param authorName 원본 표시 이름. 우리 계정으로 대조하지 못해도 이름은 남는다
     * @param createdAt  원본 작성 시각(ISO-8601). 저장 뒤 한 번 더 눌러 보존한다
     * @param markdown   우리 마크다운으로 옮긴 본문
     * @param inline     원본에서 본문 구간에 붙어 있던 댓글인가(강등 보고용)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String id, String parentId, String authorName, String authorEmail,
                        String createdAt, String markdown, boolean inline) {
    }
}
