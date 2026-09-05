package com.platform.migrationservice.migration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "migration_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private MigrationProvider provider;

    @Column(name = "source_instance_id", nullable = false, length = 255, updatable = false)
    private String sourceInstanceId;

    @Column(name = "target_space_id")
    private Long targetSpaceId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private MigrationJobMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MigrationJobStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    public static MigrationJob create(MigrationProvider provider, String sourceInstanceId,
                                      Long targetSpaceId, Long requestedBy, MigrationJobMode mode) {
        MigrationJob job = new MigrationJob();
        job.provider = MigrationSourceKey.require(provider, "provider");
        job.sourceInstanceId = MigrationSourceKey.requireText(sourceInstanceId, "sourceInstanceId", 255);
        job.targetSpaceId = MigrationSourceKey.require(targetSpaceId, "targetSpaceId");
        job.requestedBy = MigrationSourceKey.require(requestedBy, "requestedBy");
        job.mode = MigrationSourceKey.require(mode, "mode");
        job.status = MigrationJobStatus.PENDING;
        return job;
    }

    public void start(Instant now) {
        requireStatus(MigrationJobStatus.PENDING);
        startedAt = MigrationSourceKey.require(now, "now");
        status = MigrationJobStatus.RUNNING;
    }

    public void complete(Instant now) {
        requireStatus(MigrationJobStatus.RUNNING);
        completedAt = MigrationSourceKey.require(now, "now");
        status = MigrationJobStatus.COMPLETED;
    }

    public void fail(Instant now) {
        if (status != MigrationJobStatus.PENDING && status != MigrationJobStatus.RUNNING) {
            throw new IllegalStateException("Migration job cannot fail from " + status);
        }
        Instant occurredAt = MigrationSourceKey.require(now, "now");
        if (startedAt == null) {
            startedAt = occurredAt;
        }
        completedAt = occurredAt;
        status = MigrationJobStatus.FAILED;
    }

    public void cancel(Instant now) {
        if (status != MigrationJobStatus.PENDING && status != MigrationJobStatus.RUNNING) {
            throw new IllegalStateException("Migration job cannot cancel from " + status);
        }
        Instant occurredAt = MigrationSourceKey.require(now, "now");
        if (startedAt == null) {
            startedAt = occurredAt;
        }
        completedAt = occurredAt;
        status = MigrationJobStatus.CANCELLED;
    }

    private void requireStatus(MigrationJobStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Migration job must be " + expected + " but was " + status);
        }
    }
}
