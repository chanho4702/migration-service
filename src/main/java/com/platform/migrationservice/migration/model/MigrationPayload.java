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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * item 하나의 단계 산출물. checksum은 본문의 SHA-256이고, 재실행 시 "같은 것을 또 만들었는가"를
 * 이 값으로 판단한다 — 본문 전체를 문자열 비교하면 500페이지 스페이스에서 비용이 그대로 든다.
 */
@Entity
@Table(name = "migration_payload", uniqueConstraints =
        @UniqueConstraint(name = "uk_migration_payload", columnNames = {"item_id", "kind"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationPayload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private MigrationPayloadKind kind;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(nullable = false, length = 64)
    private String checksum;

    /**
     * 이 산출물을 만든 시각. @CreationTimestamp를 쓰지 않는 이유는 재실행이 같은 행을 덮어쓰기
     * 때문이다 — 그 경우 "언제 원본을 읽었는가"는 첫 실행이 아니라 마지막 실행의 시각이어야 한다.
     * IR의 source.capturedAt이 이 값을 그대로 받는다.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static MigrationPayload of(Long itemId, MigrationPayloadKind kind, String body) {
        MigrationPayload payload = new MigrationPayload();
        payload.itemId = requireNonNull(itemId, "itemId");
        payload.kind = requireNonNull(kind, "kind");
        payload.replace(body);
        return payload;
    }

    /** 재실행이 같은 자리를 덮어쓴다 — 종류별로 최신 산출물 하나만 남는다. */
    public void replace(String body) {
        if (body == null) {
            throw new IllegalArgumentException("body is required");
        }
        this.body = body;
        this.checksum = sha256(body);
        this.createdAt = Instant.now();
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
