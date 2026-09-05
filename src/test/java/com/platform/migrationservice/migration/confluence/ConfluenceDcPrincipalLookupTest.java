package com.platform.migrationservice.migration.confluence;

import com.platform.proto.org.v1.LookupMembersRequest;
import com.platform.proto.org.v1.LookupMembersResponse;
import com.platform.proto.org.v1.LookupTeamsRequest;
import com.platform.proto.org.v1.LookupTeamsResponse;
import com.platform.proto.org.v1.MemberMatch;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.TeamMatch;
import com.platform.migrationservice.migration.MigrationJobService;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcDiscoveryService;
import com.platform.migrationservice.migration.confluence.restriction.GrpcMigrationPrincipalResolver;
import com.platform.migrationservice.migration.confluence.restriction.MigrationPrincipalResolver;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationSource;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import com.platform.migrationservice.migration.repository.MigrationPayloadRepository;
import com.platform.migrationservice.migration.repository.MigrationSourceRepository;
import com.platform.migrationservice.migration.worker.MigrationWorker;
import com.platform.migrationservice.permission.FakePermissionClient;
import com.platform.migrationservice.permission.SpaceAction;
import com.platform.migrationservice.wiki.FakeWikiImportServer;
import com.platform.migrationservice.wiki.WikiImportTestSupport;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원본 사람을 우리 계정으로 **찾았을 때** 파이프라인이 어떻게 달라지는지 고정한다(org proto 계약).
 *
 * 다른 이관 테스트가 쓰는 {@code FakeMigrationPrincipalResolver} 대신 실제
 * {@link GrpcMigrationPrincipalResolver}를 in-process 가짜 org에 물려 돌린다 — 지켜야 할 것이
 * "대조되면 우리 사용자로 쓴다"라는 규칙이 아니라 **그 규칙이 gRPC 계약 위에서 성립한다**는 것이기
 * 때문이다. 매핑 실패 쪽(fail-closed)은 {@link ConfluenceDcPipelineTest}가 이미 지킨다.
 */
@SpringBootTest(properties = {
        "platform.migration.dc.page-size=2",
        "platform.migration.dc.max-attachment-bytes=1024",
        "platform.migration.dc.history-versions=0",
})
@ActiveProfiles({"test", "org-lookup"})
class ConfluenceDcPrincipalLookupTest extends WikiImportTestSupport {

    private static final long ADMIN = 11L;
    private static final long OPS_MEMBER = 77L;
    private static final Instant NOW = Instant.parse("2026-09-05T09:00:00Z");

    /** 가짜 DC가 모든 문서의 작성자로 쓰는 계정(FakeConfluenceDcServer의 createdBy). */
    private static final String OPS_EMAIL = "ops@example.com";

    /** 이름 조회에만 답하는 가짜 org. 판정 RPC는 FakePermissionClient가 따로 가로챈다. */
    static class LookupOnlyOrg extends PermissionServiceGrpc.PermissionServiceImplBase {

        @Override
        public void lookupMembers(LookupMembersRequest req, StreamObserver<LookupMembersResponse> out) {
            LookupMembersResponse.Builder response = LookupMembersResponse.newBuilder();
            for (String email : req.getEmailsList()) {
                if (OPS_EMAIL.equalsIgnoreCase(email.trim())) {
                    response.addMatches(MemberMatch.newBuilder().setQuery(email)
                            .setMemberId(OPS_MEMBER).setDisplayName("김운영").setEmail(OPS_EMAIL));
                }
            }
            out.onNext(response.build());
            out.onCompleted();
        }

        @Override
        public void lookupTeams(LookupTeamsRequest req, StreamObserver<LookupTeamsResponse> out) {
            // 이 시나리오에 그룹 제한은 없다 — 빈 응답이 곧 "우리 팀에 없다"다.
            out.onNext(LookupTeamsResponse.newBuilder().build());
            out.onCompleted();
        }
    }

