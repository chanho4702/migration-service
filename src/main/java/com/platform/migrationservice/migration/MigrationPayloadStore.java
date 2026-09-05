package com.platform.migrationservice.migration;

import com.platform.migrationservice.migration.model.MigrationPayload;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.repository.MigrationPayloadRepository;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 단계 산출물의 유일한 입출구.
 *
 * 단계 handler는 worker의 트랜잭션 밖에서 돌기 때문에(네트워크 I/O 때문) 자기 쓰기를 스스로
 * 트랜잭션으로 감싸야 한다. 여기서 REQUIRES_NEW를 쓰는 이유도 같다 — handler가 뒤에서 실패해도
 * 이미 받아온 스냅샷은 남아, 재시도가 원본을 다시 긁지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MigrationPayloadStore {

    /** 앞 단계가 남겼어야 할 산출물이 없다. 재시도해도 나아지지 않으므로 비재시도 실패다. */
    public static final String PAYLOAD_MISSING = "MIGRATION_PAYLOAD_MISSING";

    private final MigrationPayloadRepository payloads;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(long itemId, MigrationPayloadKind kind, String body) {
        Optional<MigrationPayload> existing = payloads.findByItemIdAndKind(itemId, kind);
        if (existing.isPresent()) {
            existing.get().replace(body);
            payloads.save(existing.get());
            return;
        }
        try {
            payloads.saveAndFlush(MigrationPayload.of(itemId, kind, body));
        } catch (DataIntegrityViolationException e) {
            // 같은 item을 두 시도가 동시에 처리했다. 뒤늦은 쪽은 덮어쓰기로 수렴한다.
            payloads.findByItemIdAndKind(itemId, kind)
                    .orElseThrow(() -> e)
                    .replace(body);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<StoredPayload> read(long itemId, MigrationPayloadKind kind) {
        return payloads.findByItemIdAndKind(itemId, kind)
                .map(row -> new StoredPayload(row.getBody(), row.getChecksum(), row.getCreatedAt()));
    }

    public StoredPayload require(long itemId, MigrationPayloadKind kind) {
        return read(itemId, kind)
                .orElseThrow(() -> MigrationStageException.permanent(PAYLOAD_MISSING));
    }

    /** createdAt은 이 산출물을 만든 시각이다 — IR의 source.capturedAt이 그대로 받는다. */
    public record StoredPayload(String body, String checksum, java.time.Instant createdAt) {
    }
}
