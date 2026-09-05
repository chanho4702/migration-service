package com.platform.migrationservice.permission;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 테스트 전용 페이크 — test 소스셋의 컴포넌트 스캔으로 등록된다.
 *
 * @Primary인 이유: SecurityConfig의 @ConditionalOnMissingBean은 빈 등록 순서에 기대는 약한
 * 대체라, 주입 우선권으로 확실히 가른다.
 */
@Component
@Primary
public class FakePermissionClient implements PermissionClient {

    private record Key(long userId, long spaceId, SpaceAction action) {}

    private final Set<Key> allowed = new HashSet<>();
    private final Set<Long> allowAllUsers = new HashSet<>();

    public void allow(long userId, long spaceId, SpaceAction action) {
        allowed.add(new Key(userId, spaceId, action));
    }

    /** 전역 관리자 — 잡 목록·연결 확인이 이 판정으로만 열린다. */
    public void allowAll(long userId) {
        allowAllUsers.add(userId);
    }

    public void reset() {
        allowed.clear();
        allowAllUsers.clear();
    }

    @Override
    public boolean isAllowed(long userId, long spaceId, SpaceAction action) {
        return allowAllUsers.contains(userId) || allowed.contains(new Key(userId, spaceId, action));
    }

    @Override
    public AccessScope accessibleSpaces(long userId) {
        if (allowAllUsers.contains(userId)) {
            return new AccessScope(true, Set.of());
        }
        Set<Long> ids = new HashSet<>();
        for (Key key : allowed) {
            if (key.userId() == userId) {
                ids.add(key.spaceId());
            }
        }
        return new AccessScope(false, ids);
    }
}
