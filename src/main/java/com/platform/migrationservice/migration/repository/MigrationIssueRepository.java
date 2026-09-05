package com.platform.migrationservice.migration.repository;

import com.platform.migrationservice.migration.model.MigrationIssue;
import com.platform.migrationservice.migration.report.MigrationIssueSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MigrationIssueRepository extends JpaRepository<MigrationIssue, Long> {
    List<MigrationIssue> findByJobIdOrderByIdAsc(Long jobId);

    Optional<MigrationIssue> findByItemIdAndIssueKey(Long itemId, String issueKey);

    /**
     * 한 항목의 정규화 손실 기록을 지운다. MEDIA_COPY가 자산을 해결한 뒤 IR을 다시 만들면
     * 두 번째 결과가 정본이라, 첫 패스가 남긴 "못 옮겼다"를 남겨 두면 보고서가 거짓말을 한다.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MigrationIssue s where s.itemId = :itemId and s.code like concat(:prefix, '%')")
    int deleteByItemIdAndCodeStartingWith(@Param("itemId") Long itemId, @Param("prefix") String prefix);

    /**
     * 손실 보고서의 code별 집계. 대표 위치는 min(source_path)로 뽑는다 — 어느 것이든 하나면
     * 충분하고, 집계 쿼리 안에서 결정되므로 code마다 행을 한 번 더 읽지 않는다.
     */
    @Query("""
            select new com.platform.migrationservice.migration.report.MigrationIssueSummary(
                       s.severity, s.code, count(s), sum(s.occurrenceCount), min(s.sourcePath))
              from MigrationIssue s
             where s.jobId = :jobId
             group by s.severity, s.code
            """)
    List<MigrationIssueSummary> summarize(@Param("jobId") Long jobId);
}
