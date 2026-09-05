package com.platform.migrationservice.migration.confluence.link;

import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import com.platform.migrationservice.migration.model.MigrationIssueSeverity;
import com.platform.migrationservice.migration.repository.MigrationSourceRepository;
import com.platform.migrationservice.migration.worker.MigrationJobIssueWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 잡이 끝난 뒤 한 번 도는 링크 정리 pass(M2 §4.2).
 *
 * 왜 필요한가: 문서를 만드는 시점에는 아직 안 옮긴 문서를 가리키는 링크가 있다. 그 순간 원본 URL로
 * 두면 사용자가 이관된 위키에서 원본 사이트로 튕겨 나가고, 그 링크가 미완이라는 사실이 아무 데도
 * 남지 않는다. 그래서 RESOLVE는 임시 스킴 {@code dc-page:}로 표시만 해 두고, 모든 문서가 자리를
 * 잡은 지금 다시 해석한다.
 *
 * 여기서 남은 것은 정말로 못 찾은 링크다 — 원본 절대 URL로 되돌리고 LINK_UNRESOLVED로 보고한다.
 * 깨진 링크를 남기는 것보다 원본으로 가는 링크가 낫고, 무엇보다 보고서에 사실이 남는다.
 *
 * 정리는 **새 리비전**으로 남는다(변경 요약 "이관 링크 정리"). 첨부 참조 정리와 달리 문서를 쓴
 * 시점 이후에 일어난 별개의 변경이고, 사용자가 그 사이에 손댔을 수도 있어 이력이 필요하다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationLinkFixupService {

    private final MigrationJobRepository jobs;
    private final MigrationSourceRepository sources;
    private final MigrationObjectMappingRepository mappings;
    private final MigrationLinkFixupWriter writer;
    private final MigrationJobIssueWriter jobIssues;

    /**
     * 이 잡이 만든 문서들의 임시 링크를 해석한다. 실패해도 잡의 결말을 바꾸지 않는다 —
     * 링크 정리가 안 됐다고 옮긴 문서 500건을 되돌릴 수는 없다.
     *
     * 다만 **조용히 지나가지도 않는다**. 문서 하나가 실패하면 그 문서를 짚어 잡 단위 손실로
     * 남긴다 — 로그로만 두면 보고서는 "완료"인데 링크는 원본 사이트로 튕기는 상태가 된다.
     *
     * @return 손댄 문서 수와 실패한 문서 수
     */
    public Result run(long jobId) {
        MigrationJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getMode() != MigrationJobMode.IMPORT) {
            // dry-run은 문서를 만들지 않았으니 정리할 링크도 없다.
            return new Result(0, 0);
        }
        String baseUrl = sources.findById(jobId).map(source -> source.getBaseUrl()).orElse(null);
        MigrationLinkResolver.Context context = new MigrationLinkResolver.Context(job.getProvider(),
                job.getSourceInstanceId(), baseUrl, job.getTargetSpaceId(), job.getRequestedBy(), true);

        int touched = 0;
        int failed = 0;
        for (MigrationObjectMapping mapping : mappings.findByLastJobIdOrderByIdAsc(jobId)) {
            if (mapping.getTargetPageId() == null) {
                continue;
            }
            try {
                if (writer.fixOne(job, mapping, context)) {
                    touched++;
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("링크 정리 실패 — 이 문서만 건너뛴다: job={} page={}",
                        jobId, mapping.getTargetPageId(), exception);
                jobIssues.record(jobId, MigrationIssueSeverity.ERROR,
                        MigrationJobIssueWriter.LINK_FIXUP_FAILED,
                        "page:" + mapping.getTargetPageId());
            }
        }
        if (touched > 0) {
            log.info("이관 링크 정리 완료: job={} 문서={}건", jobId, touched);
        }
        if (failed > 0) {
            log.warn("이관 링크 정리에서 {}건이 실패했다: job={}", failed, jobId);
        }
        return new Result(touched, failed);
    }

    /** @param touched 본문이 실제로 바뀐 문서 수 @param failed 정리하지 못한 문서 수 */
    public record Result(int touched, int failed) {
    }
}
