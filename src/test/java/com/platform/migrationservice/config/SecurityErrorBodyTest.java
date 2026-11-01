package com.platform.migrationservice.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보안 필터 체인이 끊는 401/403에도 플랫폼 본문 {"error": 메시지}가 실린다(P-11, common-starter
 * PlatformSecurityErrorHandlers). Spring Security 기본은 본문이 비어 프론트가 한국어 메시지를 못 낸다.
 * WWW-Authenticate(RFC 6750)는 Bearer 처리기가 그대로 붙인다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityErrorBodyTest {

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filterChainProxy;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 토큰_없는_요청은_401_본문_error() throws Exception {
        mvc.perform(get("/api/migration"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.error").value("로그인이 필요합니다"));
    }

    @Test
    void 형식이_깨진_토큰도_401_본문_error() throws Exception {
        mvc.perform(get("/api/migration").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.containsString("invalid_token")))
                .andExpect(jsonPath("$.error").value("로그인이 필요합니다"));
    }

    /**
     * 필터 단계 403 — 인증된 사용자를 체인이 거부할 때 이 서비스 체인의 ExceptionTranslationFilter가
     * 쓰는 처리기를 직접 태운다(경로 규칙이 역할로 막는 곳이 아직 없어 MockMvc로는 재현할 요청이 없다).
     */
    @Test
    void 체인이_거부한_인증_사용자는_403_본문_error() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/migration");
        request.setServletPath("/api/migration");
        SecurityFilterChain chain = filterChainProxy.getFilterChains().stream()
                .filter(c -> c.matches(request)).findFirst().orElseThrow();
        Filter translation = chain.getFilters().stream()
                .filter(ExceptionTranslationFilter.class::isInstance).findFirst().orElseThrow();

        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        translation.doFilter(request, response, (req, res) -> { throw new AccessDeniedException("denied"); });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"권한이 없습니다\"}");
    }
}
