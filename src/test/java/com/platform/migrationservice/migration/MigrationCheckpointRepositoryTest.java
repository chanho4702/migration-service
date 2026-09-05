package com.platform.migrationservice.migration;

import com.platform.migrationservice.migration.model.MigrationIssue;
import com.platform.migrationservice.migration.model.MigrationIssueSeverity;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class MigrationCheckpointRepositoryTest {

    private static final String CHECKSUM = "a".repeat(64);

    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationObjectMappingRepository mappings;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationJobRepository jobs;

    /**
     * 스페이스·페이지 id는 위키의 것이다 — 이 서비스 DB에는 그 행이 없고 FK도 없다(V1).
     * 여기서 보는 것은 checkpoint 제약(멱등 키·lease·issue 키)뿐이라 임의의 값으로 충분하다.
     */
    private static final long SPACE_ID = 4242L;
    private static final long PAGE_ID = 90001L;

    private int claim(Long itemId, String claimToken, Instant now) {
        return items.claim(itemId, "worker-a", claimToken, now.plusSeconds(300), now,
                MigrationItemStatus.RUNNING, MigrationItemStatus.PENDING, MigrationItemStatus.RETRY_WAIT);
    }

    @BeforeEach
    void clean() {
        issues.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        jobs.deleteAllInBatch();
    }

    @Test
    void job과_item은_단계_retry_checkpoint를_보존한다() {
        MigrationJob job = jobs.save(MigrationJob.create(
                MigrationProvider.NOTION, "workspace-acme", SPACE_ID, 7L, MigrationJobMode.DRY_RUN));
        MigrationItem item = items.save(MigrationItem.pending(
                job.getId(), "page-42", "2026-08-17T00:00:00Z", CHECKSUM,
                "imports/notion/job-1/source/page-42.json"));
        Instant startedAt = Instant.parse("2026-08-17T00:01:00Z");
        Instant retryAt = startedAt.plusSeconds(60);

        job.start(startedAt);
        jobs.saveAndFlush(job);
        assertThat(claim(item.getId(), "token-1", startedAt)).isEqualTo(1);

        MigrationItem running = items.findById(item.getId()).orElseThrow();
        assertThat(running.getClaimedBy()).isEqualTo("worker-a");
        assertThat(running.getClaimToken()).isEqualTo("token-1");
        running.scheduleRetry("NOTION_RATE_LIMITED", retryAt);
        items.saveAndFlush(running);

        MigrationItem waiting = items.findById(item.getId()).orElseThrow();
        assertThat(waiting.getStage()).isEqualTo(MigrationStage.EXTRACT);
        assertThat(waiting.getStatus()).isEqualTo(MigrationItemStatus.RETRY_WAIT);
        assertThat(waiting.getRetryCount()).isEqualTo(1);
        assertThat(waiting.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(waiting.getClaimedBy()).isNull();
        // 점유 UPDATE가 영속성 컨텍스트를 비우므로 같은 인스턴스가 아니다 — id로 대조한다.
        assertThat(jobs.findByStatusOrderByCreatedAtAscIdAsc(MigrationJobStatus.RUNNING))
                .extracting(MigrationJob::getId).containsExactly(job.getId());

        // 재시도 시각 전에는 아무도 집지 못한다.
        assertThat(claim(item.getId(), "token-2", retryAt.minusSeconds(1))).isZero();
        assertThat(claim(item.getId(), "token-3", retryAt)).isEqualTo(1);

        MigrationItem retried = items.findById(item.getId()).orElseThrow();
        retried.completeStage(MigrationStage.NORMALIZE);
        items.saveAndFlush(retried);

        assertThat(items.findById(item.getId()).orElseThrow())
                .satisfies(saved -> {
                    assertThat(saved.getStage()).isEqualTo(MigrationStage.NORMALIZE);
                    assertThat(saved.getStatus()).isEqualTo(MigrationItemStatus.PENDING);
                    assertThat(saved.getNextAttemptAt()).isNull();
                    assertThat(saved.getClaimedBy()).isNull();
                    assertThat(saved.getClaimToken()).isNull();
                    assertThat(saved.getLeaseExpiresAt()).isNull();
                });
    }

    @Test
    void 같은_job의_external_object는_hash_key로_중복을_막는다() {
        MigrationJob job = jobs.save(MigrationJob.create(
                MigrationProvider.CONFLUENCE_DC, "dc-cluster", SPACE_ID, 7L, MigrationJobMode.IMPORT));
        items.saveAndFlush(MigrationItem.pending(
                job.getId(), "content-10001", "27", CHECKSUM, "imports/confluence/job-1/content-10001.xhtml"));

        assertThatThrownBy(() -> items.saveAndFlush(MigrationItem.pending(
                job.getId(), "content-10001", "27", CHECKSUM, "imports/confluence/job-1/duplicate.xhtml")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void issue와_object_mapping에는_본문_대신_code_path_checksum만_남긴다() {
        MigrationJob job = jobs.save(MigrationJob.create(
                MigrationProvider.NOTION, "workspace-acme", SPACE_ID, 7L, MigrationJobMode.IMPORT));
        MigrationItem item = items.save(MigrationItem.pending(
                job.getId(), "page-42", "v3", CHECKSUM, "imports/notion/job-2/page-42.json"));
        MigrationIssue issue = issues.save(MigrationIssue.of(
                job.getId(), item.getId(), MigrationIssueSeverity.WARNING,
                "UNSUPPORTED_BLOCK", "/blocks/4"));
        MigrationObjectMapping mapping = mappings.save(MigrationObjectMapping.create(
                MigrationProvider.NOTION, "workspace-acme", "page-42", "v3", CHECKSUM,
                PAGE_ID, job.getId()));

        assertThat(issues.findByJobIdOrderByIdAsc(job.getId())).containsExactly(issue);
        assertThat(issues.findByItemIdAndIssueKey(item.getId(), MigrationIssue.issueKeyFor(
                "UNSUPPORTED_BLOCK", "/blocks/4"))).contains(issue);
        assertThat(issue.getIssueKey()).matches("[a-f0-9]{64}");
        assertThat(mapping.getSourceKey()).matches("[a-f0-9]{64}");
        assertThat(mapping.getSourceKey()).doesNotContain("page-42", "workspace-acme");
        assertThat(mappings.findBySourceKey(MigrationObjectMapping.sourceKeyFor(
                MigrationProvider.NOTION, "workspace-acme", "page-42"))).contains(mapping);
        assertThat(MigrationObjectMapping.sourceKeyFor(MigrationProvider.NOTION, "a\nb", "c"))
                .isNotEqualTo(MigrationObjectMapping.sourceKeyFor(MigrationProvider.NOTION, "a", "b\nc"));
    }
}
