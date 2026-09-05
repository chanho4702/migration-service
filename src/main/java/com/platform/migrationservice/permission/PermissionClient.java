package com.platform.migrationservice.permission;

/** org-service 권한 연동 창구 — 테스트는 페이크로 대체한다. */
public interface PermissionClient {

    /** 스페이스 단위 판정. org 불능이면 예외(503)를, 그 밖의 실패는 false(fail-closed)를 낸다. */
    boolean isAllowed(long userId, long spaceId, SpaceAction action);

    /** 전역 관리자 판정에 쓰는 grant 범위. */
    AccessScope accessibleSpaces(long userId);
}
