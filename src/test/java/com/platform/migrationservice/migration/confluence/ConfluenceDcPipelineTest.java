package com.platform.migrationservice.migration.confluence;

import com.platform.migrationservice.migration.MigrationJobService;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcDiscoveryService;
import com.platform.migrationservice.migration.confluence.restriction.FakeMigrationPrincipalResolver;
import com.platform.migrationservice.migration.dto.MigrationDiscoverResponse;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
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
import com.platform.migrationservice.migration.worker.MigrationWorker;
import com.platform.migrationservice.permission.FakePermissionClient;
import com.platform.migrationservice.permission.SpaceAction;
import com.platform.migrationservice.wiki.FakeWikiImportServer;
import com.platform.migrationservice.wiki.WikiImportTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 발견 → 실행 → 재실행까지 파이프라인 전체를 가짜 DC 서버와 가짜 위키 import 서버로 한 번
 * 완주한다(성공지표 S1).
 *
 * 단계별 단위 테스트로는 잡히지 않는 것들이 여기서 잡힌다: 부모가 자식보다 먼저 처리되는가,
 * 재실행이 문서를 늘리지 않는가, 원본이 바뀌면 갱신되는가, 시각·라벨·작성자 이름이 남는가.
 * 결과를 확인하는 자리가 위키 DB에서 import API 너머의 가짜 위키로 바뀌었을 뿐, 보는 사실은 같다.
 */
@SpringBootTest(properties = {
        "platform.migration.dc.page-size=2",
        // 가짜 첨부는 수십 바이트다. 상한을 1KB로 낮춰 "크기 초과" 경로를 실제 파일 없이 태운다.
        "platform.migration.dc.max-attachment-bytes=1024",
        // 이력 이관(M3)은 여기서 끈다. 켜면 버전마다 원본 왕복이 한 번씩 늘어, 지난 버전을
        // 두지 않은 이 시나리오들에서 없는 버전을 찾는 404가 문서마다 열 번씩 쌓인다.
        "platform.migration.dc.history-versions=0",
})
@ActiveProfiles("test")
class ConfluenceDcPipelineTest extends WikiImportTestSupport {

    private static final long ADMIN = 11L;
    private static final Instant NOW = Instant.parse("2026-09-05T09:00:00Z");

    @Autowired MigrationWorker worker;
    @Autowired MigrationJobService migrations;
    @Autowired ConfluenceDcDiscoveryService discovery;
    @Autowired MigrationJobRepository jobs;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationSourceRepository sources;
    @Autowired MigrationPayloadRepository payloads;
    @Autowired MigrationObjectMappingRepository mappings;
    @Autowired FakePermissionClient permissions;
    @Autowired FakeMigrationPrincipalResolver principals;

    private FakeConfluenceDcServer dc;
    private Long spaceId;

    @BeforeEach
    void setUp() throws Exception {
        dc = new FakeConfluenceDcServer();
        wiki.reset();
        permissions.reset();
        principals.reset();
        issues.deleteAllInBatch();
        payloads.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        sources.deleteAllInBatch();
        jobs.deleteAllInBatch();
        spaceId = wiki.putSpace("이관 대상");
        permissions.allow(ADMIN, spaceId, SpaceAction.ADMIN);
    }

    @AfterEach
    void tearDown() {
        dc.stop();
    }

