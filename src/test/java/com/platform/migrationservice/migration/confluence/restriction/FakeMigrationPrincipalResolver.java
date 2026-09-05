package com.platform.migrationservice.migration.confluence.restriction;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 테스트 전용 페이크 — 원본 사용자·그룹을 우리 id로 대조하는 창구를 흉내 낸다.
 *
 * 운영 구현({@link GrpcMigrationPrincipalResolver})은 org의 이름 조회를 탄다. 이관 시나리오
 * 대부분은 org 응답이 아니라 **대조 결과에 따른 규칙**(fail-closed·작성자 표시)을 보는 것이라
 * 여기서 매핑을 직접 심는다. gRPC 계약 위에서 그 규칙이 성립하는지는
 * {@code ConfluenceDcPrincipalLookupTest}가 실제 구현으로 따로 확인한다.
 */
@Component
@Primary
// org-lookup 프로필은 실제 gRPC 구현을 그대로 검증한다 — 그때는 이 페이크가 서지 않는다.
@org.springframework.context.annotation.Profile("!org-lookup")
public class FakeMigrationPrincipalResolver implements MigrationPrincipalResolver {

    private final Map<String, Long> users = new HashMap<>();
    private final Map<String, Long> teams = new HashMap<>();

    public void reset() {
        users.clear();
        teams.clear();
    }

    public void mapUser(String username, long userId) {
        users.put(username, userId);
    }

    public void mapTeam(String groupName, long teamId) {
        teams.put(groupName, teamId);
    }

    @Override
    public Optional<Long> resolveUser(SourceUser user) {
        return Optional.ofNullable(users.get(user.username()));
    }

    @Override
    public Optional<Long> resolveTeam(String groupName) {
        return Optional.ofNullable(teams.get(groupName));
    }
}