    @TestConfiguration
    static class OrgLookupConfig {

        private static final String SERVER_NAME = InProcessServerBuilder.generateName();

        @Bean(destroyMethod = "shutdownNow")
        Server lookupOrgServer() throws IOException {
            return InProcessServerBuilder.forName(SERVER_NAME)
                    .directExecutor().addService(new LookupOnlyOrg()).build().start();
        }

        /** 채널은 서버가 뜬 뒤에 만든다 — 인자로 받아 순서를 강제한다. */
        @Bean(destroyMethod = "shutdownNow")
        ManagedChannel lookupOrgChannel(Server lookupOrgServer) {
            return InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
        }

        /** 실제 구현을 in-process 가짜 org에 물린다 — SecurityConfig의 gRPC 빈을 대신한다. */
        @Bean
        @Primary
        MigrationPrincipalResolver lookupMigrationPrincipalResolver(ManagedChannel lookupOrgChannel) {
            return new GrpcMigrationPrincipalResolver(
                    PermissionServiceGrpc.newBlockingStub(lookupOrgChannel));
        }
    }

    @Autowired MigrationWorker worker;
    @Autowired MigrationJobService migrations;
    @Autowired ConfluenceDcDiscoveryService discovery;
    @Autowired MigrationJobRepository jobs;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationSourceRepository sources;
    @Autowired MigrationPayloadRepository payloads;
    @Autowired MigrationObjectMappingRepository mappings;
    @Autowired FakePermissionClient permissions;

    private FakeConfluenceDcServer dc;
    private Long spaceId;

    @BeforeEach
    void setUp() throws Exception {
        dc = new FakeConfluenceDcServer();
        wiki.reset();
        permissions.reset();
        issues.deleteAllInBatch();
        payloads.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        sources.deleteAllInBatch();
        jobs.deleteAllInBatch();
        spaceId = wiki.putSpace("이관 대상");
        permissions.allow(ADMIN, spaceId, SpaceAction.ADMIN);
    }

    @AfterEach
    void tearDown() {
        dc.stop();
    }

    @Test
    void 이메일로_대조된_원본_작성자가_문서의_작성자와_제한_주체가_된다() {
        dc.putPage("10001", "서비스 운영 가이드", null, "<p>제한 있는 문서</p>", 1, List.of(),
                List.of(), FakeConfluenceDcServer.FakeRestrictions.readBy(List.of("ops"), List.of()));
        dc.removePage("10002");
        dc.removePage("10003");

        MigrationJob job = jobs.save(MigrationJob.create(MigrationProvider.CONFLUENCE_DC,
                "127.0.0.1", spaceId, ADMIN, MigrationJobMode.IMPORT));
        sources.save(MigrationSource.of(job.getId(), dc.baseUrl(), "ENG", "test-token"));
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        FakeWikiImportServer.FakePage page = wiki.pageTitled("서비스 운영 가이드");

        // 작성자는 이관 담당자가 아니라 원본의 그 사람이다.
        assertThat(page.authorId).isEqualTo(OPS_MEMBER);
        // 대조됐으므로 "이관됨" 표시는 남기지 않는다 — 화면이 평소대로 우리 사용자를 보여준다.
        assertThat(page.importedAuthorName).isNull();
        assertThat(page.importedSourceUrl).isNull();
        assertThat(page.revisions).extracting(revision -> revision.editorName).containsOnlyNulls();

        // 제한도 요청자 단독이 아니라 그 사용자로 걸린다(fail-closed 경로를 타지 않았다).
        assertThat(page.viewRestrictions)
                .containsExactly(new FakeWikiImportServer.FakePrincipal("USER", OPS_MEMBER));

        assertThat(issues.findByJobIdOrderByIdAsc(job.getId()))
                .extracting(issue -> issue.getCode())
                .doesNotContain("AUTHOR_UNMAPPED", "RESTRICTION_PRINCIPAL_UNMAPPED");
    }
}