    @Test
    void 한_스페이스를_끝까지_옮기고_다시_돌려도_문서가_늘지_않는다() {
        MigrationJob job = importJob();

        MigrationDiscoverResponse discovered = discovery.discover(job.getId(), NOW);
        assertThat(discovered.discovered()).isEqualTo(3);
        assertThat(discovered.enqueued()).isEqualTo(3);
        assertThat(discovered.skipped()).isZero();
        // 조상 깊이 오름차순으로 담아야 RESOLVE에서 부모의 대상 id를 찾을 수 있다.
        assertThat(items.findFiltered(job.getId(), null, null,
                        org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .extracting(MigrationItem::getExternalObjectId)
                .containsExactly("10001", "10002", "10003");

        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.COMPLETED);
        assertThat(wiki.pageCount()).isEqualTo(3);

        FakeWikiImportServer.FakePage root = wiki.pageTitled("서비스 운영 가이드");
        FakeWikiImportServer.FakePage child = wiki.pageTitled("장애 대응 절차");
        assertThat(child.parentId).isEqualTo(root.id);
        // 시각은 원본 것이어야 한다 — "마지막 수정일"이 이관한 날짜면 사용자에게 거짓말이 된다.
        assertThat(root.createdAt).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(root.updatedAt).isEqualTo(Instant.parse("2026-08-17T00:00:00Z"));
        assertThat(root.labels).containsExactlyInAnyOrder("운영", "런북");
        // 원본 작성자 이름은 리비전 편집자 이름 스냅샷으로 남는다.
        assertThat(root.revisions).extracting(revision -> revision.editorName)
                .containsExactly("김운영");
        // 요청은 잡 요청자 명의로 간다 — 위키는 그 사람이 쓴 것으로 기록한다.
        assertThat(wiki.lastActorId).isEqualTo(ADMIN);

        // 재실행: 원본이 그대로면 새 문서도, 새 리비전도 없다(S1 멱등).
        MigrationJob second = importJob();
        discovery.discover(second.getId(), NOW);
        migrations.start(ADMIN, second.getId(), NOW);
        worker.drain(second.getId(), 100, () -> NOW);

        assertThat(wiki.pageCount()).isEqualTo(3);
        assertThat(wiki.page(root.id).revisions).hasSize(1);
        assertThat(jobs.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.COMPLETED);
    }

    @Test
    void 원본이_바뀌면_새_리비전으로_갱신된다() {
        MigrationJob first = importJob();
        discovery.discover(first.getId(), NOW);
        migrations.start(ADMIN, first.getId(), NOW);
        worker.drain(first.getId(), 100, () -> NOW);
        long pageId = wiki.pageTitled("장애 대응 절차").id;

        dc.updatePage("10002", "장애 대응 절차 v2", "<p>새 절차입니다.</p>", 9);
        MigrationJob second = importJob();
        discovery.discover(second.getId(), NOW);
        migrations.start(ADMIN, second.getId(), NOW);
        worker.drain(second.getId(), 100, () -> NOW);

        FakeWikiImportServer.FakePage updated = wiki.page(pageId);
        assertThat(updated.title).isEqualTo("장애 대응 절차 v2");
        assertThat(updated.content).contains("새 절차입니다.");
        assertThat(wiki.pageCount()).isEqualTo(3);
        assertThat(updated.revisions).hasSize(2);
        assertThat(updated.revisions.get(1).changeNote).isEqualTo("컨플루언스 재이관 v9");
    }

    @Test
    void dry_run은_문서를_한_건도_만들지_않고_마크다운까지만_남긴다() {
        MigrationJob job = createJob(MigrationJobMode.DRY_RUN);
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        assertThat(wiki.pageCount()).isZero();
        assertThat(mappings.count()).isZero();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.COMPLETED);
        MigrationItem item = items.findByJobIdAndSourceKey(job.getId(),
                MigrationItem.sourceKeyFor("10001")).orElseThrow();
        assertThat(item.getStage()).isEqualTo(MigrationStage.DONE);
        assertThat(payloads.findByItemIdAndKind(item.getId(), MigrationPayloadKind.MARKDOWN))
                .isPresent();
    }

    @Test
    void 미지원_매크로와_첨부는_손실_보고서에_남는다() {
        MigrationJob job = createJob(MigrationJobMode.DRY_RUN);
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        List<String> codes = issues.findByJobIdOrderByIdAsc(job.getId()).stream()
                .map(issue -> issue.getCode())
                .toList();
        // dry-run은 한 바이트도 받지 않는다 — 첨부는 "옮길 예정"으로만 보고된다(M2 §4.1).
        assertThat(codes).contains("CONFLUENCE_UNSUPPORTED_MACRO", "ATTACHMENT_PLANNED");

        // 집계에는 대표 위치가 함께 실려야 화면에서 "무엇이 문제였는지"까지 보인다.
        assertThat(issues.summarize(job.getId()))
                .filteredOn(summary -> "ATTACHMENT_PLANNED".equals(summary.code()))
                .singleElement()
                .satisfies(summary ->
                        assertThat(summary.sampleSourcePath()).isEqualTo("attachment:topology.png"));
    }

