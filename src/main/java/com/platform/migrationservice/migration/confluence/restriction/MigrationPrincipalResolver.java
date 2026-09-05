package com.platform.migrationservice.migration.confluence.restriction;

import java.util.Collection;
import java.util.Optional;

/**
 * 원본 컨플루언스의 사용자·그룹을 우리 계정·팀으로 대조한다.
 *
 * 원장은 org-service다. common-proto에서 이름으로 찾는 창구가 생겼고
 * ({@code LookupMembers} · {@code LookupTeams}) {@link GrpcMigrationPrincipalResolver}가 그것을 쓴다.
 * 못 찾은 주체는 여전히 fail-closed로 이어진다(ADR-W14-07) — 공개로 풀지 않고 잡 요청자 단독
 * 제한으로 닫는다.
 */
public interface MigrationPrincipalResolver {

    /** 원본 사용자 → 우리 사용자 id. 못 찾으면 빈 값. */
    Optional<Long> resolveUser(SourceUser user);

    /** 원본 그룹 이름 → 우리 팀 id. 못 찾으면 빈 값. */
    Optional<Long> resolveTeam(String groupName);

    /**
     * 한 항목이 물어볼 주체를 미리 한 번에 조회해 둔다. 이 힌트가 없으면 구현이 주체마다 org를
     * 왕복해, 문서 하나에 열 명이 걸린 제한이 열 번의 RPC가 된다.
     *
     * 결과를 돌려주지 않는 이유: 호출측 코드는 그대로 {@link #resolveUser}·{@link #resolveTeam}을
     * 쓰고 캐시 유무는 구현의 사정으로 남긴다. 기본 구현은 아무것도 하지 않는다.
     */
    default void warmUp(Collection<SourceUser> users, Collection<String> groupNames) {
    }

    /** 원본이 알려주는 사람 식별자. 셋 다 비어 있을 수 있다. */
    record SourceUser(String username, String displayName, String email) {

        /** 보고서에 남길 이름 — 사람이 원본에서 찾아갈 수 있는 값을 고른다. */
        public String label() {
            if (username != null && !username.isBlank()) {
                return username;
            }
            if (email != null && !email.isBlank()) {
                return email;
            }
            return displayName == null || displayName.isBlank() ? "unknown" : displayName;
        }
    }
}
