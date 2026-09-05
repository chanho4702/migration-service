package com.platform.migrationservice.config;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * JWT의 subject가 곧 우리 사용자 id다(플랫폼 공통 규약 — auth-server가 숫자 id를 subject로 낸다).
 * 여기서만 그 약속을 푼다.
 */
public final class JwtPrincipal {

    private JwtPrincipal() {
    }

    public static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