    @Test
    void 발견하지_않고_시작하면_400이다() {
        MigrationJob job = importJob();

        assertThatThrownBy(() -> migrations.start(ADMIN, job.getId(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MigrationJobService.MIGRATION_NOTHING_DISCOVERED);
    }

    @Test
    void 재발견은_새_항목만_담는다() {
        MigrationJob job = importJob();
        discovery.discover(job.getId(), NOW);

        MigrationDiscoverResponse again = discovery.discover(job.getId(), NOW);

        assertThat(again.discovered()).isEqualTo(3);
        assertThat(again.enqueued()).isZero();
        assertThat(again.skipped()).isEqualTo(3);
        assertThat(items.countByJobId(job.getId())).isEqualTo(3);
        assertThat(sources.findById(job.getId()).orElseThrow())
                .satisfies(source -> {
                    assertThat(source.getDiscoveredCount()).isEqualTo(3);
                    assertThat(source.getSourceSpaceName()).isEqualTo("Engineering");
                });
    }

    @Test
    void 원본에서_사라진_문서는_데드레터로_가고_잡은_FAILED다() {
        MigrationJob job = importJob();
        discovery.discover(job.getId(), NOW);
        dc.removePage("10003");
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        MigrationItem missing = items.findByJobIdAndSourceKey(job.getId(),
                MigrationItem.sourceKeyFor("10003")).orElseThrow();
        assertThat(missing.getStatus()).isEqualTo(MigrationItemStatus.DEAD_LETTER);
        assertThat(missing.getLastErrorCode()).isEqualTo("DC_NOT_FOUND");
        // 나머지 두 건은 정상적으로 옮겨진다 — 한 건 때문에 전체를 되돌리지 않는다.
        assertThat(wiki.pageCount()).isEqualTo(2);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.FAILED);
    }

    // -- M2 4.1 첨부 본체 --------------------------------------------

    @Test
    void 첨부는_문서에_붙고_본문_참조가_우리_주소로_바뀐다() {
        seedAttachmentPage();
        MigrationJob job = importJob();
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        FakeWikiImportServer.FakePage page = wiki.pageTitled("서비스 운영 가이드");
        // 상한을 넘은 huge.bin은 빠지고 나머지 둘만 올라간다.
        assertThat(page.attachments).extracting(file -> file.filename)
                .containsExactlyInAnyOrder("topology.png", "runbook.pdf");

        long imageId = page.attachment("topology.png").orElseThrow().id;
        long pdfId = page.attachment("runbook.pdf").orElseThrow().id;
        // 이미지는 인라인 주소, 그 밖의 파일은 내려받기 주소 — 두 주소 모두 위키가 준 값이다.
        assertThat(page.content).contains("/api/wiki/attachments/" + imageId + "/inline");
        assertThat(page.content).contains("](/api/wiki/attachments/" + pdfId + ")");
        assertThat(page.content).doesNotContain("attachment:topology.png");

        assertThat(issueCodes(job.getId())).contains("ATTACHMENT_TOO_LARGE");
        // 본문을 다시 눌렀어도 리비전은 하나다 — 이관은 한 번의 저장이다.
        assertThat(page.revisions).hasSize(1);
        // 현재 리비전의 본문도 함께 눌렸다 — 이력과 현재가 어긋나면 안 된다.
        assertThat(page.revisions.get(0).content).isEqualTo(page.content);
    }

    @Test
    void 원본이_바뀌어_다시_옮겨도_같은_첨부는_새_버전을_쌓지_않는다() {
        seedAttachmentPage();
        MigrationJob first = importJob();
        discovery.discover(first.getId(), NOW);
        migrations.start(ADMIN, first.getId(), NOW);
        worker.drain(first.getId(), 100, () -> NOW);
        long pageId = wiki.pageTitled("서비스 운영 가이드").id;
        int before = wiki.page(pageId).attachments.size();

        // 본문만 바뀌었다 - 첨부 바이트는 그대로다.
        dc.updatePage("10001", "서비스 운영 가이드",
                "<p>새 안내</p><ac:image><ri:attachment ri:filename=\"topology.png\"/></ac:image>", 28);
        MigrationJob second = importJob();
        discovery.discover(second.getId(), NOW);
        migrations.start(ADMIN, second.getId(), NOW);
        worker.drain(second.getId(), 100, () -> NOW);

        assertThat(wiki.page(pageId).attachments).hasSize(before);
        assertThat(wiki.page(pageId).attachments).allSatisfy(file -> assertThat(file.version).isEqualTo(1));
    }

    /**
     * 문서를 만든 **뒤** 실패해도 재시도가 문서를 하나 더 만들지 않는다.
     *
     * 이관에서 가장 되돌리기 어려운 사고가 이 중복이다. 첨부 업로드에서 위키가 한 번 죽으면
     * 문서는 이미 위키에 있는데 단계는 실패로 끝나는데, 그때 이관 원장이 비어 있으면 재시도가
     * "옮긴 적 없다"로 보고 같은 원본을 다시 만든다. 원장을 문서 생성 직후에 못박아 막는다.
     */
    @Test
    void 첨부_업로드가_한_번_실패해도_재시도가_문서를_두_벌로_만들지_않는다() {
        seedAttachmentPage();
        MigrationJob job = importJob();
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        // 문서는 만들어지고 첫 첨부 업로드에서 위키가 죽는다.
        wiki.failNextWith(503, "/attachments");
        worker.drain(job.getId(), 100, () -> NOW);

        // 재시도 전: 문서는 이미 있고 항목은 재시도 대기다.
        assertThat(wiki.pageCount()).isEqualTo(1);
        MigrationItem waiting = items.findByJobIdAndSourceKey(job.getId(),
                MigrationItem.sourceKeyFor("10001")).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(MigrationItemStatus.RETRY_WAIT);
        assertThat(waiting.getLastErrorCode()).isEqualTo("WIKI_IMPORT_UNAVAILABLE");

        // 백오프가 지난 뒤 재시도 — 같은 문서를 갱신 경로로 마저 마무리한다.
        worker.drain(job.getId(), 100, () -> NOW.plusSeconds(120));

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.COMPLETED);
        assertThat(wiki.pageCount()).isEqualTo(1);
        FakeWikiImportServer.FakePage page = wiki.pageTitled("서비스 운영 가이드");
        assertThat(page.attachments).extracting(file -> file.filename)
                .containsExactlyInAnyOrder("topology.png", "runbook.pdf");
        // 재시도가 갱신 경로를 탔으므로 리비전이 하나 더 쌓인다 — 중복 문서보다 훨씬 싼 대가다.
        assertThat(page.revisions).hasSize(2);
        // 대조도 통과한다: 받아 둔 첨부가 전부 문서에 붙었다.
        assertThat(issueCodes(job.getId())).doesNotContain("VERIFY_ATTACHMENT_MISMATCH");
    }

