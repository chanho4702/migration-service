package com.platform.migrationservice.migration.confluence.dc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DC 클라이언트 계약을 가짜 DC 서버(JDK HttpServer)로 고정한다.
 *
 * 실기 DC가 없어 응답을 흉내 내는 것이지만, 여기서 검증하는 것은 원본의 정확한 JSON이 아니라
 * **우리 쪽 규칙**이다: 상태 코드별 재시도 여부, 페이지네이션 종료 조건, 리다이렉트 거부,
 * 토큰이 헤더로만 나가는지.
 */
class ConfluenceDcClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> receivedPaths = new ArrayList<>();
    private final List<String> receivedAuthorization = new ArrayList<>();

    private final ConfluenceDcProperties properties = new ConfluenceDcProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(2), 5000, 2, 2, 1024L, 3, 2048L, 2);
    private final ConfluenceDcClient client = new ConfluenceDcClient(new ObjectMapper(), properties);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 페이지네이션은_받은_수가_limit보다_적을_때_끝난다() {
        AtomicInteger calls = new AtomicInteger();
        respond("/rest/api/content", exchange -> {
            int call = calls.incrementAndGet();
            return call == 1
                    ? json(200, """
                    {"results":[
                      {"id":"1","title":"A","version":{"number":2},"ancestors":[]},
                      {"id":"2","title":"B","version":{"number":1},"ancestors":[{"id":"1"}]}
                    ]}""")
                    : json(200, """
                    {"results":[{"id":"3","title":"C","version":{"number":5},"ancestors":[]}]}""");
        });

        ConfluenceContentPage first = client.listPages(credentials(), 0);
        ConfluenceContentPage second = client.listPages(credentials(), 2);

        assertThat(first.results()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.results().get(1).parentId()).isEqualTo("1");
        assertThat(first.results().get(1).depth()).isEqualTo(1);
        assertThat(second.hasMore()).isFalse();
        // start는 우리가 센다 — 응답의 _links.next를 따라가면 baseUrl 검증이 무의미해진다.
        assertThat(receivedPaths.get(1)).contains("start=2").contains("limit=2");
    }

    @Test
    void 토큰은_Authorization_헤더로만_나가고_URL에는_없다() {
        respond("/rest/api/content", exchange -> json(200, """
                {"results":[{"id":"1","title":"A","version":{"number":1},"ancestors":[]}]}"""));

        client.listPages(credentials(), 0);

        assertThat(receivedAuthorization).containsExactly("Bearer secret-token");
        assertThat(receivedPaths.get(0)).doesNotContain("secret-token");
    }

    @Test
    void rate_limit과_서버_오류는_재시도_대상이다() {
        respond("/rest/api/content", exchange -> json(429, "{}"));

        assertThatThrownBy(() -> client.listPages(credentials(), 0))
                .isInstanceOfSatisfying(MigrationStageException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ConfluenceDcCodes.UNAVAILABLE);
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    void 인증_실패는_재시도하지_않는다() {
        respond("/rest/api/content", exchange -> json(401, "{}"));

        assertThatThrownBy(() -> client.listPages(credentials(), 0))
                .isInstanceOfSatisfying(MigrationStageException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ConfluenceDcCodes.AUTH);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void 없는_문서는_데드레터_대상이다() {
        respond("/rest/api/content/", exchange -> json(404, "{}"));

        assertThatThrownBy(() -> client.content(credentials(), "999"))
                .isInstanceOfSatisfying(MigrationStageException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ConfluenceDcCodes.NOT_FOUND);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void 리다이렉트는_따라가지_않고_거부한다() {
        // 따라가면 검증한 baseUrl 밖(내부망)으로 끌려갈 수 있다 — SSRF 방지의 핵심 조항이다.
        respond("/rest/api/content", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            return new Response(302, "");
        });

        assertThatThrownBy(() -> client.listPages(credentials(), 0))
                .isInstanceOfSatisfying(MigrationStageException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ConfluenceDcCodes.REDIRECT_REFUSED));
    }

    @Test
    void 연결_확인은_스페이스_이름과_페이지_수를_준다() {
        respond("/rest/api/space/", exchange ->
                json(200, """
                        {"name":"Engineering","homepage":{"id":"100"}}"""));
        respond("/rest/api/content", exchange -> json(200, """
                {"results":[{"id":"1","title":"A","version":{"number":1},"ancestors":[]}],"totalSize":42}"""));

        ConfluenceSpaceProbe probe = client.probe(credentials());

        assertThat(probe.spaceName()).isEqualTo("Engineering");
        assertThat(probe.homepageId()).isEqualTo("100");
        assertThat(probe.pageCount()).isEqualTo(42);
    }

    @Test
    void 총계를_주지_않는_사이트에서도_연결_확인은_성공한다() {
        respond("/rest/api/space/", exchange -> json(200, """
                {"name":"Engineering","homepage":{"id":"100"}}"""));
        // 총계 조회만 막는 사이트가 있다. 부가 정보 때문에 연결 확인 전체를 실패시키지 않는다.
        respond("/rest/api/content", exchange -> json(500, "{}"));

        ConfluenceSpaceProbe probe = client.probe(credentials());

        assertThat(probe.spaceName()).isEqualTo("Engineering");
        assertThat(probe.pageCount()).isNull();
    }

    @Test
    void JSON이_아닌_응답은_비재시도_실패다() {
        respond("/rest/api/content", exchange -> new Response(200, "<html>로그인 페이지</html>"));

        assertThatThrownBy(() -> client.listPages(credentials(), 0))
                .isInstanceOfSatisfying(MigrationStageException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ConfluenceDcCodes.INVALID_RESPONSE);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    private ConfluenceDcCredentials credentials() {
        return new ConfluenceDcCredentials(baseUrl, "ENG", "secret-token");
    }

    private void respond(String path, Handler handler) {
        server.createContext(path, exchange -> {
            receivedPaths.add(exchange.getRequestURI().toString());
            receivedAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            Response response = handler.handle(exchange);
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
    }

    private static Response json(int status, String body) {
        return new Response(status, body);
    }

    private record Response(int status, String body) {
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
