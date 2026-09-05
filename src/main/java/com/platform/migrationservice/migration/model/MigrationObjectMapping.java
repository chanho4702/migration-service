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
@Table(name = "migration_object_map")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationObjectMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_key", nullable = false, unique = true, length = 64, updatable = false)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private MigrationProvider provider;

    @Column(name = "source_instance_id", nullable = false, length = 255, updatable = false)
    private String sourceInstanceId;

    @Column(name = "external_object_id", nullable = false, length = 512, updatable = false)
    private String externalObjectId;

    @Column(name = "source_version", length = 100)
    private String sourceVersion;

    @Column(name = "source_checksum", nullable = false, length = 64)
    private String sourceChecksum;

    @Column(name = "target_page_id")
    private Long targetPageId;

    /**
     * 이관한 댓글의 대상(V36). target_page_id를 재활용하지 않는 이유는 그 컬럼이 page(id) FK라
     * 댓글 id를 거부하기 때문이다. 댓글 행은 target_page_id가 NULL이고, 링크 정리 pass는 그 조건으로
     * 이미 건너뛴다 — 순회에 섞이지 않는다.
     */
    @Column(name = "target_comment_id")
    private Long targetCommentId;

    @Column(name = "last_job_id")
    private Long lastJobId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    public static MigrationObjectMapping create(MigrationProvider provider, String sourceInstanceId,
                                                String externalObjectId, String sourceVersion,
                                                String sourceChecksum, Long targetPageId, Long lastJobId) {
        MigrationObjectMapping mapping = new MigrationObjectMapping();
        mapping.provider = MigrationSourceKey.require(provider, "provider");
        mapping.sourceInstanceId = MigrationSourceKey.requireText(sourceInstanceId, "sourceInstanceId", 255);
        mapping.externalObjectId = MigrationSourceKey.requireText(externalObjectId, "externalObjectId", 512);
        mapping.sourceKey = MigrationSourceKey.object(provider, sourceInstanceId, externalObjectId);
        mapping.update(sourceVersion, sourceChecksum, targetPageId, lastJobId);
        return mapping;
    }

    /**
     * 이관한 댓글 한 건의 매핑(M3). externalObjectId는 {@code comment:{원본 id}}라 같은 원본의
     * 페이지 매핑과 키가 겹치지 않는다.
     */
    /** 문서를 막 만들었을 때의 행 — 아직 마무리 전이라 checksum 자리는 {@link #PENDING_CHECKSUM}이다. */
    public static MigrationObjectMapping createPending(MigrationProvider provider, String sourceInstanceId,
                                                       String externalObjectId, Long targetPageId,
                                                       Long lastJobId) {
        return create(provider, sourceInstanceId, externalObjectId, null, PENDING_CHECKSUM,
                targetPageId, lastJobId);
    }

    public static MigrationObjectMapping createComment(MigrationProvider provider, String sourceInstanceId,
                                                       String externalObjectId, String sourceChecksum,
                                                       Long targetCommentId, Long lastJobId) {
        MigrationObjectMapping mapping = new MigrationObjectMapping();
        mapping.provider = MigrationSourceKey.require(provider, "provider");
        mapping.sourceInstanceId = MigrationSourceKey.requireText(sourceInstanceId, "sourceInstanceId", 255);
        mapping.externalObjectId = MigrationSourceKey.requireText(externalObjectId, "externalObjectId", 512);
        mapping.sourceKey = MigrationSourceKey.object(provider, sourceInstanceId, externalObjectId);
        mapping.updateComment(sourceChecksum, targetCommentId, lastJobId);
        return mapping;
    }

    public static String sourceKeyFor(MigrationProvider provider, String sourceInstanceId,
                                      String externalObjectId) {
        return MigrationSourceKey.object(provider, sourceInstanceId, externalObjectId);
    }

    /** 댓글 매핑의 외부 키 — 페이지 id와 같은 숫자를 써도 겹치지 않게 접두어를 붙인다. */
    public static String commentObjectId(String sourceCommentId) {
        return "comment:" + sourceCommentId;
    }

    /**
     * 문서는 만들었지만 뒤 단계(첨부·제한·댓글)가 아직 끝나지 않았다는 표시.
     *
     * 어떤 원본의 SHA-256도 이 값이 될 수 없으므로, 이 checksum이 남아 있으면 재실행은 늘
     * "원본이 바뀌었다"로 보고 같은 문서를 갱신 경로로 다시 마무리한다.
     */
    public static final String PENDING_CHECKSUM = "0".repeat(64);

    /**
     * 대상 문서만 먼저 묶는다. **checksum은 건드리지 않는다** — 그래야 뒤 단계가 실패했을 때
     * 재시도가 "이미 다 옮겼다"로 착각하지 않고 같은 문서를 갱신 경로로 마무리한다.
     */
    public void bindTargetPage(Long targetPageId, Long lastJobId) {
        this.targetPageId = MigrationSourceKey.require(targetPageId, "targetPageId");
        this.lastJobId = MigrationSourceKey.require(lastJobId, "lastJobId");
    }

    public void update(String sourceVersion, String sourceChecksum, Long targetPageId, Long lastJobId) {
        this.sourceVersion = sourceVersion == null ? null
                : MigrationSourceKey.requireText(sourceVersion, "sourceVersion", 100);
        this.sourceChecksum = MigrationSourceKey.requireChecksum(sourceChecksum);
        this.targetPageId = MigrationSourceKey.require(targetPageId, "targetPageId");
        this.lastJobId = MigrationSourceKey.require(lastJobId, "lastJobId");
    }

    public void updateComment(String sourceChecksum, Long targetCommentId, Long lastJobId) {
        this.sourceChecksum = MigrationSourceKey.requireChecksum(sourceChecksum);
        this.targetCommentId = MigrationSourceKey.require(targetCommentId, "targetCommentId");
        this.lastJobId = MigrationSourceKey.require(lastJobId, "lastJobId");
    }
}
