package com.platform.migrationservice.migration;

import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import com.platform.migrationservice.migration.dto.MigrationItemEnqueueRequest;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원본 등록의 삽입만 담당한다. 별도 트랜잭션인 이유는 두 가지다 — job 행을 잠가 `start`와
 * 경쟁하지 않기 위해서, 그리고 동시 재시도가 unique 제약에 걸렸을 때 그 롤백이 호출자의
 * 멱등 재조회까지 무효로 만들지 않게 하기 위해서다.
 */
@Component
@RequiredArgsConstructor
public class MigrationItemIntake {

    private final MigrationJobRepository jobs;
    private final MigrationItemRepository items;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MigrationItem insert(long jobId, MigrationItemEnqueueRequest req) {
        MigrationJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new NotFoundException("마이그레이션 작업을 찾을 수 없습니다: " + jobId));
        if (job.getStatus() != MigrationJobStatus.PENDING) {
            throw new ConflictException("이미 시작된 job에는 원본을 추가할 수 없습니다: " + job.getStatus());
        }
        return items.saveAndFlush(MigrationItem.pending(jobId, req.externalObjectId(),
                req.sourceVersion(), req.sourceChecksum(), req.payloadRef(), req.siblingOrder()));
    }

    /**
     * 이미 담긴 항목의 원본 형제 순서만 다시 적는다(M2).
     *
     * 재발견은 새 항목만 담는 것이 규칙이라 기존 항목은 손대지 않는데, 순서는 예외다 — 원본에서
     * 문서를 위아래로 옮겨 놓고 다시 이관하면 그 결과가 반영돼야 한다. 본문·버전은 여전히
     * 건드리지 않으므로 진행 중인 job의 처리 순서에는 영향이 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean updateSiblingOrder(long itemId, Integer siblingOrder) {
        MigrationItem item = items.findById(itemId).orElse(null);
        if (item == null || !item.applySiblingOrder(siblingOrder)) {
            return false;
        }
        items.saveAndFlush(item);
        return true;
    }
}
