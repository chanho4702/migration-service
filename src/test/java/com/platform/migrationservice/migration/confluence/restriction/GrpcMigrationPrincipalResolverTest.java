package com.platform.migrationservice.migration.confluence.restriction;

import com.platform.proto.org.v1.LookupMembersRequest;
import com.platform.proto.org.v1.LookupMembersResponse;
import com.platform.proto.org.v1.LookupTeamsRequest;
import com.platform.proto.org.v1.LookupTeamsResponse;
import com.platform.proto.org.v1.MemberMatch;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.TeamMatch;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * gRPC 대조 구현 단위 검증 — in-process 가짜 org로 묶음 조회·캐시·실패 갈림길을 본다.
 *
 * 여기서 지키는 것은 두 가지다. 문서 하나에 열 명이 걸려도 왕복이 한 번이라는 것과,
 * org가 죽었을 때 "못 찾았다"로 삼키지 않는다는 것이다.
 */
class GrpcMigrationPrincipalResolverTest {

    static class StubOrg extends PermissionServiceGrpc.PermissionServiceImplBase {
        final AtomicInteger memberCalls = new AtomicInteger();
        final AtomicInteger teamCalls = new AtomicInteger();
        volatile Status failStatus;

        @Override
        public void lookupMembers(LookupMembersRequest req, StreamObserver<LookupMembersResponse> out) {
            memberCalls.incrementAndGet();
            if (failStatus != null) {
                out.onError(failStatus.asRuntimeException());
                return;
            }
            LookupMembersResponse.Builder response = LookupMembersResponse.newBuilder();
            for (String email : req.getEmailsList()) {
                if ("ops@example.com".equalsIgnoreCase(email.trim())) {
                    response.addMatches(MemberMatch.newBuilder().setQuery(email).setMemberId(77L)
                            .setDisplayName("김운영").setEmail("ops@example.com"));
                }
            }
            for (String username : req.getUsernamesList()) {
                if ("dev".equalsIgnoreCase(username.trim())) {
                    response.addMatches(MemberMatch.newBuilder().setQuery(username).setMemberId(88L)
                            .setDisplayName("이개발").setEmail("dev@example.com"));
                }
            }
            out.onNext(response.build());
            out.onCompleted();
        }

        @Override
        public void lookupTeams(LookupTeamsRequest req, StreamObserver<LookupTeamsResponse> out) {
            teamCalls.incrementAndGet();
            if (failStatus != null) {
                out.onError(failStatus.asRuntimeException());
                return;
            }
            LookupTeamsResponse.Builder response = LookupTeamsResponse.newBuilder();
            for (String name : req.getNamesList()) {
                if ("플랫폼팀".equals(name.trim())) {
                    response.addMatches(TeamMatch.newBuilder().setQuery(name).setTeamId(5L)
                            .setName("플랫폼팀"));
                }
            }
            out.onNext(response.build());
            out.onCompleted();
        }
    }

    StubOrg org = new StubOrg();
    Server server;
    ManagedChannel channel;
    GrpcMigrationPrincipalResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(org).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        resolver = new GrpcMigrationPrincipalResolver(PermissionServiceGrpc.newBlockingStub(channel));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow();
        channel.awaitTermination(3, TimeUnit.SECONDS);
        server.awaitTermination();
    }

    private static MigrationPrincipalResolver.SourceUser user(String username, String email) {
        return new MigrationPrincipalResolver.SourceUser(username, username, email);
    }

    @Test
    void 한_번_묶어_묻고_이후_대조는_왕복하지_않는다() {
        resolver.warmUp(List.of(user("ops", "ops@example.com"), user("dev", null),
                user("ghost", "ghost@example.com")), List.of("플랫폼팀", "없는팀"));

        assertThat(resolver.resolveUser(user("ops", "ops@example.com"))).contains(77L);
        assertThat(resolver.resolveUser(user("dev", null))).contains(88L);
        assertThat(resolver.resolveTeam("플랫폼팀")).contains(5L);
        // 못 찾은 것도 캐시된다 — 대조 안 되는 작성자 한 명이 문서마다 같은 조회를 내지 않게.
        assertThat(resolver.resolveUser(user("ghost", "ghost@example.com"))).isEmpty();
        assertThat(resolver.resolveTeam("없는팀")).isEmpty();

        assertThat(org.memberCalls.get()).isEqualTo(1);
        assertThat(org.teamCalls.get()).isEqualTo(1);
    }

    /** 대소문자·공백만 다른 값은 같은 사람이다 — 원본 데이터는 정돈돼 있지 않다. */
    @Test
    void 이메일은_trim과_대소문자_무시로_같은_사람에_붙는다() {
        assertThat(resolver.resolveUser(user("ops", " OPS@Example.com "))).contains(77L);
        assertThat(resolver.resolveUser(user("ops", "ops@example.com"))).contains(77L);
        assertThat(org.memberCalls.get()).isEqualTo(1);
    }

    /**
     * org가 죽었는데 "못 찾았다"로 삼키면 잡이 성공으로 끝나면서 문서 전부가 요청자 소유에
     * 요청자 단독 제한으로 잠긴다. 재시도 가능한 단계 실패로 올려 항목을 다시 태운다.
     */
    @Test
    void org가_닿지_않으면_재시도_가능한_단계_실패로_올린다() {
        org.failStatus = Status.UNAVAILABLE;

        assertThatThrownBy(() -> resolver.resolveUser(user("ops", "ops@example.com")))
                .isInstanceOf(MigrationStageException.class)
                .satisfies(thrown -> assertThat(((MigrationStageException) thrown).isRetryable()).isTrue());
        assertThatThrownBy(() -> resolver.resolveTeam("플랫폼팀"))
                .isInstanceOf(MigrationStageException.class);
    }

    /** 구버전 org는 이 RPC를 모른다(UNIMPLEMENTED). 그건 미매핑이고, 호출부가 fail-closed로 닫는다. */
    @Test
    void 그_밖의_오류는_미매핑으로_두고_캐시하지_않는다() {
        org.failStatus = Status.UNIMPLEMENTED;
        assertThat(resolver.resolveUser(user("ops", "ops@example.com"))).isEmpty();

        org.failStatus = null;
        assertThat(resolver.resolveUser(user("ops", "ops@example.com"))).contains(77L);
    }
}
