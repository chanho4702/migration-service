package com.platform.migrationservice.migration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 변환 손실·경고의 구조화 기록이다. 원본 값이나 본문 대신 code와 source JSON path만 보관한다.
 */
@Entity
@Table(name = "migration_issue", uniqueConstraints =
        @UniqueConstraint(name = "uk_migration_issue_key", columnNames = {"item_id", "issue_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private Long jobId;

    @Column(name = "item_id", nullable = false, updatable = false)
    private Long itemId;

    @Column(name = "issue_key", nullable = false, length = 64, updatable = false)
    private String issueKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private MigrationIssueSeverity severity;

    @Column(nullable = false, length = 128, updatable = false)
    private String code;

    @Column(name = "source_path", nullable = false, length = 1024, updatable = false)
    private String sourcePath;

    @Column(name = "occurrence_count", nullable = false)
    private Integer occurrenceCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    public static MigrationIssue of(Long jobId, Long itemId, MigrationIssueSeverity severity,
                                    String code, String sourcePath) {
        MigrationIssue issue = new MigrationIssue();
        issue.jobId = MigrationSourceKey.require(jobId, "jobId");
        issue.itemId = MigrationSourceKey.require(itemId, "itemId");
        issue.severity = MigrationSourceKey.require(severity, "severity");
        issue.code = MigrationSourceKey.requireText(code, "code", 128);
        issue.sourcePath = MigrationSourceKey.requireText(sourcePath, "sourcePath", 1024);
        issue.issueKey = MigrationSourceKey.issue(issue.code, issue.sourcePath);
        issue.occurrenceCount = 1;
        return issue;
    }

    public static String issueKeyFor(String code, String sourcePath) {
        return MigrationSourceKey.issue(code, sourcePath);
    }

    public void incrementOccurrence() {
        occurrenceCount += 1;
    }
}
