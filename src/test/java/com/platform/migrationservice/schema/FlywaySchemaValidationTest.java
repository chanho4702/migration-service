package com.platform.migrationservice.schema;

import com.platform.migrationservice.migration.model.MigrationIssue;
import com.platform.migrationservice.migration.model.MigrationIssueSeverity;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationPayload;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationSource;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import com.platform.migrationservice.migration.repository.MigrationPayloadRepository;
import com.platform.migrationservice.migration.repository.MigrationSourceRepository;
import com.platform.migrationservice.wiki.WikiImportTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway V1 ↔ JPA 엔티티 정합을 실제 Postgres로 검증한다.
 *
 * 나머지 테스트는 H2 + `ddl-auto: create-drop`이라 **마이그레이션을 아예 타지 않는다** —
 * 엔티티에 필드를 추가하고 `V*.sql`을 빠뜨려도 전부 통과하고, 운영에서 `ddl-auto: validate`가
 * 부팅을 거부한다. 그 간극을 이 테스트가 메운다.
 *
 * 여기서 하는 일:
 * 1. 빈 Postgres에 Flyway로 V1을 적용하고
 * 2. `ddl-auto: validate`로 컨텍스트를 띄운다(불일치면 컨텍스트 로딩 실패 = 테스트 실패)
 * 3. 실제 INSERT/SELECT로 H2에는 없는 제약(FK·CHECK)까지 살아 있는지 본다.
 *
 * Docker가 필요하다. 없으면 이 테스트만 실패하므로, Docker 없이 돌릴 땐
 * `./gradlew test --tests '!*FlywaySchemaValidationTest'`로 제외한다.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none",
})
@ActiveProfiles("test")
@Testcontainers
class FlywaySchemaValidationTest extends WikiImportTestSupport {

    private static final String CHECKSUM = "f".repeat(64);

    /** 위키의 스페이스 id다 — 이 스키마에는 FK가 없으므로 실재하지 않아도 저장된다(그게 요점이다). */
    private static final long SPACE_ID = 4242L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MigrationJobRepository jobs;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationSourceRepository sources;
    @Autowired MigrationPayloadRepository payloads;
    @Autowired MigrationObjectMappingRepository mappings;

    /** 컨테이너 하나를 클래스 전체가 공유한다 — 앞 테스트가 남긴 행이 다음 단정에 섞이지 않게 비운다. */
    @org.junit.jupiter.api.BeforeEach
    void clean() {
        mappings.deleteAllInBatch();
        jobs.deleteAllInBatch();   // item·issue·source·payload는 cascade로 따라간다
    }

    /**
     * 컨텍스트가 떴다는 것 자체가 "마이그레이션 결과 스키마 == 엔티티 매핑"의 증거다
     * (validate가 컬럼 누락·타입 불일치에서 부팅을 거부한다).
     */
    @Test
    void 마이그레이션_스키마로_엔티티_검증이_통과한다() {
        assertThat(postgres.isRunning()).isTrue();
    }

    @Test
    void job_item_issue가_실제_Postgres에_저장된다() {
        MigrationJob job = jobs.save(MigrationJob.create(
                MigrationProvider.NOTION, "workspace-acme", SPACE_ID, 1L, MigrationJobMode.DRY_RUN));
        MigrationItem item = items.save(MigrationItem.pending(
                job.getId(), "page-42", "v1", CHECKSUM, "imports/notion/job-1/page-42.json"));
        issues.save(MigrationIssue.of(job.getId(), item.getId(), MigrationIssueSeverity.WARNING,
                "UNSUPPORTED_BLOCK", "/blocks/4"));

        assertThat(items.findByJobIdAndSourceKey(job.getId(), item.getSourceKey()))
                .get()
                .extracting(MigrationItem::getId)
                .isEqualTo(item.getId());
        assertThat(issues.findByJobIdOrderByIdAsc(job.getId())).hasSize(1);
    }

    /** 원본·산출물은 job 삭제를 cascade로 따라간다. H2 스키마에는 FK도 CHECK도 없어 여기서만 확인된다. */
    @Test
    void 원본과_산출물은_job_삭제를_cascade로_따라간다() {
        MigrationJob job = jobs.saveAndFlush(MigrationJob.create(
                MigrationProvider.CONFLUENCE_DC, "wiki.example.com", SPACE_ID, 1L,
                MigrationJobMode.IMPORT));
        sources.saveAndFlush(MigrationSource.of(job.getId(), "https://wiki.example.com", "ENG",
                "pat-token"));
        MigrationItem item = items.saveAndFlush(MigrationItem.pending(
                job.getId(), "10001", "27", CHECKSUM, "dc:content/10001"));
        payloads.saveAndFlush(MigrationPayload.of(item.getId(), MigrationPayloadKind.SNAPSHOT,
                "{\"snapshotVersion\":1}"));

        assertThat(sources.findById(job.getId())).isPresent();
        assertThat(payloads.findByItemIdAndKind(item.getId(), MigrationPayloadKind.SNAPSHOT)).isPresent();

        jobs.deleteById(job.getId());
        jobs.flush();

        assertThat(sources.findById(job.getId())).isEmpty();
        assertThat(payloads.count()).isZero();
    }

    /** M2·M3이 더한 payload 종류가 CHECK 제약을 통과하는지 — 목록에서 빠지면 이관이 통째로 멈춘다. */
    @Test
    void M2_M3이_더한_payload_종류가_저장된다() {
        MigrationJob job = jobs.saveAndFlush(MigrationJob.create(
                MigrationProvider.CONFLUENCE_DC, "wiki.example.com", SPACE_ID, 1L,
                MigrationJobMode.IMPORT));
        MigrationItem item = items.saveAndFlush(MigrationItem.pending(
                job.getId(), "10002", "3", CHECKSUM, "dc:content/10002"));

        for (MigrationPayloadKind kind : MigrationPayloadKind.values()) {
            payloads.saveAndFlush(MigrationPayload.of(item.getId(), kind, "{}"));
        }

        assertThat(payloads.count()).isEqualTo(MigrationPayloadKind.values().length);
    }

