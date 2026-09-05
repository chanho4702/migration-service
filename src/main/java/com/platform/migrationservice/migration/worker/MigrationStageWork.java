package com.platform.migrationservice.migration.worker;

import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationStage;

/**
 * 한 번의 stage 실행에 필요한 값만 담은 불변 입력이다. 엔티티를 그대로 넘기지 않으므로
 * handler는 트랜잭션 밖에서 안전하게 네트워크 I/O를 할 수 있다.
 */
public record MigrationStageWork(
        Long jobId,
        Long itemId,
        String claimToken,
        MigrationProvider provider,
        String sourceInstanceId,
        MigrationJobMode mode,
        Long targetSpaceId,
        /** 잡 요청자. 위키 import API의 X-Actor-Id이자 대조 실패 시의 기본 주체다. */
        Long requestedBy,
        MigrationStage stage,
        String externalObjectId,
        String sourceVersion,
        String sourceChecksum,
        String payloadRef,
        Long targetPageId,
        /** 원본이 정한 형제 순서(M2). null이면 발견 순서를 그대로 쓴다. */
        Integer siblingOrder,
        int attempt) {

    public boolean dryRun() {
        return mode == MigrationJobMode.DRY_RUN;
    }
}