    // -- M2 4.2 링크 재작성 -------------------------------------------

    @Test
    void 원본_링크는_우리_주소로_바뀌고_못_찾은_것만_원본_URL로_남는다() {
        seedLinkedPages();
        MigrationJob job = importJob();
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        FakeWikiImportServer.FakePage root = wiki.pageTitled("서비스 운영 가이드");
        FakeWikiImportServer.FakePage child = wiki.pageTitled("장애 대응 절차");

        // 나중에 옮겨진 문서 - RESOLVE 때는 없었고 마무리 pass가 이었다.
        assertThat(root.content).contains("/wiki/spaces/" + spaceId + "/pages/" + child.id);
        // 원본에 없는 문서 - 끝내 못 찾았으니 원본 절대 URL로 되돌린다.
        assertThat(root.content).contains(dc.baseUrl() + "/pages/viewpage.action?pageId=99999");
        assertThat(root.content).doesNotContain("dc-page:");
        assertThat(issueCodes(job.getId())).contains("LINK_UNRESOLVED");
        // 정리는 새 리비전으로 남는다 - 문서를 쓴 뒤에 일어난 별개의 변경이다.
        assertThat(root.revisions.get(root.revisions.size() - 1).changeNote).isEqualTo("이관 링크 정리");

        // 먼저 옮겨진 문서를 제목으로 가리킨 링크는 RESOLVE에서 바로 이어진다.
        assertThat(child.content).contains("/wiki/spaces/" + spaceId + "/pages/" + root.id);
    }

