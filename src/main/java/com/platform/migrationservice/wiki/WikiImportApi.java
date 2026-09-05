package com.platform.migrationservice.wiki;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 위키 내부 import API(`/internal/wiki/import`)의 자바 쪽 얼굴.
 *
 * 이 인터페이스가 이관 엔진과 위키 사이의 **유일한** 접점이다 — 엔진은 위키 DB도 저장소도
 * 만지지 않는다. 모든 호출은 잡 요청자를 {@code X-Actor-Id}로 싣고, 위키는 그 명의로 쓴다.
 *
 * 계약의 정본은 설계 문서 §2다. 여기 메서드 하나가 거기 표의 한 줄이다.
 */
public interface WikiImportApi {

    /** 새 문서. 지난 버전을 함께 주면 1..k로 깔고 현재본이 k+1이 된다. */
    PageWritten createPage(long actorId, NewPage page);

    /** 이미 옮긴 문서를 원본의 새 버전으로 갱신한다 — 새 리비전 1건이 쌓인다. */
    PageWritten updatePage(long actorId, long pageId, PageUpdate update);

    /**
     * 버전을 올리지 않고 본문만 갈아 끼운다(첨부 참조 정리). 현재 리비전의 본문도 함께 바뀐다 —
     * 이관은 한 번의 저장이고, 리비전을 하나 더 쌓으면 옮긴 문서마다 "v2 수정됨"이 생긴다.
     */
    void replaceContent(long actorId, long pageId, String content);

    /** 새 리비전을 만들며 본문을 바꾼다(잡 마무리의 링크 정리). */
    void rewriteContent(long actorId, long pageId, String content, String changeNote);

    /** 형제 순번만 갱신한다 — 본문이 그대로면 리비전도 이벤트도 만들 이유가 없다. */
    void reorder(long actorId, long pageId, long sortOrder);

    /**
     * 스테이징해 둔 파일을 문서의 첨부로 올린다(스트리밍).
     *
     * 같은 이름·같은 checksum이면 위키가 {@code UNCHANGED}로 답하고 새 버전을 쌓지 않는다 —
     * 재이관이 같은 파일로 버전만 늘리는 것을 막는 자리가 여기다.
     */
    UploadedAttachment uploadAttachment(long actorId, long pageId, Path file, String filename,
                                        String contentType, String checksum, Integer sourceVersion);

    /** 원본 댓글 한 건. 알림·자동 구독은 발행되지 않는다. */
    long createComment(long actorId, long pageId, NewComment comment);

    /** 이미 옮긴 댓글이 아직 살아 있는가(사람이 지웠으면 false). */
    boolean commentExists(long actorId, long commentId);

    /**
     * 보기·편집 제한을 통째로 갈아 끼운다. 위키는 권한 검사도 자기 잠금 방지도 하지 않는다 —
     * fail-closed 판정은 엔진이 이미 끝냈다(ADR-W14-07).
     */
    void replaceRestrictions(long actorId, long pageId, List<RestrictionPrincipal> view,
                             List<RestrictionPrincipal> edit);

    /** 대조용 조회. 문서가 없으면 빈 값 — VERIFY가 그것을 ERROR로 보고한다. */
    Optional<ImportedPageView> getPage(long actorId, long pageId);

    /** 제목으로 문서 찾기(링크 해석 보조). 같은 제목이 여럿이면 여러 건이 온다. */
    List<PageRef> findPagesByTitle(long actorId, long spaceId, String title);

    /** 대상 스페이스 존재·이름. 없으면 빈 값. */
    Optional<SpaceRef> getSpace(long actorId, long spaceId);

    // -- 계약 본문 ---------------------------------------------------

