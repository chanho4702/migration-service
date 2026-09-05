package com.platform.migrationservice.migration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * job 하나가 바라보는 원본 DC 사이트. job과 1:1이라 job_id가 곧 PK다.
 *
 * authToken은 평문 컬럼이다 — 지금의 보호막은 DB 접근 통제뿐이고, 암호화 키를 어디에 두고 어떻게
 * 교체할지는 후속 ADR에서 정한다. 어떤 응답 DTO에도 이 값을 싣지 않는다(기획 P8): 화면에서 한 번
 * 입력한 토큰은 다시 보이지 않는다.
 */
@Entity
@Table(name = "migration_source")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationSource {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private Long jobId;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "space_key", nullable = false, length = 255)
    private String spaceKey;

    @Column(name = "auth_token", nullable = false, columnDefinition = "text")
    private String authToken;

    @Column(name = "discovered_count", nullable = false)
    private Integer discoveredCount;

    @Column(name = "discovered_at")
    private Instant discoveredAt;

    @Column(name = "source_space_name", length = 255)
    private String sourceSpaceName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    public static MigrationSource of(Long jobId, String baseUrl, String spaceKey, String authToken) {
        MigrationSource source = new MigrationSource();
        source.jobId = require(jobId, "jobId");
        source.baseUrl = requireText(baseUrl, "baseUrl", 512);
        source.spaceKey = requireText(spaceKey, "spaceKey", 255);
        source.authToken = requireText(authToken, "authToken", 4096);
        source.discoveredCount = 0;
        return source;
    }

    /** 발견 결과를 기록한다. 재발견은 누적이 아니라 그 시점의 총계로 덮어쓴다. */
    public void recordDiscovery(int discoveredCount, String sourceSpaceName, Instant now) {
        if (discoveredCount < 0) {
            throw new IllegalArgumentException("discoveredCount must not be negative");
        }
        this.discoveredCount = discoveredCount;
        this.discoveredAt = require(now, "now");
        if (sourceSpaceName != null && !sourceSpaceName.isBlank()) {
            String trimmed = sourceSpaceName.trim();
            this.sourceSpaceName = trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
        }
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
