package com.platform.migrationservice.migration.confluence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.MigrationPayloadStore;
import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcExtractHandler;
import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcIssues;
import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcMediaCopyHandler;
import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcNormalizeHandler;
import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcResolveHandler;
import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcVerifyHandler;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationSource;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import com.platform.migrationservice.migration.repository.MigrationPayloadRepository;
import com.platform.migrationservice.migration.repository.MigrationSourceRepository;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.migration.worker.MigrationStageOutcome;
import com.platform.migrationservice.migration.worker.MigrationStageWork;
import com.platform.migrationservice.wiki.WikiImportTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 단계 handler 5종을 하나씩 직접 불러 경계 동작을 고정한다.
 *
 * 파이프라인 테스트가 "잘 되는 경우"를 통째로 보는 반면, 여기서는 어긋난 입력에 handler가 무엇을
 * 하는지를 본다 — 조용히 넘어가면 안 되는 것들이라 대부분 경고 코드 하나를 확인하는 형태다.
 */
@SpringBootTest(properties = {
        // 이력 이관(M3)은 끈다. 가짜 원본은 현재 버전만 들고 있어, 켜면 EXTRACT가 없는 지난 버전을
        // 열 번 찾다가 경고를 그만큼 쌓는다 — 여기서 보려는 것은 그 경고가 아니다.
        "platform.migration.dc.history-versions=0",
})
@ActiveProfiles("test")
class ConfluenceDcStageHandlerTest extends WikiImportTestSupport {

    private static final long ADMIN = 21L;
    private static final String CHECKSUM = "a".repeat(64);

    @Autowired ConfluenceDcExtractHandler extract;
    @Autowired ConfluenceDcNormalizeHandler normalize;
    @Autowired ConfluenceDcMediaCopyHandler mediaCopy;
    @Autowired ConfluenceDcResolveHandler resolve;
    @Autowired ConfluenceDcVerifyHandler verify;
    @Autowired MigrationPayloadStore payloads;
    @Autowired MigrationJobRepository jobs;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationSourceRepository sources;
    @Autowired MigrationPayloadRepository payloadRows;
    @Autowired MigrationObjectMappingRepository mappings;
    @Autowired ObjectMapper json;

    private FakeConfluenceDcServer dc;
    private Long spaceId;
    private MigrationJob job;

    @BeforeEach
    void setUp() throws Exception {
        dc = new FakeConfluenceDcServer();
        wiki.reset();
        issues.deleteAllInBatch();
        payloadRows.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        sources.deleteAllInBatch();
        jobs.deleteAllInBatch();
        spaceId = wiki.putSpace("핸들러");
        job = jobs.save(MigrationJob.create(MigrationProvider.CONFLUENCE_DC, "127.0.0.1",
                spaceId, ADMIN, MigrationJobMode.IMPORT));
        sources.save(MigrationSource.of(job.getId(), dc.baseUrl(), "ENG", "test-token"));
    }

    @AfterEach
    void tearDown() {
        dc.stop();
    }

    @Test
    void EXTRACT는_정규화기가_읽는_필드만_담은_스냅샷을_남긴다() throws Exception {
        MigrationItem item = enqueue("10001", "27");

        MigrationStageOutcome outcome = extract.handle(work(item, MigrationStage.EXTRACT, "27"));

        assertThat(outcome.issues()).isEmpty();
        var snapshot = json.readTree(payloads.require(item.getId(), MigrationPayloadKind.SNAPSHOT).body());
        assertThat(snapshot.fieldNames()).toIterable().containsExactlyInAnyOrder("snapshotVersion", "content");
        assertThat(snapshot.path("content").path("title").asText()).isEqualTo("서비스 운영 가이드");
        // 원본 응답의 _links는 담지 않는다 — 스냅샷은 DC 버전 차이에 노출되지 않는 우리 계약이다.
        assertThat(snapshot.path("content").has("_links")).isFalse();
    }

    @Test
    void 발견_이후_원본이_수정됐으면_경고를_남기고_최신본을_가져온다() {
        MigrationItem item = enqueue("10001", "27");
        dc.updatePage("10001", "서비스 운영 가이드", "<p>바뀐 본문</p>", 28);

        MigrationStageOutcome outcome = extract.handle(work(item, MigrationStage.EXTRACT, "27"));

        assertThat(codes(outcome)).containsExactly(ConfluenceDcIssues.SOURCE_VERSION_DRIFT);
    }