    @Test
    void 링크_정리를_마친_문서는_다시_이관해도_리비전이_늘지_않는다() {
        seedLinkedPages();
        MigrationJob first = importJob();
        discovery.discover(first.getId(), NOW);
        migrations.start(ADMIN, first.getId(), NOW);
        worker.drain(first.getId(), 100, () -> NOW);
        long rootId = wiki.pageTitled("서비스 운영 가이드").id;
        int before = wiki.page(rootId).revisions.size();

        MigrationJob second = importJob();
        discovery.discover(second.getId(), NOW);
        migrations.start(ADMIN, second.getId(), NOW);
        worker.drain(second.getId(), 100, () -> NOW);

        assertThat(wiki.page(rootId).revisions).hasSize(before);
    }

    /**
     * 링크 정리가 실패하면 잡에 남는다 — 로그로만 두면 보고서는 "완료"인데 문서 사이 링크는
     * 원본 사이트로 튕긴다. 그리고 고친 뒤 정리만 따로 다시 돌릴 수 있다.
     */
    @Test
    void 링크_정리_실패는_잡에_남고_따로_다시_돌릴_수_있다() {
        seedLinkedPages();
        MigrationJob job = importJob();
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        // 정리 pass가 본문을 다시 쓰려는 순간 위키가 죽는다.
        wiki.failNextWith(503, "/content");
        worker.drain(job.getId(), 100, () -> NOW);

        // 옮기기 자체는 끝났다 — 정리 실패가 잡의 결말을 바꾸지는 않는다.
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.COMPLETED);
        FakeWikiImportServer.FakePage root = wiki.pageTitled("서비스 운영 가이드");
        assertThat(root.content).contains("dc-page:");

        // 실패가 보고서에 남는다(항목 표가 아니라 잡 단위로).
        assertThat(migrations.detail(ADMIN, job.getId()).jobIssues())
                .extracting(issue -> issue.code())
                .contains("LINK_FIXUP_FAILED");

        // 위키가 돌아온 뒤 정리만 다시 돌린다 — 옮긴 문서를 다시 이관하지 않는다.
        assertThat(migrations.rerunLinkFixup(ADMIN, job.getId()).touched()).isEqualTo(1);
        assertThat(wiki.page(root.id).content).doesNotContain("dc-page:");

