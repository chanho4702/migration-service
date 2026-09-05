package com.platform.migrationservice.migration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * extractor가 발견한 원본 객체 한 건. 본문은 immutable object storage에 두고 여기에는
 * 참조와 checksum만 넣는다 — 원본 payload를 DB에 담지 않는 것이 W14 계약이다.
 */
public record MigrationItemEnqueueRequest(
        @NotBlank @Size(max = 512) String externalObjectId,
        @Size(max = 100) String sourceVersion,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}", message = "sourceChecksum은 소문자 SHA-256이어야 합니다")
        String sourceChecksum,
        @NotBlank @Size(max = 1024) String payloadRef,
        /** 원본이 정한 형제 순서(M2). 모르면 null — 그때는 발견 순서를 그대로 쓴다. */
        Integer siblingOrder) {

    public MigrationItemEnqueueRequest(String externalObjectId, String sourceVersion,
                                       String sourceChecksum, String payloadRef) {
        this(externalObjectId, sourceVersion, sourceChecksum, payloadRef, null);
    }
}
