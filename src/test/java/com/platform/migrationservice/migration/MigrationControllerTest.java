package com.platform.migrationservice.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationItemStatus;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationJobMode;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.repository.MigrationJobRepository;
import com.platform.migrationservice.migration.repository.MigrationPayloadRepository;
import com.platform.migrationservice.migration.repository.MigrationSourceRepository;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import com.platform.migrationservice.permission.FakePermissionClient;
import com.platform.migrationservice.permission.SpaceAction;
import com.platform.migrationservice.wiki.WikiImportTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static com.platform.migrationservice.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class MigrationControllerTest extends WikiImportTestSupport {

    private static final long ADMIN = 7L;
    private static final long OUTSIDER = 8L;
    private static final String CHECKSUM = "c".repeat(64);

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FakePermissionClient permissions;
    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationObjectMappingRepository mappings;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationJobRepository jobs;
    @Autowired MigrationSourceRepository sources;
    @Autowired MigrationPayloadRepository payloads;

    private Long spaceId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        issues.deleteAllInBatch();
        payloads.deleteAllInBatch();
        sources.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        jobs.deleteAllInBatch();
        wiki.reset();
        spaceId = wiki.putSpace("Migration");
        permissions.allow(ADMIN, spaceId, SpaceAction.ADMIN);
        permissions.allow(OUTSIDER, spaceId, SpaceAction.EDIT);
    }

    @Test
    void 관리자는_job을_만들고_원본을_등록한_뒤_시작할_수_있다() throws Exception {
        long jobId = createJob(MigrationJobMode.DRY_RUN);

        mvc.perform(post("/api/migration/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enqueueBody("page-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalObjectId").value("page-1"))
                .andExpect(jsonPath("$.stage").value("EXTRACT"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 같은 원본을 다시 넣어도 item은 하나다 — extractor 재개가 job을 부풀리면 안 된다.
        mvc.perform(post("/api/migration/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enqueueBody("page-1"))))
                .andExpect(status().isCreated());
        assertThat(items.countByJobId(jobId)).isEqualTo(1);

        mvc.perform(post("/api/migration/{id}/start", jobId).with(asUser(ADMIN, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.itemCount").value(1));

        mvc.perform(post("/api/migration/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enqueueBody("page-2"))))
                .andExpect(status().isConflict());
    }

    @Test
    void ADMIN이_아니면_job을_만들지도_읽지도_못한다() throws Exception {
        long jobId = createJob(MigrationJobMode.IMPORT);

        mvc.perform(post("/api/migration").with(asUser(OUTSIDER, "Outsider"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createBody(MigrationJobMode.IMPORT))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/migration/{id}/report", jobId).with(asUser(OUTSIDER, "Outsider")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 보고서는_상태_단계_손실_dead_letter를_함께_낸다() throws Exception {
        MigrationJob job = jobs.save(MigrationJob.create(MigrationProvider.NOTION, "workspace-acme",
                spaceId, ADMIN, MigrationJobMode.DRY_RUN));
        MigrationItem dead = items.save(MigrationItem.pending(job.getId(), "page-9", "1", CHECKSUM,
                "imports/page-9.json"));
        job.start(java.time.Instant.parse("2026-08-18T09:00:00Z"));
        jobs.saveAndFlush(job);
        items.claim(dead.getId(), "worker-a", "token-1", java.time.Instant.parse("2026-08-18T09:05:00Z"),
                java.time.Instant.parse("2026-08-18T09:00:01Z"), MigrationItemStatus.RUNNING,
                MigrationItemStatus.PENDING, MigrationItemStatus.RETRY_WAIT);
        MigrationItem running = items.findById(dead.getId()).orElseThrow();
        running.deadLetter("NOTION_FORBIDDEN", java.time.Instant.parse("2026-08-18T09:00:02Z"));
        items.saveAndFlush(running);
        issues.save(com.platform.migrationservice.migration.model.MigrationIssue.of(job.getId(), running.getId(),
                com.platform.migrationservice.migration.model.MigrationIssueSeverity.ERROR,
                "NOTION_FORBIDDEN", "page-9"));

        mvc.perform(get("/api/migration/{id}/report", job.getId()).with(asUser(ADMIN, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.mode").value("DRY_RUN"))
                .andExpect(jsonPath("$.job.itemCount").value(1))
                .andExpect(jsonPath("$.itemsByStatus.DEAD_LETTER").value(1))
                .andExpect(jsonPath("$.itemsByStage.EXTRACT").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("NOTION_FORBIDDEN"))
                .andExpect(jsonPath("$.issues[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.issues[0].occurrences").value(1))
                // 대표 위치가 없으면 화면이 "MACRO_OPAQUE 3건"까지만 알고 어디였는지 모른다.
                .andExpect(jsonPath("$.issues[0].sampleSourcePath").value("page-9"))
                .andExpect(jsonPath("$.deadLetters[0].externalObjectId").value("page-9"))
                .andExpect(jsonPath("$.deadLetters[0].lastErrorCode").value("NOTION_FORBIDDEN"));
    }

    @Test
    void 연결_확인과_잡_목록은_전역_관리자만_할_수_있다() throws Exception {
        // 대상 스페이스가 아직 없거나 여러 스페이스에 걸치므로 스페이스 ADMIN으로는 판정할 수 없다.
        mvc.perform(post("/api/migration/confluence-dc/probe").with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "baseUrl", "https://wiki.example.com",
                                "spaceKey", "ENG",
                                "token", "pat-token"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/migration").with(asUser(ADMIN, "Admin")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 전역_관리자는_잡_목록을_최신순으로_본다() throws Exception {
        permissions.allowAll(ADMIN);
        long first = createJob(MigrationJobMode.DRY_RUN);
        long second = createJob(MigrationJobMode.IMPORT);

        mvc.perform(get("/api/migration").with(asUser(ADMIN, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[0].provider").value("NOTION"))
                .andExpect(jsonPath("$[0].sourceSpaceKey").doesNotExist());
    }

    @Test
    void 상세는_원본_요약과_단계별_집계를_함께_준다() throws Exception {
        long jobId = createConfluenceJob();

        mvc.perform(get("/api/migration/{id}", jobId).with(asUser(ADMIN, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("CONFLUENCE_DC"))
                // baseUrl의 호스트가 sourceInstanceId로 채워진다 — 관리자가 두 번 입력하지 않는다.
                .andExpect(jsonPath("$.sourceInstanceId").value("wiki.example.com"))
                .andExpect(jsonPath("$.source.baseUrl").value("https://wiki.example.com"))
                .andExpect(jsonPath("$.source.spaceKey").value("ENG"))
                .andExpect(jsonPath("$.source.discoveredCount").value(0))
                // 토큰은 어떤 응답에도 실리지 않는다(기획 P8).
                .andExpect(jsonPath("$.source.token").doesNotExist())
                .andExpect(jsonPath("$.counts.byStatus").exists())
                .andExpect(jsonPath("$.counts.byStage").exists());
    }

    @Test
    void CONFLUENCE_DC_잡은_원본_정보_없이_만들_수_없다() throws Exception {
        Map<String, Object> body = Map.of(
                "provider", "CONFLUENCE_DC",
                "targetSpaceId", spaceId,
                "mode", "IMPORT");

        mvc.perform(post("/api/migration").with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("원본 컨플루언스 접속 정보가 필요합니다"));
    }

    @Test
    void http가_아닌_원본_주소는_거부한다() throws Exception {
        // SSRF 1차 방어 — 스킴을 열어두면 서버 로컬 파일을 읽게 만들 수 있다.
        Map<String, Object> body = Map.of(
                "provider", "CONFLUENCE_DC",
                "targetSpaceId", spaceId,
                "mode", "IMPORT",
                "source", Map.of("baseUrl", "file:///etc/passwd", "spaceKey", "ENG", "token", "t"));

        mvc.perform(post("/api/migration").with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("원본 컨플루언스 주소는 http 또는 https여야 합니다"));
    }

    @Test
    void 항목이_없는_잡은_시작할_수_없다() throws Exception {
        long jobId = createConfluenceJob();

        mvc.perform(post("/api/migration/{id}/start", jobId).with(asUser(ADMIN, "Admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value(MigrationJobService.MIGRATION_NOTHING_DISCOVERED));
    }

    @Test
    void 항목_목록은_상태로_거를_수_있다() throws Exception {
        long jobId = createJob(MigrationJobMode.IMPORT);
        mvc.perform(post("/api/migration/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enqueueBody("page-1"))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/migration/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items[0].externalObjectId").value("page-1"));

        mvc.perform(get("/api/migration/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .param("status", "DEAD_LETTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    /**
     * 위키가 닿지 않으면 "스페이스가 없다"가 아니라 503이다. 404로 뭉개면 위키가 잠깐 죽은 사이에
     * 만들려던 잡이 스페이스를 잘못 짚은 것처럼 보이고, 관리자가 엉뚱한 곳을 고치게 된다.
     */
    @Test
    void 위키가_닿지_않으면_503이다() throws Exception {
        wiki.failNextWith(503);

        mvc.perform(post("/api/migration").with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createBody(MigrationJobMode.IMPORT))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("위키에 연결할 수 없습니다"));
    }

    @Test
    void 없는_스페이스로는_job을_만들_수_없다() throws Exception {
        Map<String, Object> body = Map.of(
                "provider", "NOTION",
                "sourceInstanceId", "workspace-acme",
                "targetSpaceId", spaceId + 9999,
                "mode", "IMPORT");

        mvc.perform(post("/api/migration").with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void checksum_형식이_틀리면_400이다() throws Exception {
        long jobId = createJob(MigrationJobMode.IMPORT);
        Map<String, Object> body = Map.of(
                "externalObjectId", "page-1",
                "sourceVersion", "1",
                "sourceChecksum", "NOT-A-CHECKSUM",
                "payloadRef", "imports/page-1.json");

        mvc.perform(post("/api/migration/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    private long createJob(MigrationJobMode mode) throws Exception {
        String response = mvc.perform(post("/api/migration").with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createBody(mode))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private long createConfluenceJob() throws Exception {
        Map<String, Object> body = Map.of(
                "provider", "CONFLUENCE_DC",
                "targetSpaceId", spaceId,
                "mode", "IMPORT",
                "source", Map.of(
                        "baseUrl", "https://wiki.example.com/",
                        "spaceKey", "ENG",
                        "token", "pat-token"));
        String response = mvc.perform(post("/api/migration").with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private Map<String, Object> createBody(MigrationJobMode mode) {
        return Map.of(
                "provider", "NOTION",
                "sourceInstanceId", "workspace-acme",
                "targetSpaceId", spaceId,
                "mode", mode.name());
    }

    private Map<String, Object> enqueueBody(String externalObjectId) {
        return Map.of(
                "externalObjectId", externalObjectId,
                "sourceVersion", "1",
                "sourceChecksum", CHECKSUM,
                "payloadRef", "imports/" + externalObjectId + ".json");
    }
}
