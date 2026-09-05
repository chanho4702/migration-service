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
     * 문서를 만들자마자 대상 id를 원장에 못박는다 — **즉시 커밋된다**(REQUIRES_NEW).
     *
     * 이 호출이 없으면 문서를 만든 뒤 첨부·제한·댓글에서 실패했을 때 원장에 아무것도 남지 않는다.
     * 재시도(또는 리스 만료 재실행)는 "옮긴 적 없다"로 보고 같은 원본으로 문서를 하나 더 만든다 —
     * 이관에서 가장 되돌리기 어려운 사고가 그 중복이다.
     *
     * checksum은 넣지 않는다(새 행이면 {@code PENDING_CHECKSUM}, 기존 행이면 옛 값 유지).
     * 그래야 재시도가 멱등 분기에서 멈추지 않고 같은 문서를 갱신 경로로 마저 마무리한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bindTargetPage(MigrationProvider provider, String sourceInstanceId,
                               String externalObjectId, Long targetPageId, Long jobId) {
        String sourceKey = MigrationObjectMapping.sourceKeyFor(provider, sourceInstanceId, externalObjectId);
        mappings.findBySourceKey(sourceKey)
                .ifPresentOrElse(
                        existing -> {
                            existing.bindTargetPage(targetPageId, jobId);
                            mappings.save(existing);
                        },
                        () -> mappings.saveAndFlush(MigrationObjectMapping.createPending(provider,
                                sourceInstanceId, externalObjectId, targetPageId, jobId)));
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
