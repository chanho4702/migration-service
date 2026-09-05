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

@Entity
@Table(name = "migration_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_migration_item_source", columnNames = {"job_id", "source_key"}),
        @UniqueConstraint(name = "uk_migration_item_job_id", columnNames = {"job_id", "id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private Long jobId;

    @Column(name = "source_key", nullable = false, length = 64, updatable = false)
    private String sourceKey;

    @Column(name = "external_object_id", nullable = false, length = 512, updatable = false)
    private String externalObjectId;

    @Column(name = "source_version", length = 100, updatable = false)
    private String sourceVersion;

    @Column(name = "source_checksum", nullable = false, length = 64, updatable = false)
    private String sourceChecksum;

    @Column(name = "payload_ref", nullable = false, length = 1024, updatable = false)
    private String payloadRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MigrationStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MigrationItemStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "target_page_id")
    private Long targetPageId;

    /**
     * 원본에서 같은 부모 아래 몇 번째였는가(M2). 모르면 null이고, 그때는 발견 순서를 그대로 쓴다.
     * 재이관에서 원본 순서가 바뀌면 이 값만 갱신해 대상 문서를 옮기지 않고 정렬만 맞춘다.
     */
    @Column(name = "sibling_order")
    private Integer siblingOrder;

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    public static MigrationItem pending(Long jobId, String externalObjectId, String sourceVersion,
                                        String sourceChecksum, String payloadRef) {
        return pending(jobId, externalObjectId, sourceVersion, sourceChecksum, payloadRef, null);
    }

    public static MigrationItem pending(Long jobId, String externalObjectId, String sourceVersion,
                                        String sourceChecksum, String payloadRef, Integer siblingOrder) {
        MigrationItem item = new MigrationItem();
        item.jobId = MigrationSourceKey.require(jobId, "jobId");
        item.externalObjectId = MigrationSourceKey.requireText(externalObjectId, "externalObjectId", 512);
        item.sourceKey = MigrationSourceKey.item(item.externalObjectId);
        item.sourceVersion = optionalText(sourceVersion, "sourceVersion", 100);
        item.sourceChecksum = MigrationSourceKey.requireChecksum(sourceChecksum);
        item.payloadRef = MigrationSourceKey.requireText(payloadRef, "payloadRef", 1024);
        item.stage = MigrationStage.EXTRACT;
        item.status = MigrationItemStatus.PENDING;
        item.retryCount = 0;
        item.siblingOrder = siblingOrder;
        return item;
    }

    /**
     * 원본 형제 순서를 다시 적는다. 바뀐 값이 있을 때만 true라, 재발견이 아무것도 달라지지 않은
     * 스페이스에 쓸데없는 UPDATE를 만들지 않는다.
     */
    public boolean applySiblingOrder(Integer siblingOrder) {
        if (java.util.Objects.equals(this.siblingOrder, siblingOrder)) {
            return false;
        }
        this.siblingOrder = siblingOrder;
        return true;
    }

    public static String sourceKeyFor(String externalObjectId) {
        return MigrationSourceKey.item(externalObjectId);
    }

    /** 점유 중이지만 lease가 만료돼 소유 노드를 더 이상 신뢰할 수 없는 상태. */
    public boolean isLeaseExpired(Instant now) {
        return status == MigrationItemStatus.RUNNING
                && !leaseExpiresAt.isAfter(MigrationSourceKey.require(now, "now"));
    }

    /** lease가 만료된 RUNNING item을 재시도 대기로 되돌린다. 아직 유효하면 false. */
    public boolean releaseExpiredLease(String errorCode, Instant now, Instant nextAttemptAt) {
        Instant observedAt = MigrationSourceKey.require(now, "now");
        if (status != MigrationItemStatus.RUNNING || leaseExpiresAt.isAfter(observedAt)) {
            return false;
        }
        this.lastErrorCode = MigrationSourceKey.requireText(errorCode, "errorCode", 128);
        this.nextAttemptAt = MigrationSourceKey.require(nextAttemptAt, "nextAttemptAt");
        this.retryCount += 1;
        status = MigrationItemStatus.RETRY_WAIT;
        releaseLease();
        return true;
    }

    public void completeStage(MigrationStage nextStage) {
        requireRunning();
        MigrationStage next = MigrationSourceKey.require(nextStage, "nextStage");
        if (next.ordinal() != stage.ordinal() + 1) {
            throw new IllegalStateException("Migration stage must advance exactly once");
        }
        stage = next;
        status = next == MigrationStage.DONE ? MigrationItemStatus.COMPLETED : MigrationItemStatus.PENDING;
        releaseLease();
    }

    public void scheduleRetry(String errorCode, Instant nextAttemptAt) {
        requireRunning();
        this.lastErrorCode = MigrationSourceKey.requireText(errorCode, "errorCode", 128);
        this.nextAttemptAt = MigrationSourceKey.require(nextAttemptAt, "nextAttemptAt");
        retryCount += 1;
        status = MigrationItemStatus.RETRY_WAIT;
        releaseLease();
    }

    public void deadLetter(String errorCode, Instant now) {
        requireRunning();
        lastErrorCode = MigrationSourceKey.requireText(errorCode, "errorCode", 128);
        deadLetteredAt = MigrationSourceKey.require(now, "now");
        nextAttemptAt = null;
        status = MigrationItemStatus.DEAD_LETTER;
        releaseLease();
    }

    public void bindTargetPage(Long pageId) {
        targetPageId = MigrationSourceKey.require(pageId, "pageId");
    }

    private void releaseLease() {
        claimedBy = null;
        claimToken = null;
        leaseExpiresAt = null;
    }

    private void requireRunning() {
        if (status != MigrationItemStatus.RUNNING) {
            throw new IllegalStateException("Migration item must be RUNNING but was " + status);
        }
    }

    private static String optionalText(String value, String name, int maxLength) {
        if (value == null) {
            return null;
        }
        return MigrationSourceKey.requireText(value, name, maxLength);
    }
}