    @Test
    void NORMALIZE는_깨진_스냅샷을_재시도하지_않고_실패시킨다() {
        MigrationItem item = enqueue("10001", "1");
        payloads.write(item.getId(), MigrationPayloadKind.SNAPSHOT, "{\"snapshotVersion\":1}");

        assertThatThrownBy(() -> normalize.handle(work(item, MigrationStage.NORMALIZE, "1")))
                .isInstanceOfSatisfying(MigrationStageException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ConfluenceDcIssues.SNAPSHOT_INVALID);
                    // 같은 XHTML을 다시 읽어도 결과가 같다 — 재시도는 데드레터를 늦출 뿐이다.
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void NORMALIZE는_정규화_손실을_그대로_보고한다() {
        MigrationItem item = enqueue("10001", "27");
        extract.handle(work(item, MigrationStage.EXTRACT, "27"));

        MigrationStageOutcome outcome = normalize.handle(work(item, MigrationStage.NORMALIZE, "27"));

        assertThat(codes(outcome)).contains("CONFLUENCE_UNSUPPORTED_MACRO");
        assertThat(payloads.read(item.getId(), MigrationPayloadKind.IR)).isPresent();
    }

    /**
     * MEDIA_COPY는 파일을 받아 두고, 그 사실을 IR에 반영한다. IR을 다시 만드는 것이 핵심이다 —
     * NORMALIZE는 자산 목록이 비어 있는 채로 돌아 이미지를 "옮기지 못한 원본 요소"로 눕히기 때문에,
     * 여기서 갈아끼우지 않으면 파일은 옮겨졌는데 본문에는 안내 문구만 남는다.
     */
    @Test
    void MEDIA_COPY는_첨부를_받아_두고_IR을_다시_만든다() throws Exception {
        dc.putPage("10001", "서비스 운영 가이드", null,
                "<p>구성도</p><ac:image><ri:attachment ri:filename=\"topology.png\"/></ac:image>",
                27, List.of(), List.of(FakeConfluenceDcServer.png("topology.png")),
                FakeConfluenceDcServer.FakeRestrictions.none());
        MigrationItem item = enqueue("10001", "27");
        extract.handle(work(item, MigrationStage.EXTRACT, "27"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "27"));
        // 첫 정규화는 자산 목록이 비어 있어 이미지를 "옮기지 못한 원본 요소"로 눕힌다.
        assertThat(json.readTree(payloads.require(item.getId(), MigrationPayloadKind.IR).body())
                .path("assets")).isEmpty();

        mediaCopy.handle(work(item, MigrationStage.MEDIA_COPY, "27"));

        String manifest = payloads.require(item.getId(), MigrationPayloadKind.MEDIA_MANIFEST).body();
        assertThat(json.readTree(manifest).path("files")).hasSize(1);
        assertThat(json.readTree(manifest).path("files").get(0).path("filename").asText())
                .isEqualTo("topology.png");
        // 목록에는 위키 저장소 좌표가 없다 — 엔진은 위키가 파일을 어디 두는지 알지 않는다.
        assertThat(json.readTree(manifest).path("files").get(0).has("storageKey")).isFalse();
        // 재정규화 결과 — IR에 자산이 선언되고 본문이 그 자산을 가리킨다.
        String ir = payloads.require(item.getId(), MigrationPayloadKind.IR).body();
        assertThat(json.readTree(ir).path("assets")).hasSize(1);
        assertThat(json.readTree(ir).path("assets").get(0).path("sourceExternalId").asText())
                .isEqualTo("attachment:topology.png");
    }

    /** 상한을 넘는 파일은 받지 않고 보고서에만 남긴다 — 워커가 메모리째 넘어가면 안 된다. */
    @Test
    void MEDIA_COPY는_상한을_넘는_첨부를_건너뛰고_경고한다() {
        dc.putPage("10001", "서비스 운영 가이드", null,
                "<p><ac:link><ri:attachment ri:filename=\"huge.bin\"/></ac:link></p>", 27, List.of(),
                List.of(FakeConfluenceDcServer.oversized("huge.bin", 10_000_000_000L)),
                FakeConfluenceDcServer.FakeRestrictions.none());
        MigrationItem item = enqueue("10001", "27");
        extract.handle(work(item, MigrationStage.EXTRACT, "27"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "27"));

        MigrationStageOutcome outcome = mediaCopy.handle(work(item, MigrationStage.MEDIA_COPY, "27"));

        assertThat(outcome.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo(ConfluenceDcIssues.ATTACHMENT_TOO_LARGE);
                    assertThat(issue.sourcePath()).isEqualTo("attachment:huge.bin");
                });
    }