        // 다시 눌러도 안전하다 — 고칠 것이 없으면 손대지 않는다.
        assertThat(migrations.rerunLinkFixup(ADMIN, job.getId()).touched()).isZero();
    }

    // -- M2 4.3 페이지 제한 -------------------------------------------

    @Test
    void 대조된_제한은_그대로_옮기고_미매핑은_요청자_단독으로_닫는다() {
        dc.putPage("10001", "서비스 운영 가이드", null, "<p>제한 있는 문서</p>", 1, List.of(),
                List.of(), FakeConfluenceDcServer.FakeRestrictions.readBy(List.of("ops"), List.of()));
        dc.putPage("10002", "장애 대응 절차", "10001", "<p>대조 안 되는 문서</p>", 1, List.of(),
                List.of(), FakeConfluenceDcServer.FakeRestrictions.readBy(List.of("ghost"), List.of()));
        dc.removePage("10003");
        principals.mapUser("ops", 77L);

        MigrationJob job = importJob();
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        FakeWikiImportServer.FakePage mapped = wiki.pageTitled("서비스 운영 가이드");
        assertThat(mapped.viewRestrictions)
                .containsExactly(new FakeWikiImportServer.FakePrincipal("USER", 77L));

        // 못 찾은 주체는 공개로 풀지 않는다 - 잡 요청자만 남기고 닫는다(ADR-W14-07 fail-closed).
        FakeWikiImportServer.FakePage unmapped = wiki.pageTitled("장애 대응 절차");
        assertThat(unmapped.viewRestrictions)
                .containsExactly(new FakeWikiImportServer.FakePrincipal("USER", ADMIN));
        assertThat(issueCodes(job.getId())).contains("RESTRICTION_PRINCIPAL_UNMAPPED");
    }

    // -- M2 4.4 원본 정렬 ---------------------------------------------

    @Test
    void 형제_순서는_원본을_따르고_재이관에서_순서만_바뀌면_정렬만_갱신된다() {
        dc.putPage("10003", "복구 체크리스트", "10001", "<p>체크리스트</p>", 1, List.of(), List.of(),
                FakeConfluenceDcServer.FakeRestrictions.none());
        dc.reorder(List.of("10001", "10003", "10002"));

        MigrationJob first = importJob();
        discovery.discover(first.getId(), NOW);
        migrations.start(ADMIN, first.getId(), NOW);
        worker.drain(first.getId(), 100, () -> NOW);

        assertThat(wiki.pageTitled("복구 체크리스트").sortOrder).isZero();
        assertThat(wiki.pageTitled("장애 대응 절차").sortOrder).isEqualTo(1L);

        // 원본에서 순서만 뒤집었다 - 본문이 그대로라 문서는 다시 쓰이지 않고 순번만 따라온다.
        dc.reorder(List.of("10001", "10002", "10003"));
        MigrationJob second = importJob();
        discovery.discover(second.getId(), NOW);
        migrations.start(ADMIN, second.getId(), NOW);
        worker.drain(second.getId(), 100, () -> NOW);

        assertThat(wiki.pageTitled("장애 대응 절차").sortOrder).isZero();
        assertThat(wiki.pageTitled("복구 체크리스트").sortOrder).isEqualTo(1L);
        assertThat(wiki.pageTitled("장애 대응 절차").revisions).hasSize(1);
    }

    /** 이미지·PDF·상한 초과 파일이 모두 걸린 문서 하나로 줄인다. */
    private void seedAttachmentPage() {
        dc.putPage("10001", "서비스 운영 가이드", null,
                "<p>구성도</p>"
                        + "<ac:image><ri:attachment ri:filename=\"topology.png\"/></ac:image>"
                        + "<p><ac:link><ri:attachment ri:filename=\"runbook.pdf\"/>"
                        + "<ac:plain-text-link-body>런북</ac:plain-text-link-body></ac:link></p>"
                        + "<p><ac:link><ri:attachment ri:filename=\"huge.bin\"/>"
                        + "<ac:plain-text-link-body>대용량</ac:plain-text-link-body></ac:link></p>",
                27, List.of(),
                List.of(FakeConfluenceDcServer.png("topology.png"),
                        FakeConfluenceDcServer.pdf("runbook.pdf"),
                        FakeConfluenceDcServer.oversized("huge.bin", 10_000_000L)),
                FakeConfluenceDcServer.FakeRestrictions.none());
        dc.removePage("10002");
        dc.removePage("10003");
    }

    /** 서로를 가리키는 문서 둘 + 없는 문서 하나를 가리키는 링크. */
    private void seedLinkedPages() {
        String base = dc.baseUrl();
        dc.putPage("10001", "서비스 운영 가이드", null,
                "<p><a href=\"" + base + "/pages/viewpage.action?pageId=10002\">장애 대응</a>"
                        + " · <a href=\"" + base + "/pages/viewpage.action?pageId=99999\">사라진 문서</a></p>",
                1, List.of(), List.of(), FakeConfluenceDcServer.FakeRestrictions.none());
        dc.putPage("10002", "장애 대응 절차", "10001",
                "<p><a href=\"" + base
                        + "/display/ENG/%EC%84%9C%EB%B9%84%EC%8A%A4+%EC%9A%B4%EC%98%81+%EA%B0%80%EC%9D%B4%EB%93%9C"
                        + "\">운영 가이드</a></p>",
                1, List.of(), List.of(), FakeConfluenceDcServer.FakeRestrictions.none());
        dc.removePage("10003");
    }

    private List<String> issueCodes(long jobId) {
        return issues.findByJobIdOrderByIdAsc(jobId).stream().map(issue -> issue.getCode()).toList();
    }

    private MigrationJob importJob() {
        return createJob(MigrationJobMode.IMPORT);
    }

    private MigrationJob createJob(MigrationJobMode mode) {
        MigrationJob job = jobs.save(MigrationJob.create(MigrationProvider.CONFLUENCE_DC,
                "127.0.0.1", spaceId, ADMIN, mode));
        sources.save(MigrationSource.of(job.getId(), dc.baseUrl(), "ENG", "test-token"));
        return job;
    }
}
