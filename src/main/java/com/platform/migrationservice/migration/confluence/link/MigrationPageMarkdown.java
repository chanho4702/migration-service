package com.platform.migrationservice.migration.confluence.link;

import com.platform.migrationservice.migration.MigrationPayloadStore;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 이관한 문서의 현재 본문을 **엔진 쪽 산출물**에서 되찾는다.
 *
 * 위키에 본문을 되묻지 않는 이유가 둘이다.
 * 1. 대조용 조회(`GET /pages/{id}`)는 본문 길이만 준다 — 문서 500건의 본문을 다시 받아 오는 것은
 *    링크 정리 하나를 위해 치를 값이 아니다.
 * 2. 우리가 쓴 마크다운은 이미 MARKDOWN 산출물로 남아 있다. 링크 정리와 앵커 대조가 보는 것은
 *    헤딩과 링크뿐이고, 그 둘은 우리가 쓴 그 본문에 그대로 있다.
 *
 * 사람이 이관 뒤 손댄 편집은 여기 반영되지 않는다. 그래도 되는 이유: 이 값으로 하는 일은
 * "임시 링크가 남아 있는가"와 "이 앵커에 맞는 헤딩이 있었는가"뿐이고, 둘 다 우리가 쓴 시점의
 * 사실이다. 사람이 지운 헤딩까지 좇으려면 본문을 되받아야 하는데, 그 값은 이 두 판단에 비해 비싸다.
 */
@Component
@RequiredArgsConstructor
public class MigrationPageMarkdown {

    private final MigrationItemRepository items;
    private final MigrationPayloadStore payloads;

    /** 이 매핑이 가리키는 문서에 우리가 마지막으로 쓴 마크다운. 못 찾으면 빈 값. */
    public Optional<String> of(MigrationObjectMapping mapping) {
        if (mapping == null || mapping.getLastJobId() == null) {
            return Optional.empty();
        }
        return items.findByJobIdAndSourceKey(mapping.getLastJobId(),
                        MigrationItem.sourceKeyFor(mapping.getExternalObjectId()))
                .flatMap(item -> payloads.read(item.getId(), MigrationPayloadKind.MARKDOWN))
                .map(MigrationPayloadStore.StoredPayload::body);
    }
}