    @Test
    void RESOLVE는_조상을_못_찾으면_루트에_두고_경고한다() {
        // 부모(10001)를 옮기지 않은 채 자식만 처리한다 — 조상이 데드레터로 빠진 상황이다.
        MigrationItem item = enqueue("10002", "3");
        extract.handle(work(item, MigrationStage.EXTRACT, "3"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "3"));

        MigrationStageOutcome outcome = resolve.handle(work(item, MigrationStage.RESOLVE, "3"));

        assertThat(codes(outcome)).contains(ConfluenceDcIssues.PARENT_NOT_FOUND,
                ConfluenceDcIssues.AUTHOR_UNMAPPED);
        assertThat(wiki.page(outcome.targetPageId()).parentId).isNull();
    }

    /**
     * 잘라내기는 EXTRACT에서 일어난다 — IR 계약의 title 상한도 255라, 스냅샷에서 미리 자르지 않으면
     * NORMALIZE가 검증 실패로 항목을 데드레터시켜 문서가 아예 안 넘어온다.
     */
    @Test
    void 아주_긴_제목은_스냅샷_단계에서_잘리고_문서까지_옮겨진다() {
        String longTitle = "가".repeat(300);
        dc.updatePage("10002", longTitle, "<p>본문</p>", 4);
        MigrationItem item = enqueue("10002", "4");

        MigrationStageOutcome extracted = extract.handle(work(item, MigrationStage.EXTRACT, "4"));
        assertThat(codes(extracted)).contains(ImportedPageWriter.TITLE_TRUNCATED);

        normalize.handle(work(item, MigrationStage.NORMALIZE, "4"));
        MigrationStageOutcome outcome = resolve.handle(work(item, MigrationStage.RESOLVE, "4"));

        assertThat(wiki.page(outcome.targetPageId()).title)
                .hasSize(ImportedPageWriter.MAX_TITLE_LENGTH);
    }

    @Test
    void VERIFY는_제목이_어긋나면_ERROR로_보고한다() {
        MigrationItem item = enqueue("10002", "3");
        extract.handle(work(item, MigrationStage.EXTRACT, "3"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "3"));
        MigrationStageOutcome resolved = resolve.handle(work(item, MigrationStage.RESOLVE, "3"));
        long pageId = resolved.targetPageId();
        wiki.retitle(pageId, "사람이 바꾼 제목");

        MigrationStageOutcome outcome = verify.handle(
                workWithPage(item, MigrationStage.VERIFY, "3", pageId));

        assertThat(codes(outcome)).contains(ConfluenceDcIssues.VERIFY_TITLE_MISMATCH);
        // 대조 실패는 예외가 아니다 — 재시도가 같은 문서를 또 쓰면 안 된다.
        assertThat(outcome.targetPageId()).isEqualTo(pageId);
    }

    @Test
    void VERIFY는_대상_문서가_사라졌으면_ERROR로_보고한다() {
        MigrationItem item = enqueue("10002", "3");

        MigrationStageOutcome outcome =
                verify.handle(workWithPage(item, MigrationStage.VERIFY, "3", 999_999L));

        assertThat(codes(outcome)).containsExactly(ConfluenceDcIssues.VERIFY_PAGE_MISSING);
    }

    /**
     * 위키가 요청을 거부하면(4xx) 재시도하지 않는다 — 같은 본문을 다시 보내도 같은 답이 온다.
     * 내부 토큰이 어긋난 상황이 그 대표적인 예다.
     */
    @Test
    void 위키가_없는_문서를_가리키면_비재시도_실패다() {
        MigrationItem item = enqueue("10002", "3");
        extract.handle(work(item, MigrationStage.EXTRACT, "3"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "3"));
        MigrationStageOutcome resolved = resolve.handle(work(item, MigrationStage.RESOLVE, "3"));
        wiki.removePage(resolved.targetPageId());

        // 같은 원본을 다시 처리하면 매핑은 살아 있고 문서만 없다 — 새 문서를 만들어야 한다.
        MigrationStageOutcome again = resolve.handle(work(item, MigrationStage.RESOLVE, "3"));

        assertThat(again.targetPageId()).isNotEqualTo(resolved.targetPageId());
        assertThat(wiki.page(again.targetPageId()).title).isEqualTo("장애 대응 절차");
    }

