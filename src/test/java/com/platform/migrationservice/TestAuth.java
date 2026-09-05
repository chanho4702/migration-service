package com.platform.migrationservice;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** MockMvc 요청에 우리 규약대로 만든 JWT를 실어 준다 — subject가 사용자 id다. */
public final class TestAuth {

    private TestAuth() {
    }

    public static RequestPostProcessor asUser(long userId, String name) {
        return jwt().jwt(builder -> builder
                .subject(String.valueOf(userId))
                .claim("name", name)
                .issuedAt(Instant.parse("2026-09-05T00:00:00Z"))
                .expiresAt(Instant.parse("2036-09-05T00:00:00Z")));
    }
}
