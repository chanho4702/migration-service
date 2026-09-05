package com.platform.migrationservice.migration.repository;

import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, Long> {
    List<MigrationJob> findByStatusOrderByCreatedAtAscIdAsc(MigrationJobStatus status);

    /** 관리 화면 목록 — 최신순. 상한은 호출부가 Limit으로 준다. */
    List<MigrationJob> findAllByOrderByIdDesc(org.springframework.data.domain.Limit limit);

    /**
     * job의 상태 전이(등록 마감·취소)와 그 상태에 기대는 작업(원본 등록·item 점유)을 행 잠금으로
     * 직렬화한다. 잠금 없이 읽고 판단하면 `start`/`cancel`이 그 사이에 커밋돼, 마감된 job에 원본이
     * 더 들어가거나 취소된 job의 item을 worker가 집는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from MigrationJob j where j.id = :id")
    Optional<MigrationJob> findByIdForUpdate(@Param("id") Long id);
}
