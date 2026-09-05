package com.platform.migrationservice.migration.confluence.handler;

/** 컨플루언스 DC 단계가 보고하는 손실·경고 코드. 손실 보고서의 집계 키가 된다. */
public final class ConfluenceDcIssues {

    /** enqueue 때 본 버전과 실제로 읽은 버전이 다르다 — 발견 뒤 원본이 수정됐다. */
    public static final String SOURCE_VERSION_DRIFT = "SOURCE_VERSION_DRIFT";

    /** 첨부 본체를 옮기지 못했다. 본문의 참조는 남지만 파일은 없다. */
    public static final String ATTACHMENT_NOT_COPIED = "ATTACHMENT_NOT_COPIED";

    /** dry-run이 "이 파일을 이만큼 옮길 예정"이라고 알린다(INFO). 손실이 아니다. */
    public static final String ATTACHMENT_PLANNED = "ATTACHMENT_PLANNED";

    /** 파일 하나가 상한(platform.migration.dc.max-attachment-bytes)을 넘어 건너뛰었다. */
    public static final String ATTACHMENT_TOO_LARGE = "ATTACHMENT_TOO_LARGE";

    /** 본문이 가리키는 파일명에 해당하는 첨부 레코드를 못 찾아 참조를 그대로 두었다. */
    public static final String ATTACHMENT_REF_UNRESOLVED = "ATTACHMENT_REF_UNRESOLVED";

    /** 원본 사이트 링크가 가리키는 문서를 끝내 못 찾아 원본 절대 URL로 되돌렸다. */
    public static final String LINK_UNRESOLVED = "LINK_UNRESOLVED";

    /** 제목으로 찾은 문서가 대상 스페이스에 여럿이라 어느 것인지 정할 수 없었다. */
    public static final String LINK_AMBIGUOUS = "LINK_AMBIGUOUS";

    /** 링크의 앵커가 대상 문서의 헤딩 어느 것과도 맞지 않아 앵커만 떼었다. */
    public static final String ANCHOR_DROPPED = "ANCHOR_DROPPED";

    /**
     * 원본 제한의 사용자·그룹을 우리 계정으로 대조하지 못했다. 공개로 풀지 않고 잡 요청자
     * 단독 제한으로 닫는다(ADR-W14-07 fail-closed) — 잘못 열린 문서가 잘못 잠긴 문서보다 나쁘다.
     */
    public static final String RESTRICTION_PRINCIPAL_UNMAPPED = "RESTRICTION_PRINCIPAL_UNMAPPED";

    /** 조상의 대상 페이지를 찾지 못해 루트에 두었다. */
    public static final String PARENT_NOT_FOUND = "PARENT_NOT_FOUND";

    /** 원본 작성자를 우리 사용자로 대조하지 못했다 — 잡 요청자를 작성자로 쓴다(기획 P2는 M3). */
    public static final String AUTHOR_UNMAPPED = "AUTHOR_UNMAPPED";

    /** IR이 우리 계약을 벗어났다. 재시도해도 같으므로 항목을 데드레터로 보낸다. */
    public static final String IR_INVALID = "IR_INVALID";

    /** 원본 스냅샷을 IR로 옮기지 못했다(잘린 XHTML·알 수 없는 구조). */
    public static final String SNAPSHOT_INVALID = "SNAPSHOT_INVALID";

    /**
     * 원본의 인라인 댓글을 페이지 댓글로 내렸다(M3, 기획 P5). 원본 앵커는 원본이 렌더한 본문
     * 기준이라 우리 본문에서 같은 구간을 다시 찾을 수 없다 — 대신 인용을 본문 앞에 남긴다.
     */
    public static final String INLINE_COMMENT_DEMOTED = "INLINE_COMMENT_DEMOTED";

    /** 댓글 본문을 우리 포맷으로 옮기지 못해 그 댓글만 건너뛰었다. */
    public static final String COMMENT_NOT_MIGRATED = "COMMENT_NOT_MIGRATED";

    /**
     * 원본의 답글의 답글을 최상위 답글로 폈다. 우리 댓글은 중첩이 1단이고, 깊이를 살리려고
     * 대화를 쪼개는 것보다 한 단으로 눕히는 편이 읽힌다.
     */
    public static final String COMMENT_REPLY_FLATTENED = "COMMENT_REPLY_FLATTENED";

    /** 지난 버전 하나가 상한을 넘거나 원본에서 사라져 이력에 넣지 못했다(M3 §5.3). */
    public static final String HISTORY_VERSION_SKIPPED = "HISTORY_VERSION_SKIPPED";

    /** 대조 실패 — 옮긴 결과가 원본과 다르다. */
    public static final String VERIFY_PAGE_MISSING = "VERIFY_PAGE_MISSING";
    public static final String VERIFY_TITLE_MISMATCH = "VERIFY_TITLE_MISMATCH";
    /** 블로그 글이 일반 문서로(또는 그 반대로) 들어갔다 — 트리와 블로그 목록 중 한쪽에서 사라진다. */
    public static final String VERIFY_TYPE_MISMATCH = "VERIFY_TYPE_MISMATCH";
    public static final String VERIFY_BODY_EMPTY = "VERIFY_BODY_EMPTY";
    public static final String VERIFY_LABEL_MISMATCH = "VERIFY_LABEL_MISMATCH";
    /**
     * 받아 둔 첨부가 대상 문서에 없다. 업로드가 실패했거나 사람이 지웠다 — 어느 쪽이든
     * "옮겼는데 파일이 없다"이므로 보고서에 남는다.
     */
    public static final String VERIFY_ATTACHMENT_MISMATCH = "VERIFY_ATTACHMENT_MISMATCH";
    /** 옮기려던 댓글 수보다 문서의 댓글이 적다 — 대화 일부가 넘어오지 못했다. */
    public static final String VERIFY_COMMENT_COUNT_MISMATCH = "VERIFY_COMMENT_COUNT_MISMATCH";
    public static final String VERIFY_MARKDOWN_MISSING = "VERIFY_MARKDOWN_MISSING";

    /** 이 job에 원본 접속 정보가 없다 — 잡 생성이 잘못됐다. */
    public static final String SOURCE_MISSING = "MIGRATION_SOURCE_MISSING";

    private ConfluenceDcIssues() {
    }
}
