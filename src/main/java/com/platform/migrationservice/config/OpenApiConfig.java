package com.platform.migrationservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Map;

/**
 * `GET /v3/api-docs`로 나가는 OpenAPI 3 스펙의 메타와 공통 오류 응답.
 *
 * UI(swagger-ui/scalar)는 붙이지 않는다 — 이 스펙은 사람이 브라우저로 보는 것이 아니라
 * myFront의 `scripts/api`가 긁어 가 문서 위키 페이지를 생성하는 입력이다.
 * 게이트웨이·nginx가 `/v3`를 라우팅하지 않으므로 클러스터 안에서만 보인다.
 *
 * 구성은 wiki-backend의 같은 이름 클래스를 따른다 — 생성기가 세 서비스의 문서를 나란히 싣기
 * 때문에 메타·스키마 이름·오류 문구가 갈리면 그대로 드러난다.
 */
@Configuration
public class OpenApiConfig {

    /** common-starter의 오류 계약 — 어떤 실패든 바디는 이 모양 하나뿐이다. */
    static final String ERROR_SCHEMA = "PlatformError";
    private static final String ERROR_REF = "#/components/schemas/" + ERROR_SCHEMA;
    private static final String BEARER = "bearerAuth";

    /**
     * 오류 설명 문구. wiki·alm·org와 같은 문자열을 쓴다(참고: alm-backend
     * {@code config/OpenApiConfig.ERROR_DESCRIPTIONS}) — **여기만 바꾸지 않는다.** 생성기가 네
     * 서비스의 문서를 나란히 싣기 때문에 표현이 갈리면 그대로 드러난다.
     *
     * 409 문구는 낙관적 락을 가리키지만 이 서비스의 409는 잡 상태 충돌(이미 시작·종료된 작업)이다.
     * 문구를 갈라 놓기보다 공통값을 쓰기로 정했다 — 실제 메시지는 응답 본문의 `error`가 말한다
     * (예: "이미 시작된 job입니다: RUNNING").
     */
    static final Map<String, String> ERROR_DESCRIPTIONS = Map.of(
            "400", "요청 검증 실패",
            "401", "인증 실패 — 토큰 없음·만료·무효",
            "403", "권한 없음",
            "404", "대상 없음",
            "409", "버전 충돌 — expectedVersion 불일치",
            "503", "권한 서비스(org) 불능");

    @Bean
    OpenAPI migrationOpenApi(@Value("${spring.application.version:0.1.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("Migration API")
                        .version(version)
                        .description("""
                                컨플루언스 설치형(Server/DC)·노션 원본을 읽어 위키로 옮기는 이관 엔진.
                                모든 경로는 `/api/migration` 아래에 있고, 인가는 org-service의 전역 grant 또는
                                대상 스페이스 ADMIN으로 판정한다. 실제 문서 쓰기는 위키의 내부 import API를 거치며
                                그 표면은 이 문서에 실리지 않는다.
                                오류 응답은 플랫폼 공통 계약인 `{"error": "메시지"}` 한 가지다."""))
                .servers(List.of(new Server().url("/").description("게이트웨이 뒤 이관 서비스")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("개인 API 토큰 `chanho_pat_…` 또는 세션 JWT"))
                        .addSchemas(ERROR_SCHEMA, platformError()))
                // 공개 엔드포인트가 없으므로 전역으로 건다 — 오퍼레이션별 예외를 두지 않는다.
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    private static Schema<Object> platformError() {
        return new ObjectSchema()
                .description("플랫폼 공통 오류 응답. 메시지는 한국어이며 사용자에게 그대로 보인다.")
                .addProperty("error", new StringSchema()
                        .description("사용자에게 보일 오류 메시지")
                        .example("이미 시작된 job입니다: RUNNING"))
                .required(List.of("error"));
    }

    /**
     * 컨트롤러가 직접 선언하지 않는 공통 실패를 채운다. 코드가 실제로 낼 수 있는 것만 넣는다.
     *
     * <ul>
     *   <li>401·403 — 모든 경로가 인증을 요구하고(SecurityConfig) 전역 관리자 또는 대상 스페이스
     *       ADMIN을 판정한다.</li>
     *   <li>400 — 요청 본문이 있는 오퍼레이션만. 본문 없는 GET에는 검증할 것이 없다.</li>
     *   <li>404 — 경로 변수로 대상을 지목하는 오퍼레이션만. 목록 조회에는 붙이지 않는다.</li>
     *   <li>503 — 전부. 읽기·쓰기가 모두 org-service gRPC 권한 판정을 타고, 잡 생성은 위키에도
     *       묻는다. 둘 중 하나가 불능이면 {@code ServiceUnavailableException}으로 503이 나간다.</li>
     *   <li>그 밖의 코드는 {@link ApiFailures}로 오퍼레이션이 직접 선언한다 — 규칙으로는
     *       알아낼 수 없는 것들이다(상태 충돌 409, 본문의 id가 가리키는 대상의 404).</li>
     * </ul>
     *
     * 400·503 두 줄은 wiki-backend·alm-backend·org-service와 맞춘 플랫폼 공통 규칙이다.
     * 이미 선언된 응답 코드는 건드리지 않는다 — 컨트롤러 주석이 항상 이긴다.
     */
    @Bean
    OperationCustomizer commonErrorResponses() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            addIfAbsent(responses, "401");
            addIfAbsent(responses, "403");
            if (hasRequestBody(handlerMethod)) {
                addIfAbsent(responses, "400");
            }
            if (hasPathVariable(handlerMethod)) {
                addIfAbsent(responses, "404");
            }
            addIfAbsent(responses, "503");
            ApiFailures declared = handlerMethod.getMethodAnnotation(ApiFailures.class);
            if (declared != null) {
                for (String code : declared.value()) {
                    addIfAbsent(responses, code);
                }
            }
            return operation;
        };
    }

    private static void addIfAbsent(ApiResponses responses, String code) {
        if (responses.containsKey(code)) {
            return;
        }
        responses.addApiResponse(code, new ApiResponse()
                .description(ERROR_DESCRIPTIONS.get(code))
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<Object>().$ref(ERROR_REF)))));
    }

    /**
     * 요청 본문을 받는가.
     *
     * 오퍼레이션의 {@code requestBody} 유무로 판정하지 않는 이유: 그건 springdoc이 이 커스터마이저
     * 전에 채워 두었는지에 기댄다. 핸들러 시그니처는 실행 순서와 무관하다.
     */
    private static boolean hasRequestBody(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(RequestBody.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPathVariable(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(PathVariable.class)) {
                return true;
            }
        }
        return false;
    }
}