    /**
     * V7의 lease CHECK — RUNNING과 소유자·만료가 항상 함께 움직이는지는 실제 Postgres에서만 확인된다.
     */
    @Test
    void worker_lease는_RUNNING_상태와_함께만_존재한다() {
        MigrationJob job = jobs.save(MigrationJob.create(
                MigrationProvider.NOTION, "workspace-acme", SPACE_ID, 1L, MigrationJobMode.IMPORT));
        MigrationItem item = items.saveAndFlush(MigrationItem.pending(
                job.getId(), "page-77", "v1", CHECKSUM, "imports/notion/job-2/page-77.json"));
        Instant claimedAt = Instant.parse("2026-09-05T09:00:00Z");

        assertThat(items.claim(item.getId(), "worker-a", "token-1",
                claimedAt.plusSeconds(300), claimedAt, MigrationItemStatus.RUNNING,
                MigrationItemStatus.PENDING, MigrationItemStatus.RETRY_WAIT)).isEqualTo(1);

        assertThat(items.findById(item.getId()).orElseThrow())
                .satisfies(running -> {
                    assertThat(running.getStatus()).isEqualTo(MigrationItemStatus.RUNNING);
                    assertThat(running.getClaimedBy()).isEqualTo("worker-a");
                    assertThat(running.getClaimToken()).isEqualTo("token-1");
                    assertThat(running.getLeaseExpiresAt()).isEqualTo(claimedAt.plusSeconds(300));
                });

        MigrationItem running = items.findById(item.getId()).orElseThrow();
        running.scheduleRetry("NOTION_TIMEOUT", claimedAt.plusSeconds(60));
        items.saveAndFlush(running);

        assertThat(items.findById(item.getId()).orElseThrow())
                .satisfies(waiting -> {
                    assertThat(waiting.getStatus()).isEqualTo(MigrationItemStatus.RETRY_WAIT);
                    assertThat(waiting.getClaimedBy()).isNull();
                    assertThat(waiting.getClaimToken()).isNull();
                    assertThat(waiting.getLeaseExpiresAt()).isNull();
                });
    }

    /**
     * V2 — 잡 단위 손실(item_id NULL)이 저장되고, 같은 (job, issue_key)가 두 벌로 쌓이지 않는다.
     *
     * H2 스키마에는 부분 유니크 인덱스가 없어 여기서만 확인된다. 이 인덱스가 없으면 링크 정리가
     * 실패할 때마다 같은 행이 새로 쌓여 보고서가 같은 말을 수십 번 반복한다.
     */
    @Test
    void V2_잡_단위_손실은_항목_없이_저장되고_중복되지_않는다() {
        MigrationJob job = jobs.saveAndFlush(MigrationJob.create(
                MigrationProvider.CONFLUENCE_DC, "wiki.example.com", SPACE_ID, 1L,
                MigrationJobMode.IMPORT));

        issues.saveAndFlush(MigrationIssue.ofJob(job.getId(), MigrationIssueSeverity.ERROR,
                "LINK_FIXUP_FAILED", "job:" + job.getId()));

        assertThat(issues.findByJobIdAndItemIdIsNullOrderByIdAsc(job.getId()))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.getItemId()).isNull();
                    assertThat(issue.getCode()).isEqualTo("LINK_FIXUP_FAILED");
                });

        // 같은 (job, issue_key)를 한 번 더 넣으면 부분 유니크 인덱스가 막는다.
        assertThatThrownBy(() -> issues.saveAndFlush(MigrationIssue.ofJob(job.getId(),
                MigrationIssueSeverity.ERROR, "LINK_FIXUP_FAILED", "job:" + job.getId())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** 잡을 지우면 잡 단위 손실도 함께 사라진다 — 항목 FK로는 닿지 않는 행이라 별도 FK가 필요했다. */
    @Test
    void V2_잡_단위_손실은_잡_삭제를_cascade로_따라간다() {
        MigrationJob job = jobs.saveAndFlush(MigrationJob.create(
                MigrationProvider.CONFLUENCE_DC, "wiki.example.com", SPACE_ID, 1L,
                MigrationJobMode.IMPORT));
        issues.saveAndFlush(MigrationIssue.ofJob(job.getId(), MigrationIssueSeverity.ERROR,
                "LINK_FIXUP_FAILED", "page:9001"));

        jobs.deleteById(job.getId());
        jobs.flush();

        assertThat(issues.count()).isZero();
    }

    /**
     * 위키 id에는 FK가 없다 — 그 값이 실재하지 않아도 저장된다. 이것은 사고가 아니라 서비스를
     * 가른 결과이고, 대신 살아 있는지는 import API 조회로 판정한다.
     */
    @Test
    void 위키의_페이지_id는_FK_없이_저장된다() {
        MigrationJob job = jobs.saveAndFlush(MigrationJob.create(
                MigrationProvider.NOTION, "workspace-acme", SPACE_ID, 1L, MigrationJobMode.IMPORT));

        mappings.saveAndFlush(com.platform.migrationservice.migration.model.MigrationObjectMapping.create(
                MigrationProvider.NOTION, "workspace-acme", "page-42", "v3", CHECKSUM,
                999_999L, job.getId()));

        assertThat(mappings.findFirstByTargetPageIdOrderByIdAsc(999_999L)).isPresent();
    }
}
