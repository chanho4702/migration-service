package com.platform.migrationservice.migration.confluence.restriction;

import java.util.Optional;

/**
 * 아무도 대조하지 못하는 구현 — org 원장이 없는 배포(docs 프로필)의 자리를 채운다.
 *
 * 컴포넌트 스캔으로 등록하지 않는다. 팀 위키에서는 {@link GrpcMigrationPrincipalResolver}가 서고,
 * 여기가 서는 곳은 공개 문서 인스턴스뿐이다 — 거기에는 조회할 계정도 팀도 없다.
 *
 * 그래도 조용히 공개로 푸는 경로는 없다: 미매핑은 호출부가 fail-closed로 닫는다(ADR-W14-07).
 */
public class UnmappedMigrationPrincipalResolver implements MigrationPrincipalResolver {

    @Override
    public Optional<Long> resolveUser(SourceUser user) {
        return Optional.empty();
    }

    @Override
    public Optional<Long> resolveTeam(String groupName) {
        return Optional.empty();
    }
}
