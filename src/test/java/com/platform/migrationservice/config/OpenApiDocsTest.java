package com.platform.migrationservice.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.wiki.WikiImportTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `/v3/api-docs`가 문서 생성기(myFront `scripts/api`)가 쓸 수 있는 모양으로 나오는지 지킨다.
 *
 * 태그·요약이 비면 생성기가 "제목 없는 엔드포인트"를 뱉는데, 그건 실행해 보기 전에는 드러나지
 * 않는다. 그래서 새 컨트롤러가 주석 없이 들어오면 여기서 먼저 깨지게 한다.
 *
 * 회귀 항목은 wiki-backend와 같은 것을 본다 — 인증 주체 누출(alm-backend가 밟았다)과 성공 응답
 * 소실(org-service가 밟았다). 세 서비스의 문서가 나란히 실리므로 함정도 공유한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenApiDocsTest extends WikiImportTestSupport {

    private static final Map<String, String> CANONICAL = Map.of(
            "400", "요청 검증 실패",
            "401", "인증 실패 — 토큰 없음·만료·무효",
            "403", "권한 없음",
            "404", "대상 없음",
            "409", "버전 충돌 — expectedVersion 불일치",
            "503", "권한 서비스(org) 불능");
    private static final String ERROR_REF = "#/components/schemas/PlatformError";
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private JsonNode spec() throws Exception {
        // 토큰 없이 200이어야 한다 — 수집기는 인증 없이 컨테이너 네트워크에서 긁어 간다.
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new ObjectMapper().readTree(body);
    }

    @Test
    void 모든_오퍼레이션에_태그와_요약이_있다() throws Exception {
        List<String> missing = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            JsonNode tags = operation.get("tags");
            if (tags == null || !tags.isArray() || tags.isEmpty()) {
                missing.add(where + " — @Tag 없음");
            }
            JsonNode summary = operation.get("summary");
            if (summary == null || summary.asText().isBlank()) {
                missing.add(where + " — @Operation(summary) 없음");
            }
        });

        assertThat(missing).as("주석이 빠진 오퍼레이션").isEmpty();
    }

    /** "METHOD /경로" 라벨과 오퍼레이션 노드를 짝지어 훑는다. */
    private static void eachOperation(JsonNode spec, java.util.function.BiConsumer<String, JsonNode> visit) {
        spec.get("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
            if (HTTP_METHODS.contains(op.getKey())) {
                visit.accept(op.getKey().toUpperCase() + " " + path.getKey(), op.getValue());
            }
        }));
    }

    @Test
    void 문서에_들어간_오퍼레이션이_비어_있지_않다() throws Exception {
        JsonNode paths = spec().get("paths");
        long operations = 0;
        for (var path : paths.properties()) {
            for (var op : path.getValue().properties()) {
                if (HTTP_METHODS.contains(op.getKey())) {
                    operations++;
                }
            }
        }
        // 컨트롤러가 통째로 스캔에서 빠지는 회귀(예: springdoc 패키지 스캔 설정 실수)를 잡는다.
        assertThat(operations).isGreaterThanOrEqualTo(11);
    }

    /**
     * 인증 주체(@AuthenticationPrincipal Jwt)가 쿼리 파라미터로 새지 않는지.
     * 모든 쓰기 핸들러가 Jwt를 받으므로, 한 번 새면 오퍼레이션 전부에 가짜 파라미터가 붙는다.
     * alm-backend가 실제로 밟은 함정이라 여기서 회귀로 막는다.
     */
    @Test
    void 인증_주체가_파라미터로_새지_않는다() throws Exception {
        List<String> leaked = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            JsonNode parameters = operation.get("parameters");
            if (parameters == null) {
                return;
            }
            for (JsonNode parameter : parameters) {
                String name = parameter.path("name").asText("").toLowerCase();
                if (name.contains("jwt") || name.contains("principal")) {
                    leaked.add(where + " — " + name);
                }
            }
        });
        assertThat(leaked).as("인증 주체가 샌 파라미터").isEmpty();
    }

    /**
     * 성공 응답이 사라지지 않았는지.
     * 핸들러에 @ApiResponse를 하나라도 달면 springdoc이 반환 타입에서 200을 자동 생성하지 않는다 —
     * 4xx만 달면 성공 응답이 조용히 사라진다. 이 서비스가 실패 코드를 @ApiResponse가 아니라
     * {@link ApiFailures}로 선언하는 이유가 그것이고, 이 테스트가 그 이유를 지킨다.
     */
    @Test
    void 모든_오퍼레이션에_성공_응답이_있다() throws Exception {
        List<String> missing = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            boolean success = false;
            var codes = operation.path("responses").fieldNames();
            while (codes.hasNext()) {
                if (codes.next().startsWith("2")) {
                    success = true;
                }
            }
            if (!success) {
                missing.add(where);
            }
        });
        assertThat(missing).as("2xx 응답이 없는 오퍼레이션").isEmpty();
    }

    @Test
    void 내부_전용_경로는_문서에_없다() throws Exception {
        List<String> leaked = new ArrayList<>();
        spec().get("paths").fieldNames().forEachRemaining(path -> {
            if (path.startsWith("/internal")) {
                leaked.add(path);
            }
        });
        assertThat(leaked).as("문서에 샌 내부 경로").isEmpty();
    }

    @Test
    void 모든_경로는_api_migration_아래에_있다() throws Exception {
        List<String> outside = new ArrayList<>();
        spec().get("paths").fieldNames().forEachRemaining(path -> {
            if (!path.startsWith("/api/migration")) {
                outside.add(path);
            }
        });
        assertThat(outside).as("/api/migration 밖으로 나간 경로").isEmpty();
    }

    @Test
    void bearerAuth_보안_스킴이_전역으로_걸린다() throws Exception {
        JsonNode spec = spec();

        JsonNode scheme = spec.at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.isMissingNode()).isFalse();
        assertThat(scheme.get("type").asText()).isEqualTo("http");
        assertThat(scheme.get("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.get("description").asText()).contains("chanho_pat_");

        JsonNode security = spec.get("security");
        assertThat(security).isNotNull();
        assertThat(security.isArray()).isTrue();
        assertThat(security.toString()).contains("bearerAuth");
    }

    @Test
    void 공통_오류_스키마와_401_403이_붙는다() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.at("/components/schemas/PlatformError/properties/error/type").asText())
                .isEqualTo("string");

        JsonNode list = spec.at("/paths/~1api~1migration/get");
        assertThat(list.isMissingNode()).isFalse();
        assertThat(list.at("/responses/401").isMissingNode()).isFalse();
        assertThat(list.at("/responses/403").isMissingNode()).isFalse();
        // 목록 조회에는 404를 붙이지 않는다.
        assertThat(list.at("/responses/404").isMissingNode()).isTrue();

        // 경로 변수로 대상을 지목하면 404가 붙는다.
        assertThat(spec.at("/paths/~1api~1migration~1{jobId}/get/responses/404").isMissingNode())
                .isFalse();
    }

    /**
     * 409는 상태 충돌이 실제로 가능한 오퍼레이션에만 붙는다.
     *
     * 이 서비스에는 expectedVersion이 없다 — 409는 "이미 시작·종료된 잡"이라는 상태 충돌이고,
     * 그 사실은 규칙으로 알아낼 수 없어 오퍼레이션이 직접 선언한다.
     */
    @Test
    void 상태_충돌이_가능한_오퍼레이션에만_409가_붙는다() throws Exception {
        JsonNode spec = spec();

        assertThat(spec.at("/paths/~1api~1migration~1{jobId}~1start/post/responses/409").isMissingNode())
                .isFalse();
        assertThat(spec.at("/paths/~1api~1migration~1{jobId}~1cancel/post/responses/409").isMissingNode())
                .isFalse();
        assertThat(spec.at("/paths/~1api~1migration~1{jobId}~1items/post/responses/409").isMissingNode())
                .isFalse();
        assertThat(spec.at("/paths/~1api~1migration~1{jobId}~1discover/post/responses/409").isMissingNode())
                .isFalse();
        assertThat(spec.at("/paths/~1api~1migration~1{jobId}~1link-fixup/post/responses/409").isMissingNode())
                .isFalse();

        // 조회에는 충돌할 상태가 없다.
        assertThat(spec.at("/paths/~1api~1migration~1{jobId}~1report/get/responses/409").isMissingNode())
                .isTrue();
        assertThat(spec.at("/paths/~1api~1migration/get/responses/409").isMissingNode()).isTrue();

        // 문구는 네 서비스 공통값을 쓴다. 이 서비스의 409는 낙관적 락이 아니라 잡 상태 충돌이지만,
        // 실제 사유는 응답 본문의 error가 말한다 — 문서 문구를 갈라 놓지 않는다.
        assertThat(spec.at("/paths/~1api~1migration~1{jobId}~1start/post/responses/409/description")
                .asText()).isEqualTo("버전 충돌 — expectedVersion 불일치");
    }

    /**
     * 503은 전 오퍼레이션에 붙는다. 읽기든 쓰기든 org-service gRPC 권한 판정을 타고, 잡 생성은
     * 위키에도 묻는다 — 둘 중 하나가 죽으면 어떤 엔드포인트든 503이 나갈 수 있다.
     */
    @Test
    void 모든_오퍼레이션에_503이_붙는다() throws Exception {
        List<String> missing = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            JsonNode response = operation.path("responses").path("503");
            if (response.isMissingNode()) {
                missing.add(where);
            } else if (!ERROR_REF.equals(response.at("/content/application~1json/schema/$ref").asText())) {
                missing.add(where + " — PlatformError 스키마가 아님");
            }
        });
        assertThat(missing).as("503이 빠진 오퍼레이션").isEmpty();
    }

    /**
     * 400은 요청 본문이 있는 오퍼레이션에 자동으로, 그리고 본문 없이도 검증에 걸릴 수 있는
     * 오퍼레이션이 스스로 선언했을 때만 붙는다.
     *
     * 본문 없는 조회에 400을 붙이면 생성된 문서가 "아무 요청이나 400이 날 수 있다"고 잘못 말한다.
     * 반대로 여기 예외를 열어 두지 않으면 잡 시작·발견이 실제로 내는 400이 문서에서 사라진다 —
     * 그래서 예외를 **정확한 집합으로 못박아** 실수로 번지는 것만 막는다.
     */
    @Test
    void 본문이_있거나_스스로_선언한_오퍼레이션에만_400이_붙는다() throws Exception {
        Set<String> declaredWithoutBody = Set.of(
                "POST /api/migration/{jobId}/start",
                "POST /api/migration/{jobId}/discover");

        List<String> wrong = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            boolean hasBody = !operation.path("requestBody").isMissingNode();
            boolean has400 = !operation.path("responses").path("400").isMissingNode();
            if (hasBody && !has400) {
                wrong.add(where + " — 본문이 있는데 400 없음");
            }
            if (!hasBody && has400 && !declaredWithoutBody.contains(where)) {
                wrong.add(where + " — 본문도 선언도 없는데 400 있음");
            }
            if (!hasBody && !has400 && declaredWithoutBody.contains(where)) {
                wrong.add(where + " — 선언했는데 400 없음");
            }
        });
        assertThat(wrong).as("400 규칙에 어긋난 오퍼레이션").isEmpty();

        // 규칙이 실제로 무언가를 덮는지 — 본문 있는 오퍼레이션이 0개면 위 단언은 공허하게 통과한다.
        assertThat(spec().at("/paths/~1api~1migration/post/responses/400").isMissingNode()).isFalse();
        assertThat(spec().at("/paths/~1api~1migration~1{jobId}/get/responses/400").isMissingNode()).isTrue();
    }

    /**
     * 오류 설명 문구가 세 서비스 공통값 그대로인지. 상수를 읽지 않고 리터럴로 대조한다 —
     * 상수를 참조하면 문구가 바뀔 때 테스트도 같이 따라가서 아무것도 못 잡는다.
     */
    @Test
    void 오류_설명_문구가_세_서비스_공통값이다() throws Exception {
        JsonNode spec = spec();

        JsonNode write = spec.at("/paths/~1api~1migration/post/responses");
        assertThat(write.at("/400/description").asText()).isEqualTo("요청 검증 실패");
        assertThat(write.at("/401/description").asText()).isEqualTo("인증 실패 — 토큰 없음·만료·무효");
        assertThat(write.at("/403/description").asText()).isEqualTo("권한 없음");
        assertThat(write.at("/404/description").asText()).isEqualTo("대상 없음");
        assertThat(write.at("/503/description").asText()).isEqualTo("권한 서비스(org) 불능");

        // 한 곳만 맞고 나머지가 옛 문구로 남는 일이 없도록 전 오퍼레이션을 훑는다.
        List<String> drifted = new ArrayList<>();
        eachOperation(spec, (where, operation) -> operation.path("responses").properties().forEach(r -> {
            String expected = CANONICAL.get(r.getKey());
            if (expected != null && !expected.equals(r.getValue().path("description").asText())) {
                drifted.add(where + " " + r.getKey());
            }
        }));
        assertThat(drifted).as("문구가 어긋난 응답").isEmpty();
    }

    @Test
    void 서비스_메타가_스펙에_담긴다() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.at("/info/title").asText()).isEqualTo("Migration API");
        assertThat(spec.at("/info/version").asText()).isNotBlank();
        assertThat(spec.at("/servers/0/url").asText()).isEqualTo("/");
    }
}
