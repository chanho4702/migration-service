package com.platform.migrationservice.migration.confluence.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.MigrationPayloadStore;
import com.platform.migrationservice.migration.confluence.ImportedPageWriter;
import com.platform.migrationservice.migration.confluence.comment.MigrationCommentImporter;
import com.platform.migrationservice.migration.confluence.history.MigrationHistoryPayload;
import com.platform.migrationservice.migration.confluence.link.MigrationLinkResolver;
import com.platform.migrationservice.migration.confluence.link.MigrationLinkRewriter;
import com.platform.migrationservice.migration.confluence.media.MigrationAttachmentImporter;
import com.platform.migrationservice.migration.confluence.restriction.MigrationPrincipalResolver;
import com.platform.migrationservice.migration.confluence.restriction.MigrationRestrictionApplier;
import com.platform.migrationservice.migration.ir.DocumentIrMarkdownContext;
import com.platform.migrationservice.migration.ir.DocumentIrMarkdownResult;
import com.platform.migrationservice.migration.ir.DocumentIrMarkdownWriter;
import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationSource;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import com.platform.migrationservice.migration.repository.MigrationSourceRepository;
import com.platform.migrationservice.migration.worker.MigrationObjectMappingWriter;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.migration.worker.MigrationStageHandler;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.migration.worker.MigrationStageOutcome;
import com.platform.migrationservice.migration.worker.MigrationStageWork;
import com.platform.migrationservice.wiki.WikiImportApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * IR → 마크다운 → (import면) 위키 import API로 실제 문서.
 *
 * 재실행 규칙이 여기에 있다. 같은 원본을 같은 상태로 다시 만나면 **아무것도 다시 쓰지 않고** 이미
 * 만든 문서 id를 돌려준다(S1 멱등). 원본이 바뀌었으면 새 리비전으로 갱신한다 — 지우고 다시 만들면
 * 링크·별표·댓글이 딸려 사라진다.
 *
 * M2에서 이 단계가 하는 일이 셋 늘었다.
 * 1. **링크 재작성** — 문서를 쓰기 전에 원본 사이트 URL을 우리 주소로 바꾼다. 아직 안 옮긴 문서를
 *    가리키면 임시 스킴으로 남기고 잡 마무리 pass가 마저 잇는다.
 * 2. **첨부 등록** — MEDIA_COPY가 받아 둔 파일을 이 문서의 첨부로 올리고, 본문 참조를 위키가 준
 *    URL로 바꾼다. 페이지가 있어야 첨부를 올릴 수 있어 순서가 "문서 → 첨부 → 본문 정리"가 된다.
 * 3. **제한 적용** — 원본의 보기·편집 제한을 옮긴다. 대조 실패는 공개가 아니라 잠금이다(fail-closed).
 *
 * M3에서 다시 셋이 늘었다.
 * 4. **블로그 글** — 원본 type이 blogpost면 우리 BLOG로 쓴다. 부모도 트리 순번도 없다.
 * 5. **지난 버전** — EXTRACT가 받아 둔 이력을 리비전 1..k로 깔고 현재본을 k+1로 둔다(최초 이관만).
 * 6. **댓글** — 문서·첨부·제한이 자리를 잡은 뒤에 단다. 재이관은 이미 단 댓글을 건너뛴다.
 */
@Component
@RequiredArgsConstructor
public class ConfluenceDcResolveHandler implements MigrationStageHandler {

    private final MigrationPayloadStore payloads;
    private final DocumentIrMarkdownWriter markdownWriter;
    private final ImportedPageWriter pageWriter;
    private final MigrationLinkRewriter linkRewriter;
    private final MigrationAttachmentImporter attachmentImporter;
    private final MigrationRestrictionApplier restrictionApplier;
    private final MigrationPrincipalResolver principals;
    private final MigrationCommentImporter commentImporter;
    private final MigrationObjectMappingWriter objectMappings;
    private final MigrationObjectMappingRepository mappings;
    private final MigrationSourceRepository sources;
    private final WikiImportApi wiki;
    private final ObjectMapper objectMapper;

    @Override
    public MigrationProvider provider() {
        return MigrationProvider.CONFLUENCE_DC;
    }

    @Override
    public MigrationStage stage() {
        return MigrationStage.RESOLVE;
    }

