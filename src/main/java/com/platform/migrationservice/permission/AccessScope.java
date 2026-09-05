package com.platform.migrationservice.permission;

import java.util.Set;

/**
 * 한 사용자가 닿을 수 있는 스페이스의 범위.
 *
 * {@code all=true}는 전역 grant(GLOBAL)를 가졌다는 뜻이다 — 이관 관리 화면(잡 목록·연결 확인)은
 * 대상 스페이스가 아직 없거나 여러 스페이스에 걸치므로 이 판정으로만 열린다.
 */
public record AccessScope(boolean all, Set<Long> spaceIds) {

    public AccessScope {
        spaceIds = spaceIds == null ? Set.of() : Set.copyOf(spaceIds);
    }
}
