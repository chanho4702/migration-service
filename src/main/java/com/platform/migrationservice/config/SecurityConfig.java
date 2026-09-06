package com.platform.migrationservice.config;

import com.platform.migrationservice.migration.confluence.restriction.GrpcMigrationPrincipalResolver;
import com.platform.migrationservice.migration.confluence.restriction.MigrationPrincipalResolver;
import com.platform.migrationservice.permission.GrpcPermissionClient;
import com.platform.migrationservice.permission.PermissionClient;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT 디코더(JWKS + issuer/audience 검증)와 roles→ROLE_ 변환기는 common-starter가 준다(S-02).
 * 여기에는 이 서비스만의 것 — 어떤 경로를 열지, org gRPC 채널과 클라이언트 — 만 남긴다.
 *
 * 공개 엔드포인트는 없다. `/api/migration/**`은 전부 전역 관리자 또는 대상 스페이스 ADMIN이고,
 * 그 판정은 서비스 계층이 org gRPC로 한다. 예외는 OpenAPI 스펙 하나뿐이다 — 게이트웨이·nginx가
 * `/v3`를 라우팅하지 않아 클러스터 밖에서는 닿지 않는다(wiki-backend와 같은 규칙).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // 내부망 전용 헬스/빌드정보 — 게이트웨이 대시보드가 인증 없이 프로브한다.
                        // 노출은 이 둘뿐이다(management.endpoints.web.exposure.include).
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    /** org gRPC 채널 — 권한 판정과 주체 대조가 공유한다. */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "orgChannel")
    io.grpc.ManagedChannel orgChannel(
            @Value("${platform.org-grpc.host}") String host,
            @Value("${platform.org-grpc.port}") int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }

    /** 테스트는 FakePermissionClient(@Primary)가 이 빈을 대체한다. */
    @Bean
    @ConditionalOnMissingBean(PermissionClient.class)
    PermissionClient permissionClient(io.grpc.ManagedChannel orgChannel) {
        return new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(orgChannel));
    }

    /**
     * 원본 작성자·제한 주체를 우리 계정·팀에 짝짓는 창구. 테스트는
     * FakeMigrationPrincipalResolver(@Primary)가 대체한다.
     */
    @Bean
    @ConditionalOnMissingBean(MigrationPrincipalResolver.class)
    MigrationPrincipalResolver migrationPrincipalResolver(io.grpc.ManagedChannel orgChannel) {
        return new GrpcMigrationPrincipalResolver(PermissionServiceGrpc.newBlockingStub(orgChannel));
    }
}
