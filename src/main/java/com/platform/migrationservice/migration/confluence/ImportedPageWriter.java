package com.platform.migrationservice.migration.confluence;

import com.platform.migrationservice.migration.model.MigrationIssueSeverity;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.wiki.WikiImportApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * IR에서 뽑은 마크다운을 실제 위키 문서로 쓴다 — 위키 내부 import API를 거쳐서.
 *
 * 일반 생성 경로(위키의 공개 REST)를 부르지 않는 이유는 그대로다.
 * 1. **알림을 쏘지 않는다** — 500페이지를 옮기면 스페이스 구독자 전원에게 500통이 간다. 이관은
 *    "새 글이 올라왔다"는 사건이 아니라 데이터 이전이다.
 * 2. **자동 구독하지 않는다** — 이관 담당자가 옮긴 문서 전부를 구독하게 되는 건 사고에 가깝다.
 * 3. **시각을 원본 것으로 되돌린다** — 공개 REST로는 created_at/updated_at을 지정할 수 없다.
 *
 * 반대로 검색 색인 이벤트는 위키 쪽에서 **발행된다** — 이 플랫폼에서 색인은 그 이벤트 하나로만
 * 갱신되므로, 빼면 옮긴 문서가 검색에 영영 안 잡힌다.
 *
 * 트랜잭션은 없다. 이 서비스의 DB에는 쓰지 않고 위키에 HTTP로 쓴다 — 요청 하나가 곧 위키 쪽
 * 트랜잭션 하나다.
 */
@Component
@RequiredArgsConstructor
public class ImportedPageWriter {

    /** 원본 제목이 우리 상한을 넘어 잘랐다. */
    public static final String TITLE_TRUNCATED = "TITLE_TRUNCATED";

    /** page.title은 varchar(255)다 — 넘치면 저장 자체가 실패하므로 잘라서라도 옮긴다. */
    public static final int MAX_TITLE_LENGTH = 255;

    private final WikiImportApi wiki;

    /**
     * 새 문서를 만든다.
     *
     * 지난 버전을 함께 넘기면 문서는 k+1번째 버전으로 태어난다(M3 §5.3) — 리비전 1..k가 원본
     * 이력이고 k+1이 현재본이다. 이력은 **최초 이관에만** 쌓는다: 재이관에서 다시 깔면 그 사이
     * 사람이 손댄 리비전과 번호가 엉킨다.
     *
     * @return 만들어진 페이지 id와 그 과정의 손실
     */
    public ImportResult create(long actorId, ImportedPage source) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        String title = truncateTitle(source.title(), source.externalObjectId(), issues);

        List<WikiImportApi.NewRevision> revisions = new ArrayList<>();
        int version = 1;
        for (ImportedRevision revision : source.history()) {
            String revisionTitle = revision.title() == null || revision.title().isBlank()
                    ? title
                    : truncateTitle(revision.title(), source.externalObjectId(), new ArrayList<>());
            revisions.add(new WikiImportApi.NewRevision(version++, revisionTitle, revision.markdown(),
                    null, revision.editorName(), revision.savedAt(), revision.changeNote()));
        }