    /**
     * 위키 문서 종류. 원본이 blogpost면 BLOG다 — 트리에 넣으면 날짜순 글이 폴더 밑에 박힌다.
     * JSON 계약은 소문자다(`page`/`blog`) — 위키의 {@code PageType}이 그렇게 직렬화한다.
     */
    enum ImportedPageType {
        PAGE, BLOG;

        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 첨부 업로드 결과의 판정. 같은 이름·같은 checksum이면 UNCHANGED다. */
    enum AttachmentOutcome { CREATED, NEW_VERSION, UNCHANGED }

    /**
     * 새 문서 한 건.
     *
     * @param authorId           우리 계정으로 대조된 작성자. 못 찾았으면 잡 요청자다.
     * @param importedAuthorName 대조하지 **못했을 때만** 채운다 — 채워 두면 화면이 계속 "이관됨"으로 보인다.
     */
    record NewPage(long spaceId, Long parentId, ImportedPageType type, String title, String content,
                   Instant createdAt, Instant updatedAt, Long authorId, String importedAuthorName,
                   String sourceUrl, Long sortOrder, List<String> labels, List<NewRevision> revisions) {

        public NewPage {
            labels = labels == null ? List.of() : List.copyOf(labels);
            revisions = revisions == null ? List.of() : List.copyOf(revisions);
            type = type == null ? ImportedPageType.PAGE : type;
        }
    }

    /** 원본의 지난 버전 하나. 번호는 오래된 것부터 1..k다. */
    record NewRevision(int version, String title, String content, Long editorId, String editorName,
                       Instant savedAt, String changeNote) {
    }

    /**
     * 쓰기 결과. issues는 **위키 쪽에서 난 손실**이다(제목 잘림 등) — 엔진이 자기 보고서로
     * 옮겨 적는다. 여기서 버리면 "옮겼는데 제목이 잘렸다"를 아무도 모른다.
     */
    record PageWritten(long pageId, int version, List<WriteIssue> issues) {

        public PageWritten {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    /** 위키가 보고한 손실 하나. sourcePath는 엔진이 알고 있으므로(자기가 보낸 항목이다) 오지 않는다. */
    record WriteIssue(String severity, String code) {
    }

    /**
     * 재이관 — 제목·본문을 갱신하고 updatedAt은 원본 것으로 되돌린다.
     *
     * 순번은 여기 없다. 위키 계약이 본문 교체와 순서 변경을 나눠 두었고(리비전이 쌓이는 쓰기와
     * 아닌 쓰기다), 순서만 필요할 때 새 리비전이 생기면 안 된다 — 순번은 `PUT /order`로 따로 간다.
     */
    record PageUpdate(String title, String content, Instant updatedAt, Long editorId,
                      String editorName, String changeNote, String sourceUrl, List<String> labels) {

        public PageUpdate {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    record UploadedAttachment(long attachmentId, String inlineUrl, String downloadUrl,
                              AttachmentOutcome outcome) {
    }

    /** @param authorName 대조하지 못했을 때만 채운다(대조된 댓글은 우리 사용자로 보여야 한다). */
    record NewComment(Long parentCommentId, Long authorId, String authorName, String body,
                      Instant createdAt) {
    }

    /** 제한 주체 한 건. type은 USER 또는 TEAM. */
    record RestrictionPrincipal(String type, long id) {
    }

    /** 대조용 응답 — 본문 전체가 아니라 길이만 온다(옮긴 것이 통째로 날아갔는지만 본다). */
    record ImportedPageView(long pageId, long spaceId, Long parentId, String title,
                            ImportedPageType type, int contentLength, int version, long sortOrder,
                            List<String> labels, List<AttachmentView> attachments, long commentCount) {

        public ImportedPageView {
            labels = labels == null ? List.of() : List.copyOf(labels);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    record AttachmentView(long id, String filename, String checksum) {
    }

    /** 제목으로 찾은 문서. 응답에 스페이스가 없어(질의한 스페이스 안에서만 찾는다) 그 값을 그대로 채운다. */
    record PageRef(long pageId, long spaceId, String title) {
    }

    record SpaceRef(long spaceId, String key, String name) {
    }
}
