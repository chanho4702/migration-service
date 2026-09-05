package com.platform.migrationservice.migration.repository;

import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MigrationObjectMappingRepository extends JpaRepository<MigrationObjectMapping, Long> {
    Optional<MigrationObjectMapping> findBySourceKey(String sourceKey);

    /** 이 job이 마지막으로 손댄 대상들 — 잡 마무리 링크 정리(M2)가 훑을 범위다. */
    List<MigrationObjectMapping> findByLastJobIdOrderByIdAsc(Long lastJobId);

    /**
     * 대상 문서 id로 되짚는다 — 제목으로 찾은 문서가 우리가 옮긴 것인지 보고, 맞으면 그때 쓴
     * 마크다운으로 앵커를 대조한다. target_page_id는 유니크가 아니므로 가장 오래된 것 하나만 본다.
     */
    Optional<MigrationObjectMapping> findFirstByTargetPageIdOrderByIdAsc(Long targetPageId);
}