    @Override
    public MigrationStageOutcome handle(MigrationStageWork work) {
        JsonNode ir = parse(payloads.require(work.itemId(), MigrationPayloadKind.IR).body());
        MigrationSource source = sources.findById(work.jobId()).orElse(null);
        DocumentIrMarkdownContext context = source == null
                ? DocumentIrMarkdownContext.none()
                : new DocumentIrMarkdownContext(source.getSpaceKey(), source.getBaseUrl());

        DocumentIrMarkdownResult rendered = markdownWriter.write(ir, context);
        payloads.write(work.itemId(), MigrationPayloadKind.MARKDOWN, rendered.markdown());
        List<MigrationStageIssue> issues = new ArrayList<>(rendered.issues());

        if (work.dryRun()) {
            // dry-run은 쓰기 0건이 약속이다(M-02). 여기서 멈추고 보고서만 남긴다.
            return MigrationStageOutcome.ok(issues);
        }

        JsonNode snapshot = parse(payloads.require(work.itemId(), MigrationPayloadKind.SNAPSHOT).body())
                .path("content");
        long actorId = work.requestedBy();

        Optional<MigrationObjectMapping> existing = mappings.findBySourceKey(
                MigrationObjectMapping.sourceKeyFor(work.provider(), work.sourceInstanceId(),
                        work.externalObjectId()));
        Long existingPageId = existing.map(MigrationObjectMapping::getTargetPageId)
                .filter(pageId -> pageId != null && wiki.getPage(actorId, pageId).isPresent())
                .orElse(null);

        if (existingPageId != null
                && work.sourceChecksum().equals(existing.orElseThrow().getSourceChecksum())) {
            // 원본도 그대로고 대상 문서도 살아 있다. 손대지 않는 것이 정답이다 — 다시 쓰면 아무것도
            // 안 바뀐 리비전이 쌓이고 "수정됨" 알림 대상이 늘어난다. 다만 형제 순서는 본문과 무관하게
            // 바뀔 수 있어(checksum은 id+버전이다) 순번만 따로 맞춘다.
            pageWriter.resequence(actorId, existingPageId, work.siblingOrder());
            return MigrationStageOutcome.page(existingPageId, issues);
        }

        MigrationLinkResolver.Context linkContext = new MigrationLinkResolver.Context(work.provider(),
                work.sourceInstanceId(), source == null ? null : source.getBaseUrl(),
                work.targetSpaceId(), actorId, false);
        MigrationLinkRewriter.Result linked =
                linkRewriter.rewriteSourceLinks(rendered.markdown(), linkContext);
        issues.addAll(linked.issues());
        // 산출물은 "이 문서에 마지막으로 쓴 본문"이어야 한다. 잡 마무리의 링크 정리가 이 값에서
        // 임시 링크(dc-page:)를 찾으므로, 여기서 갱신하지 않으면 정리 pass가 아무것도 못 찾는다.
        payloads.write(work.itemId(), MigrationPayloadKind.MARKDOWN, linked.markdown());

        ImportedPageWriter.ImportedPage page = toImportedPage(work, source, snapshot,
                linked.markdown(), issues);
        long pageId;
        if (existingPageId != null) {
            String note = "컨플루언스 재이관 v" + snapshot.path("version").path("number").asInt(1);
            ImportedPageWriter.ImportResult result =
                    pageWriter.update(actorId, existingPageId, page, note);
            pageId = result.pageId();
            issues.addAll(result.issues());
        } else {
            ImportedPageWriter.ImportResult result = pageWriter.create(actorId, page);
            pageId = result.pageId();
            issues.addAll(result.issues());
        }

        // 문서가 생긴 **직후** 원장에 못박는다. 아래 첨부·제한·댓글은 위키가 잠깐 죽기만 해도
        // 재시도 실패로 올라오는데, 그때 원장이 비어 있으면 재시도가 같은 원본으로 문서를 하나
        // 더 만든다. checksum은 아직 넣지 않으므로 재시도는 이 문서를 갱신 경로로 마저 마무리한다.
        objectMappings.bindTargetPage(work.provider(), work.sourceInstanceId(),
                work.externalObjectId(), pageId, work.jobId());

        issues.addAll(attachBody(work, pageId, linked.markdown()));
        issues.addAll(restrictionApplier.apply(snapshot, pageId, actorId));
        // 댓글은 문서·첨부·제한이 모두 자리를 잡은 뒤에 단다(M3 §5.2). 제한을 먼저 걸어야
        // 옮긴 대화가 원본과 같은 사람들에게만 보인다.
        issues.addAll(commentImporter.importComments(work, pageId, actorId));

        // 마무리 — 여기서야 checksum이 들어가고, 그때부터 재실행이 "이미 옮겼다"로 건너뛴다.
        // worker는 DONE에 닿을 때 한 번 더 부르는데(멱등), 그 사이의 VERIFY가 실패해 재시도되면
        // 이 항목의 자식들이 부모를 못 찾는다.
        objectMappings.upsert(work.provider(), work.sourceInstanceId(), work.externalObjectId(),
                work.sourceVersion(), work.sourceChecksum(), pageId, work.jobId());
        return MigrationStageOutcome.page(pageId, issues);
    }