        WikiImportApi.PageWritten created = wiki.createPage(actorId, new WikiImportApi.NewPage(
                source.spaceId(), source.parentId(), source.type(), title, source.markdown(),
                source.createdAt(), source.updatedAt(),
                // authorId의 **유무**가 곧 "대조됐는가"다(위키 계약). 못 찾았으면 비워 보내고,
                // 위키는 X-Actor-Id(잡 요청자)를 작성자로 눕히면서 아래 이름을 표시로 남긴다.
                source.authorMapped() ? source.authorId() : null,
                source.authorMapped() ? null : source.authorDisplayName(),
                source.authorMapped() ? null : source.sourceUrl(),
                // sortOrder를 주지 않으면 위키가 형제 맨 뒤에 붙인다 — 그것이 M1 규칙(발견 순서)이다.
                source.siblingOrder() == null ? null : source.siblingOrder().longValue(),
                source.labels(), revisions));
        issues.addAll(reported(created, source.externalObjectId()));
        return new ImportResult(created.pageId(), issues);
    }

    /**
     * 이미 옮긴 문서를 원본의 새 버전으로 갱신한다. 새 리비전이 쌓이므로 이관 전 손댄 내용이
     * 사라지지 않고 이력에 남는다.
     *
     * 순번은 따로 보낸다. 위키 계약이 본문 교체와 순서 변경을 나눠 두었고, 문서 이동 경로(부모
     * 재계산·권한 검사)를 타지 않아야 한다 — 여기서 부모는 그대로다.
     */
    public ImportResult update(long actorId, long pageId, ImportedPage source, String changeNote) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        String title = truncateTitle(source.title(), source.externalObjectId(), issues);
        WikiImportApi.PageWritten written = wiki.updatePage(actorId, pageId,
                new WikiImportApi.PageUpdate(title, source.markdown(), source.updatedAt(),
                        source.authorMapped() ? source.authorId() : null,
                        source.authorMapped() ? null : source.authorDisplayName(), changeNote,
                        source.authorMapped() ? null : source.sourceUrl(), source.labels()));
        issues.addAll(reported(written, source.externalObjectId()));
        resequence(actorId, pageId, source.siblingOrder());
        return new ImportResult(pageId, issues);
    }

    /**
     * 위키가 돌려준 손실을 우리 보고서 형식으로 옮긴다. 위치는 우리가 안다 — 우리가 보낸 항목이다.
     */
    private static List<MigrationStageIssue> reported(WikiImportApi.PageWritten written,
                                                      String externalObjectId) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        for (WikiImportApi.WriteIssue issue : written.issues()) {
            MigrationIssueSeverity severity;
            try {
                severity = MigrationIssueSeverity.valueOf(issue.severity());
            } catch (IllegalArgumentException | NullPointerException exception) {
                // 모르는 심각도는 경고로 둔다 — 등급 하나 때문에 손실 자체를 버리지 않는다.
                severity = MigrationIssueSeverity.WARNING;
            }
            issues.add(new MigrationStageIssue(severity, issue.code(), "page:" + externalObjectId));
        }
        return issues;
    }

    /**
     * 첨부를 등록한 뒤 본문의 참조만 실제 URL로 바꿔 넣는다(M2 §4.1).
     *
     * 새 리비전을 만들지 않는다. 첨부 참조 정리는 이관이라는 한 번의 저장을 끝맺는 일이지 별도의
     * 편집이 아니고, 리비전을 하나 더 쌓으면 옮겨온 문서마다 "v2 수정됨"이 생겨 이력이 거짓이 된다.
     * 위키는 방금 쓴 리비전의 본문도 같이 눌러 현재와 이력을 일치시킨다.
     */
    public void rewriteBody(long actorId, long pageId, String markdown) {
        wiki.replaceContent(actorId, pageId, markdown);
    }

    /**
     * 순번만 갱신한다(M2 §4.4). 원본에서 문서 순서만 바뀐 재이관이 여기로 온다 — 본문이 그대로라
     * 리비전도 이벤트도 만들 이유가 없다.
     */
    public void resequence(long actorId, long pageId, Integer siblingOrder) {
        if (siblingOrder == null) {
            return;
        }
        wiki.reorder(actorId, pageId, siblingOrder.longValue());
    }

    private String truncateTitle(String title, String externalObjectId, List<MigrationStageIssue> issues) {
        String value = title == null || title.isBlank() ? "제목 없음" : title.trim();
        if (value.length() <= MAX_TITLE_LENGTH) {
            return value;
        }
        issues.add(MigrationStageIssue.warning(TITLE_TRUNCATED, "page:" + externalObjectId));
        return value.substring(0, MAX_TITLE_LENGTH);
    }

    /**
     * 한 문서를 쓰는 데 필요한 값. authorId는 이미 우리 사용자로 결정된 값이고(대조 실패 시
     * 잡 요청자), authorDisplayName은 원본에 적혀 있던 이름 그대로다.
     */
    public record ImportedPage(long spaceId, Long parentId, String externalObjectId, String title,
                               String markdown, long authorId, String authorDisplayName,
                               /** 원본 작성자를 우리 계정으로 찾았는가. 못 찾았으면 이름·원본 주소를 문서에 남긴다(M3). */
                               boolean authorMapped,
                               String sourceUrl,
                               Instant createdAt, Instant updatedAt, List<String> labels,
                               Integer siblingOrder,
                               /** 원본이 블로그 글이면 BLOG(M3 §5.1). 트리에 넣으면 날짜순 글이 폴더 밑에 박힌다. */
                               WikiImportApi.ImportedPageType type,
                               /** 함께 옮길 지난 버전. 오래된 것부터다. 비어 있으면 현재본만 남는다. */
                               List<ImportedRevision> history) {

        public ImportedPage {
            labels = labels == null ? List.of() : List.copyOf(labels);
            history = history == null ? List.of() : List.copyOf(history);
            type = type == null ? WikiImportApi.ImportedPageType.PAGE : type;
        }
    }

    /** 원본의 지난 버전 하나. 리비전 번호는 writer가 1부터 다시 매긴다. */
    public record ImportedRevision(String title, String markdown, String editorName, String changeNote,
                                   Instant savedAt) {
    }

    public record ImportResult(long pageId, List<MigrationStageIssue> issues) {
    }
}