    /**
     * 받아 둔 첨부가 문서에서 사라졌으면 VERIFY가 잡는다. 제목·라벨만 보던 시절에는 "파일이
     * 빠진 문서"가 그대로 성공으로 지나갔다.
     */
    @Test
    void VERIFY는_받아_둔_첨부가_문서에_없으면_ERROR로_보고한다() {
        dc.putPage("10001", "서비스 운영 가이드", null,
                "<p>구성도</p><ac:image><ri:attachment ri:filename=\"topology.png\"/></ac:image>",
                27, List.of(), List.of(FakeConfluenceDcServer.png("topology.png")),
                FakeConfluenceDcServer.FakeRestrictions.none());
        MigrationItem item = enqueue("10001", "27");
        extract.handle(work(item, MigrationStage.EXTRACT, "27"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "27"));
        mediaCopy.handle(work(item, MigrationStage.MEDIA_COPY, "27"));
        MigrationStageOutcome resolved = resolve.handle(work(item, MigrationStage.RESOLVE, "27"));
        long pageId = resolved.targetPageId();
        // 붙어 있는 동안에는 대조가 통과한다.
        assertThat(codes(verify.handle(workWithPage(item, MigrationStage.VERIFY, "27", pageId))))
                .doesNotContain(ConfluenceDcIssues.VERIFY_ATTACHMENT_MISMATCH);

        wiki.removeAttachment(pageId, "topology.png");

        MigrationStageOutcome outcome =
                verify.handle(workWithPage(item, MigrationStage.VERIFY, "27", pageId));

        assertThat(outcome.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo(ConfluenceDcIssues.VERIFY_ATTACHMENT_MISMATCH);
                    assertThat(issue.sourcePath()).isEqualTo("attachment:topology.png");
                });
    }

    /**
     * 옮기려던 댓글보다 문서의 댓글이 적으면 VERIFY가 잡는다. 반대로 더 많은 것은 손실이 아니다 —
     * 사람이 이관 뒤에 단 댓글이다.
     */
    @Test
    void VERIFY는_옮기려던_댓글이_모자라면_ERROR로_보고한다() {
        dc.putComments("10001", List.of(
                FakeConfluenceDcServer.FakeComment.footer("c1", "<p>확인했습니다.</p>", "박댓글",
                        "2026-02-01T00:00:00Z"),
                FakeConfluenceDcServer.FakeComment.footer("c2", "<p>감사합니다.</p>", "김운영",
                        "2026-02-02T00:00:00Z")));
        MigrationItem item = enqueue("10001", "27");
        extract.handle(work(item, MigrationStage.EXTRACT, "27"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "27"));
        MigrationStageOutcome resolved = resolve.handle(work(item, MigrationStage.RESOLVE, "27"));
        long pageId = resolved.targetPageId();
        assertThat(wiki.commentsOf(pageId)).hasSize(2);
        assertThat(codes(verify.handle(workWithPage(item, MigrationStage.VERIFY, "27", pageId))))
                .doesNotContain(ConfluenceDcIssues.VERIFY_COMMENT_COUNT_MISMATCH);

        wiki.removeComment(wiki.commentsOf(pageId).get(0).id());

        assertThat(codes(verify.handle(workWithPage(item, MigrationStage.VERIFY, "27", pageId))))
                .contains(ConfluenceDcIssues.VERIFY_COMMENT_COUNT_MISMATCH);
    }

    private List<String> codes(MigrationStageOutcome outcome) {
        return outcome.issues().stream().map(MigrationStageIssue::code).toList();
    }

    private MigrationItem enqueue(String externalId, String version) {
        return items.saveAndFlush(MigrationItem.pending(job.getId(), externalId, version, CHECKSUM,
                "dc:content/" + externalId));
    }

    private MigrationStageWork work(MigrationItem item, MigrationStage stage, String version) {
        return workWithPage(item, stage, version, null);
    }

    private MigrationStageWork workWithPage(MigrationItem item, MigrationStage stage, String version,
                                            Long targetPageId) {
        return new MigrationStageWork(job.getId(), item.getId(), "token", MigrationProvider.CONFLUENCE_DC,
                job.getSourceInstanceId(), job.getMode(), spaceId, ADMIN, stage,
                item.getExternalObjectId(), version, CHECKSUM, item.getPayloadRef(), targetPageId,
                item.getSiblingOrder(), 1);
    }
}
