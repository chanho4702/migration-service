package com.platform.migrationservice.migration.worker;

import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 객체 → 내부 페이지 매핑을 별도 트랜잭션에서 쓴다. 같은 원본을 두 job이 동시에 끝내면
 * 한쪽이 unique 제약에 걸리는데, 그 롤백이 stage 성공 기록(item 전진·issue)까지 되돌리면
 * 성공한 handler가 실패로 둔갑한다. 실패를 이 트랜잭션 안에 가두고, 호출자가 한 번 더 부르면
 * 그때는 행이 있으므로 update 경로로 수렴한다.
 */
@Component
@RequiredArgsConstructor
public class MigrationObjectMappingWriter {

    private final MigrationObjectMappingRepository mappings;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(MigrationProvider provider, String sourceInstanceId, String externalObjectId,
                       String sourceVersion, String sourceChecksum, Long targetPageId, Long jobId) {
        String sourceKey = MigrationObjectMapping.sourceKeyFor(provider, sourceInstanceId, externalObjectId);
        mappings.findBySourceKey(sourceKey)
                .ifPresentOrElse(
                        existing -> {
                            existing.update(sourceVersion, sourceChecksum, targetPageId, jobId);
                            mappings.save(existing);
                        },
                        () -> mappings.saveAndFlush(MigrationObjectMapping.create(provider, sourceInstanceId,
                                externalObjectId, sourceVersion, sourceChecksum, targetPageId, jobId)));
    }

    /**
     * 이관한 댓글 하나의 매핑(M3). 페이지와 같은 이유로 별도 트랜잭션이다 — 이 행 하나가
     * 제약에 걸려도 이미 만들어진 댓글까지 되돌아가면 재실행이 같은 댓글을 또 단다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertComment(MigrationProvider provider, String sourceInstanceId,
                              String sourceCommentId, String sourceChecksum, Long targetCommentId,
                              Long jobId) {
        String externalObjectId = MigrationObjectMapping.commentObjectId(sourceCommentId);
        String sourceKey = MigrationObjectMapping.sourceKeyFor(provider, sourceInstanceId, externalObjectId);
        mappings.findBySourceKey(sourceKey)
                .ifPresentOrElse(
                        existing -> {
                            existing.updateComment(sourceChecksum, targetCommentId, jobId);
                            mappings.save(existing);
                        },
                        () -> mappings.saveAndFlush(MigrationObjectMapping.createComment(provider,
                                sourceInstanceId, externalObjectId, sourceChecksum, targetCommentId, jobId)));
    }
}
