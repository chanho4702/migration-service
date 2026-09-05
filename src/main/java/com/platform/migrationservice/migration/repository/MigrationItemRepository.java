package com.platform.migrationservice.migration.repository;

import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.report.MigrationStageCount;
import com.platform.migrationservice.migration.report.MigrationStatusCount;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MigrationItemRepository extends JpaRepository<MigrationItem, Long> {
    Optional<MigrationItem> findByJobIdAndSourceKey(Long jobId, String sourceKey);

    List<MigrationItem> findByJobIdAndStatusOrderByIdAsc(Long jobId, MigrationItemStatus status);

    boolean existsByJobIdAndStatusIn(Long jobId, Collection<MigrationItemStatus> statuses);

    long countByJobId(Long jobId);

    /**
     * 지금 처리할 수 있는 item id. 행 잠금을 걸지 않고 id만 훑는다 — `FOR UPDATE SKIP LOCKED`는
     * H2에 없어 테스트 DB와 운영 DB가 갈라진다. 실제 점유 경합은 {@link #claim} 조건부 UPDATE가 건다.
     */
    @Query("""
            select i.id from MigrationItem i
             where i.jobId = :jobId
               and (i.status = :pending
                    or (i.status = :retryWait and i.nextAttemptAt <= :now))
             order by i.id asc
            """)
    List<Long> findClaimableIds(@Param("jobId") Long jobId,
                                @Param("pending") MigrationItemStatus pending,
                                @Param("retryWait") MigrationItemStatus retryWait,
                                @Param("now") Instant now,
                                Pageable pageable);

    /**
     * 조건부 UPDATE로 점유한다. 엔티티를 읽어 낙관적 락으로 겨루면 진 쪽이 던지는 예외가 트랜잭션을
     * rollback-only로 만들어 같은 트랜잭션에서 다음 후보를 집을 수 없다. 여기서는 갱신된 행 수만 보고
     * 진 경우 조용히 다음 후보로 넘어간다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MigrationItem i
               set i.status = :running, i.claimedBy = :workerId, i.claimToken = :claimToken,
                   i.leaseExpiresAt = :leaseUntil, i.nextAttemptAt = null, i.updatedAt = :now
             where i.id = :id
               and (i.status = :pending
                    or (i.status = :retryWait and i.nextAttemptAt <= :now))
            """)
    int claim(@Param("id") Long id,
              @Param("workerId") String workerId,
              @Param("claimToken") String claimToken,
              @Param("leaseUntil") Instant leaseUntil,
              @Param("now") Instant now,
              @Param("running") MigrationItemStatus running,
              @Param("pending") MigrationItemStatus pending,
              @Param("retryWait") MigrationItemStatus retryWait);

    List<MigrationItem> findByStatusAndLeaseExpiresAtLessThanEqualOrderByIdAsc(
            MigrationItemStatus status, Instant cutoff, Pageable pageable);

    /**
     * 실패 항목 표(W29). status·stage는 둘 다 선택이라 null이면 그 조건을 무시한다 —
     * 필터 조합마다 파생 쿼리를 하나씩 두면 네 벌이 되고, 하나만 고쳐도 나머지가 어긋난다.
     */
    @Query("""
            select i from MigrationItem i
             where i.jobId = :jobId
               and (:status is null or i.status = :status)
               and (:stage is null or i.stage = :stage)
             order by i.id asc
            """)
    org.springframework.data.domain.Page<MigrationItem> findFiltered(
            @Param("jobId") Long jobId,
            @Param("status") MigrationItemStatus status,
            @Param("stage") com.platform.migrationservice.migration.model.MigrationStage stage,
            Pageable pageable);

    @Query("""
            select new com.platform.migrationservice.migration.report.MigrationStatusCount(i.status, count(i))
              from MigrationItem i
             where i.jobId = :jobId
             group by i.status
            """)
    List<MigrationStatusCount> countByStatus(@Param("jobId") Long jobId);

    @Query("""
            select new com.platform.migrationservice.migration.report.MigrationStageCount(i.stage, count(i))
              from MigrationItem i
             where i.jobId = :jobId
             group by i.stage
            """)
    List<MigrationStageCount> countByStage(@Param("jobId") Long jobId);
}