    /**
     * 첨부를 이 문서에 올리고 본문의 `attachment:{파일명}` 참조를 실제 주소로 바꾼다.
     *
     * 문서를 이미 쓴 뒤에야 할 수 있는 일이다 — 첨부는 페이지에 매달리고, 본문 참조는 그 첨부의
     * id로 걸린다. 본문을 다시 누르되 새 리비전은 만들지 않는다(같은 저장의 마무리다).
     */
    private List<MigrationStageIssue> attachBody(MigrationStageWork work, long pageId, String markdown) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        long actorId = work.requestedBy();
        MigrationAttachmentImporter.Registered registered =
                attachmentImporter.register(work.itemId(), pageId, actorId);
        issues.addAll(registered.issues());

        MigrationAttachmentImporter.Rewritten rewritten =
                attachmentImporter.rewrite(markdown, registered);
        issues.addAll(rewritten.issues());
        if (!rewritten.markdown().equals(markdown)) {
            pageWriter.rewriteBody(actorId, pageId, rewritten.markdown());
            payloads.write(work.itemId(), MigrationPayloadKind.MARKDOWN, rewritten.markdown());
        }
        return issues;
    }

    private ImportedPageWriter.ImportedPage toImportedPage(MigrationStageWork work,
                                                           MigrationSource source, JsonNode snapshot,
                                                           String markdown,
                                                           List<MigrationStageIssue> issues) {
        WikiImportApi.ImportedPageType type = pageTypeOf(snapshot);
        // 블로그 글은 트리 밖에 산다(M3 §5.1) — 부모를 찾을 필요도, 둘 자리도 없다.
        Long parentId = type == WikiImportApi.ImportedPageType.BLOG
                ? null
                : resolveParent(work, snapshot, issues);
        JsonNode createdBy = snapshot.path("history").path("createdBy");
        String displayName = createdBy.path("displayName").asText("");
        // 원본 작성자를 우리 계정으로 찾는다(org proto LookupMembers). 찾으면 그 사람이 문서의
        // 작성자·수정자다. 못 찾으면 계정을 새로 만들지 않는 것이 이 모듈의 전제라(기획 §2 제외)
        // 잡 요청자를 작성자로 두고 원본 이름을 imported_author_name과 리비전 편집자 이름으로
        // 남긴다(M3 §5.4).
        Optional<Long> mappedAuthor = principals.resolveUser(new MigrationPrincipalResolver.SourceUser(
                createdBy.path("username").asText(""), displayName,
                createdBy.path("email").asText("")));
        if (mappedAuthor.isEmpty()) {
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.AUTHOR_UNMAPPED,
                    "user:" + (displayName.isBlank() ? "unknown" : displayName)));
        }

        List<String> labels = new ArrayList<>();
        for (JsonNode label : snapshot.path("metadata").path("labels").path("results")) {
            String name = label.path("name").asText("");
            if (!name.isBlank()) {
                labels.add(name);
            }
        }
        Instant createdAt = parseInstant(snapshot.path("history").path("createdDate").asText(""));
        Instant updatedAt = parseInstant(snapshot.path("version").path("when").asText(""));
        return new ImportedPageWriter.ImportedPage(work.targetSpaceId(), parentId,
                work.externalObjectId(), snapshot.path("title").asText(""), markdown,
                mappedAuthor.orElseGet(work::requestedBy), displayName, mappedAuthor.isPresent(),
                sourceUrlOf(source, work),
                createdAt, updatedAt, labels, work.siblingOrder(), type,
                importedHistory(work));
    }

    /** 원본이 블로그 글이면 BLOG. 그 밖은 전부 일반 문서다(폴더는 원본에 없다). */
    private static WikiImportApi.ImportedPageType pageTypeOf(JsonNode snapshot) {
        return "blogpost".equalsIgnoreCase(snapshot.path("type").asText(""))
                ? WikiImportApi.ImportedPageType.BLOG
                : WikiImportApi.ImportedPageType.PAGE;
    }

    /**
     * 원본 문서로 가는 주소(M3 §5.4). 원본 응답의 `_links`를 따라가지 않고 고정 패턴으로 만든다 —
     * 화면의 링크지만 우리가 만든 주소만 내보낸다는 규칙은 여기서도 같다.
     */
    private static String sourceUrlOf(MigrationSource source, MigrationStageWork work) {
        if (source == null || source.getBaseUrl() == null || source.getBaseUrl().isBlank()) {
            return null;
        }
        return source.getBaseUrl() + "/pages/viewpage.action?pageId=" + work.externalObjectId();
    }

    /** EXTRACT가 받아 둔 지난 버전(M3 §5.3). 오래된 것부터 그대로 넘긴다. */
    private List<ImportedPageWriter.ImportedRevision> importedHistory(MigrationStageWork work) {
        return payloads.read(work.itemId(), MigrationPayloadKind.HISTORY)
                .map(payload -> {
                    MigrationHistoryPayload history;
                    try {
                        history = objectMapper.readValue(payload.body(), MigrationHistoryPayload.class);
                    } catch (JsonProcessingException exception) {
                        // 형식을 못 읽으면 이력 없이 간다 — 현재본을 못 옮길 이유는 아니다.
                        return List.<ImportedPageWriter.ImportedRevision>of();
                    }
                    return history.revisions().stream()
                            .map(entry -> new ImportedPageWriter.ImportedRevision(entry.title(),
                                    entry.markdown(), entry.editorName(), entry.message(),
                                    parseInstantOrNull(entry.when())))
                            .toList();
                })
                .orElseGet(List::of);
    }

    /**
     * 부모는 조상 목록의 마지막 항목이 이미 옮겨졌을 때만 정해진다. 발견이 조상 깊이 순으로
     * 담으므로 정상 흐름에서는 늘 있고, 없다면 그 조상이 데드레터로 빠졌다는 뜻이다 —
     * 그때 문서를 버리지 않고 루트에 두는 편이 낫다(트리는 나중에 옮길 수 있다).
     */
    private Long resolveParent(MigrationStageWork work, JsonNode snapshot,
                               List<MigrationStageIssue> issues) {
        JsonNode ancestors = snapshot.path("ancestors");
        if (!ancestors.isArray() || ancestors.isEmpty()) {
            return null;
        }
        String parentExternalId = ancestors.get(ancestors.size() - 1).path("id").asText("");
        if (parentExternalId.isBlank()) {
            return null;
        }
        Optional<Long> parentPageId = mappings.findBySourceKey(MigrationObjectMapping.sourceKeyFor(
                        work.provider(), work.sourceInstanceId(), parentExternalId))
                .map(MigrationObjectMapping::getTargetPageId)
                .filter(id -> id != null && wiki.getPage(work.requestedBy(), id).isPresent());
        if (parentPageId.isEmpty()) {
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.PARENT_NOT_FOUND,
                    "page:" + parentExternalId));
            return null;
        }
        return parentPageId.get();
    }

    /** 원본 시각을 못 읽으면 지금으로 둔다 — 시각 하나 때문에 문서를 통째로 못 옮기게 하지 않는다. */
    private Instant parseInstant(String value) {
        Instant parsed = parseInstantOrNull(value);
        return parsed == null ? Instant.now() : parsed;
    }

    /** 리비전 시각은 "모른다"를 그대로 둔다 — 지금으로 채우면 옛 이력이 오늘 저장된 것처럼 보인다. */
    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw MigrationStageException.permanent(ConfluenceDcIssues.IR_INVALID);
        }
    }
}
