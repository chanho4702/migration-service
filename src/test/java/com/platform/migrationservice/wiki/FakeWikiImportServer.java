package com.platform.migrationservice.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 위키 내부 import API(설계 §2)를 흉내 내는 인메모리 서버.
 *
 * 왜 인메모리 페이크가 아니라 **진짜 HTTP 서버**인가: 이관 엔진이 위키와 만나는 유일한 자리가
 * {@link WikiImportClient}이고, 거기서 틀리기 쉬운 것이 JSON 필드 이름·multipart 조립·오류
 * 상태 코드 해석이다. 인터페이스를 가짜 구현으로 갈아 끼우면 그 셋이 전부 테스트를 빠져나간다.
 *
 * 여기 구현한 규칙은 계약이 요구하는 것만이다 — 알림·구독·감사·권한 검사는 없다(그게 이 API가
 * 따로 있는 이유다). 위키 쪽 실제 구현과 어긋나는 부분이 생기면 여기가 아니라 계약 문서를 먼저 고친다.
 */
public class FakeWikiImportServer {

    /** 테스트가 쓰는 내부 토큰. 이 값이 아니면 401이다 — 클라이언트가 헤더를 빠뜨리면 여기서 걸린다. */
    public static final String INTERNAL_TOKEN = "test-internal-token";

    private static final String PREFIX = "/internal/wiki/import";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final Map<Long, FakeSpace> spaces = new LinkedHashMap<>();
    private final Map<Long, FakePage> pages = new LinkedHashMap<>();
    private final Map<Long, FakeComment> comments = new LinkedHashMap<>();
    private final AtomicLong spaceIds = new AtomicLong(500);
    private final AtomicLong pageIds = new AtomicLong(1000);
    private final AtomicLong attachmentIds = new AtomicLong(2000);
    private final AtomicLong commentIds = new AtomicLong(3000);

    /** 마지막 요청이 실어 온 X-Actor-Id — 엔진이 잡 요청자를 제대로 싣는지 확인할 때 쓴다. */
    public volatile Long lastActorId;

    /** 0이 아니면 다음 요청 한 건을 이 상태로 거절한다 — 장애 전파를 태우는 스위치다. */
    private volatile int failNextStatus;

    public FakeWikiImportServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("가짜 위키 서버를 열지 못했다", exception);
        }
        server.createContext(PREFIX, this::dispatch);
        server.setExecutor(null);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    // -- 테스트가 들여다보는 상태 -------------------------------------

    public void reset() {
        spaces.clear();
        pages.clear();
        comments.clear();
        lastActorId = null;
        failNextStatus = 0;
    }

    /** 다음 요청 한 건만 이 상태로 거절한다(예: 503 — 위키가 잠깐 죽은 상황). */
    public void failNextWith(int status) {
        failNextStatus = status;
    }

    /** 대상 스페이스 하나를 심는다. 이관은 이미 있는 스페이스로만 들어간다. */
    public long putSpace(String name) {
        long id = spaceIds.incrementAndGet();
        spaces.put(id, new FakeSpace(id, name));
        return id;
    }

    public List<FakePage> pages() {
        return List.copyOf(pages.values());
    }

    public int pageCount() {
        return pages.size();
    }

    public FakePage pageTitled(String title) {
        return pages.values().stream()
                .filter(page -> title.equals(page.title))
                .findFirst()
                .orElseThrow(() -> new AssertionError("문서를 찾을 수 없다: " + title));
    }

    public FakePage page(long pageId) {
        FakePage page = pages.get(pageId);
        if (page == null) {
            throw new AssertionError("문서를 찾을 수 없다: " + pageId);
        }
        return page;
    }

    /** 사람이 문서를 지운 상황을 만든다(재실행이 다시 만드는지 보는 시나리오). */
    public void removePage(long pageId) {
        pages.remove(pageId);
    }

    /** 사람이 제목을 고친 상황을 만든다(VERIFY 대조 실패 시나리오). */
    public void retitle(long pageId, String title) {
        page(pageId).title = title;
    }

    /** 같은 제목의 문서를 하나 더 심는다 — 제목 링크 모호 판정을 태우는 자리다. */
    public long putPage(long spaceId, String title, String content) {
        long id = pageIds.incrementAndGet();
        FakePage page = new FakePage(id, spaceId, null, "page", title, content);
        pages.put(id, page);
        return id;
    }

    public List<FakeComment> commentsOf(long pageId) {
        return comments.values().stream()
                .filter(comment -> comment.pageId == pageId)
                .sorted(Comparator.comparing((FakeComment comment) -> comment.createdAt)
                        .thenComparingLong(comment -> comment.id))
                .toList();
    }

    // -- 라우팅 ------------------------------------------------------

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            if (!INTERNAL_TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
                respondError(exchange, 401, "내부 토큰이 올바르지 않습니다");
                return;
            }
            String actor = exchange.getRequestHeaders().getFirst("X-Actor-Id");
            if (actor == null || actor.isBlank()) {
                respondError(exchange, 400, "요청자를 알 수 없습니다");
                return;
            }
            lastActorId = Long.parseLong(actor);

            int forced = failNextStatus;
            if (forced != 0) {
                failNextStatus = 0;
                respondError(exchange, forced, "위키가 지금은 응답할 수 없습니다");
                return;
            }

            String path = exchange.getRequestURI().getPath().substring(PREFIX.length());
            String method = exchange.getRequestMethod();
            String[] segments = Arrays.stream(path.split("/"))
                    .filter(segment -> !segment.isBlank())
                    .toArray(String[]::new);
            route(exchange, method, segments);
        } catch (AssertionError | RuntimeException exception) {
            respondError(exchange, 500, String.valueOf(exception.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String method, String[] segments) throws IOException {
        if (segments.length == 1 && segments[0].equals("pages") && method.equals("POST")) {
            createPage(exchange);
            return;
        }
        if (segments.length == 2 && segments[0].equals("pages")) {
            long pageId = Long.parseLong(segments[1]);
            if (method.equals("PUT")) {
                updatePage(exchange, pageId);
                return;
            }
            if (method.equals("GET")) {
                readPage(exchange, pageId);
                return;
            }
        }
        if (segments.length == 3 && segments[0].equals("pages")) {
            long pageId = Long.parseLong(segments[1]);
            switch (segments[2]) {
                case "content" -> {
                    if (method.equals("PUT")) {
                        replaceContent(exchange, pageId);
                        return;
                    }
                }
                case "order" -> {
                    if (method.equals("PUT")) {
                        reorder(exchange, pageId);
                        return;
                    }
                }
                case "attachments" -> {
                    if (method.equals("POST")) {
                        uploadAttachment(exchange, pageId);
                        return;
                    }
                }
                case "comments" -> {
                    if (method.equals("POST")) {
                        createComment(exchange, pageId);
                        return;
                    }
                }
                case "restrictions" -> {
                    if (method.equals("PUT")) {
                        replaceRestrictions(exchange, pageId);
                        return;
                    }
                }
                default -> { }
            }
        }
        if (segments.length == 2 && segments[0].equals("comments") && method.equals("GET")) {
            long commentId = Long.parseLong(segments[1]);
            FakeComment comment = comments.get(commentId);
            if (comment != null) {
                respond(exchange, 200, "{\"commentId\":" + commentId
                        + ",\"pageId\":" + comment.pageId()
                        + ",\"parentCommentId\":" + (comment.parentId() == null ? "null" : comment.parentId())
                        + ",\"createdAt\":" + quote(comment.createdAt().toString()) + "}");
            } else {
                respondError(exchange, 404, "댓글을 찾을 수 없습니다");
            }
            return;
        }
        if (segments.length == 2 && segments[0].equals("spaces") && method.equals("GET")) {
            readSpace(exchange, Long.parseLong(segments[1]));
            return;
        }
        if (segments.length == 3 && segments[0].equals("spaces") && segments[2].equals("pages")
                && method.equals("GET")) {
            findByTitle(exchange, Long.parseLong(segments[1]));
            return;
        }
        respondError(exchange, 404, "없는 경로입니다");
    }

    // -- 핸들러 ------------------------------------------------------

    private void createPage(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        long spaceId = body.path("spaceId").asLong();
        if (!spaces.containsKey(spaceId)) {
            respondError(exchange, 404, "스페이스를 찾을 수 없습니다: " + spaceId);
            return;
        }
        long pageId = pageIds.incrementAndGet();
        FakePage page = new FakePage(pageId, spaceId, nullableLong(body, "parentId"),
                body.path("type").asText("page").toLowerCase(Locale.ROOT),
                body.path("title").asText(""), body.path("content").asText(""));
        page.createdAt = Instant.parse(body.path("createdAt").asText());
        page.updatedAt = Instant.parse(body.path("updatedAt").asText());
        // authorId가 오면 그 사람이 쓴 문서고, 없으면 X-Actor-Id가 작성자로 눕고 이름이 표시로 남는다.
        Long authorId = nullableLong(body, "authorId");
        boolean mapped = authorId != null;
        page.authorId = mapped ? authorId : lastActorId;
        page.importedAuthorName = mapped ? null : nullableText(body, "importedAuthorName");
        page.importedSourceUrl = mapped ? null : nullableText(body, "sourceUrl");
        page.labels.addAll(normalizedLabels(body.path("labels")));
        Long sortOrder = nullableLong(body, "sortOrder");
        page.sortOrder = sortOrder != null ? sortOrder : nextSortOrder(spaceId, page.parentId);

        // 지난 버전이 오면 1..k로 깔고 현재본을 k+1로 둔다(§2 POST /pages).
        int version = 0;
        for (JsonNode revision : body.path("revisions")) {
            version++;
            page.revisions.add(new FakeRevision(revision.path("version").asInt(version),
                    revision.path("title").asText(page.title), revision.path("content").asText(""),
                    nullableText(revision, "editorName"), nullableText(revision, "changeNote"),
                    instantOrNull(nullableText(revision, "savedAt"))));
        }
        version++;
        page.version = version;
        // 현재본 리비전의 편집자 이름은 imported_author_name과 같은 값이다 — 대조된 작성자는
        // 둘 다 비어 우리 사용자로 보인다.
        page.revisions.add(new FakeRevision(version, page.title, page.content,
                page.importedAuthorName, null, page.updatedAt));
        pages.put(pageId, page);

        respond(exchange, 201, "{\"pageId\":" + pageId + ",\"version\":" + version
                + ",\"issues\":[]}");
    }

    private void updatePage(HttpExchange exchange, long pageId) throws IOException {
        FakePage page = pages.get(pageId);
        if (page == null) {
            respondError(exchange, 404, "문서를 찾을 수 없습니다: " + pageId);
            return;
        }
        JsonNode body = readJson(exchange);
        page.title = body.path("title").asText(page.title);
        page.content = body.path("content").asText(page.content);
        page.updatedAt = Instant.parse(body.path("updatedAt").asText());
        page.labels.clear();
        page.labels.addAll(normalizedLabels(body.path("labels")));
        // 재이관은 순번을 받지 않는다 — 순서 변경은 PUT /order로 따로 온다(계약).
        boolean reimportMapped = nullableLong(body, "editorId") != null;
        page.importedAuthorName = reimportMapped ? null : nullableText(body, "editorName");
        page.importedSourceUrl = reimportMapped ? null : nullableText(body, "sourceUrl");
        page.version++;
        page.revisions.add(new FakeRevision(page.version, page.title, page.content,
                reimportMapped ? null : nullableText(body, "editorName"),
                nullableText(body, "changeNote"), page.updatedAt));
        respond(exchange, 200, "{\"pageId\":" + pageId + ",\"version\":" + page.version
                + ",\"issues\":[]}");
    }

    private void replaceContent(HttpExchange exchange, long pageId) throws IOException {
        FakePage page = pages.get(pageId);
        if (page == null) {
            respondError(exchange, 404, "문서를 찾을 수 없습니다: " + pageId);
            return;
        }
        JsonNode body = readJson(exchange);
        String content = body.path("content").asText("");
        boolean changed = !content.equals(page.content);
        if (body.path("bumpVersion").asBoolean(false)) {
            page.content = content;
            page.version++;
            page.revisions.add(new FakeRevision(page.version, page.title, content, null,
                    nullableText(body, "changeNote"), page.updatedAt));
        } else {
            // 버전을 올리지 않는 본문 교체 — 현재 리비전의 본문도 함께 바꿔 이력이 거짓이 되지 않게 한다.
            page.content = content;
            if (!page.revisions.isEmpty()) {
                page.revisions.get(page.revisions.size() - 1).content = content;
            }
        }
        respond(exchange, 200, "{\"pageId\":" + pageId + ",\"version\":" + page.version
                + ",\"changed\":" + changed + "}");
    }

    private void reorder(HttpExchange exchange, long pageId) throws IOException {
        FakePage page = pages.get(pageId);
        if (page == null) {
            respondError(exchange, 404, "문서를 찾을 수 없습니다: " + pageId);
            return;
        }
        long sortOrder = readJson(exchange).path("sortOrder").asLong();
        boolean changed = page.sortOrder != sortOrder;
        page.sortOrder = sortOrder;
        respond(exchange, 200, "{\"pageId\":" + pageId + ",\"sortOrder\":" + sortOrder
                + ",\"changed\":" + changed + "}");
    }

    private void uploadAttachment(HttpExchange exchange, long pageId) throws IOException {
        FakePage page = pages.get(pageId);
        if (page == null) {
            respondError(exchange, 404, "문서를 찾을 수 없습니다: " + pageId);
            return;
        }
        Multipart parsed = Multipart.parse(exchange);
        String filename = parsed.field("filename");
        String contentType = parsed.field("contentType");
        String checksum = parsed.field("checksum");
        if (filename == null || parsed.file == null) {
            respondError(exchange, 400, "첨부 파일이 없습니다");
            return;
        }
        FakeAttachment existing = page.attachments.stream()
                .filter(attachment -> attachment.filename.equals(filename))
                .findFirst().orElse(null);
        String outcome;
        FakeAttachment attachment;
        if (existing == null) {
            attachment = new FakeAttachment(attachmentIds.incrementAndGet(), filename, contentType,
                    parsed.file.length, checksum);
            page.attachments.add(attachment);
            outcome = "CREATED";
        } else if (existing.checksum != null && existing.checksum.equals(checksum)) {
            // 같은 이름·같은 내용이면 아무것도 하지 않는다 — 재이관이 버전만 쌓는 것을 막는 자리다.
            attachment = existing;
            outcome = "UNCHANGED";
        } else {
            existing.version++;
            existing.contentType = contentType;
            existing.size = parsed.file.length;
            existing.checksum = checksum;
            attachment = existing;
            outcome = "NEW_VERSION";
        }
        respond(exchange, 200, "{\"attachmentId\":" + attachment.id
                + ",\"inlineUrl\":\"/api/wiki/attachments/" + attachment.id + "/inline\""
                + ",\"downloadUrl\":\"/api/wiki/attachments/" + attachment.id + "\""
                + ",\"outcome\":\"" + outcome + "\"}");
    }

    private void createComment(HttpExchange exchange, long pageId) throws IOException {
        if (!pages.containsKey(pageId)) {
            respondError(exchange, 404, "문서를 찾을 수 없습니다: " + pageId);
            return;
        }
        JsonNode body = readJson(exchange);
        long commentId = commentIds.incrementAndGet();
        Long commentAuthor = nullableLong(body, "authorId");
        FakeComment comment = new FakeComment(commentId, pageId, nullableLong(body, "parentCommentId"),
                commentAuthor == null ? lastActorId : commentAuthor,
                commentAuthor == null ? nullableText(body, "authorName") : null,
                body.path("body").asText(""), Instant.parse(body.path("createdAt").asText()));
        comments.put(commentId, comment);
        respond(exchange, 201, "{\"commentId\":" + commentId + "}");
    }

    private void replaceRestrictions(HttpExchange exchange, long pageId) throws IOException {
        FakePage page = pages.get(pageId);
        if (page == null) {
            respondError(exchange, 404, "문서를 찾을 수 없습니다: " + pageId);
            return;
        }
        JsonNode body = readJson(exchange);
        page.viewRestrictions.clear();
        page.editRestrictions.clear();
        for (JsonNode principal : body.path("view")) {
            page.viewRestrictions.add(new FakePrincipal(principal.path("type").asText(""),
                    principal.path("id").asLong()));
        }
        for (JsonNode principal : body.path("edit")) {
            page.editRestrictions.add(new FakePrincipal(principal.path("type").asText(""),
                    principal.path("id").asLong()));
        }
        respond(exchange, 204, "");
    }

    private void readPage(HttpExchange exchange, long pageId) throws IOException {
        FakePage page = pages.get(pageId);
        if (page == null) {
            respondError(exchange, 404, "문서를 찾을 수 없습니다: " + pageId);
            return;
        }
        StringBuilder out = new StringBuilder("{\"pageId\":").append(page.id)
                .append(",\"spaceId\":").append(page.spaceId)
                .append(",\"parentId\":").append(page.parentId == null ? "null" : page.parentId)
                .append(",\"title\":").append(quote(page.title))
                .append(",\"type\":\"").append(page.type).append('"')
                .append(",\"contentLength\":").append(page.content == null ? 0 : page.content.length())
                .append(",\"version\":").append(page.version)
                .append(",\"sortOrder\":").append(page.sortOrder)
                .append(",\"commentCount\":").append(commentsOf(pageId).size())
                .append(",\"labels\":[");
        out.append(String.join(",", page.labels.stream().map(FakeWikiImportServer::quote).toList()));
        out.append("],\"attachments\":[");
        List<String> attachments = new ArrayList<>();
        for (FakeAttachment attachment : page.attachments) {
            attachments.add("{\"id\":" + attachment.id + ",\"filename\":" + quote(attachment.filename)
                    + ",\"checksum\":" + quote(attachment.checksum) + "}");
        }
        out.append(String.join(",", attachments)).append("]}");
        respond(exchange, 200, out.toString());
    }

    private void readSpace(HttpExchange exchange, long spaceId) throws IOException {
        FakeSpace space = spaces.get(spaceId);
        if (space == null) {
            respondError(exchange, 404, "스페이스를 찾을 수 없습니다: " + spaceId);
            return;
        }
        respond(exchange, 200, "{\"spaceId\":" + spaceId + ",\"key\":" + quote("SP" + spaceId)
                + ",\"name\":" + quote(space.name) + "}");
    }

    private void findByTitle(HttpExchange exchange, long spaceId) throws IOException {
        String title = queryParam(exchange, "title");
        List<String> rows = new ArrayList<>();
        for (FakePage page : pages.values()) {
            if (page.spaceId == spaceId && page.title != null && page.title.equalsIgnoreCase(title)) {
                rows.add("{\"pageId\":" + page.id + ",\"title\":" + quote(page.title)
                        + ",\"type\":\"" + page.type + "\"}");
            }
        }
        respond(exchange, 200, "{\"pages\":[" + String.join(",", rows) + "]}");
    }

    // -- 보조 -------------------------------------------------------

    private long nextSortOrder(long spaceId, Long parentId) {
        long max = -1;
        for (FakePage page : pages.values()) {
            if (page.spaceId == spaceId && java.util.Objects.equals(page.parentId, parentId)) {
                max = Math.max(max, page.sortOrder);
            }
        }
        return max + 1;
    }

    /** 위키의 라벨 규칙 — 소문자·공백은 하이픈·중복 제거. VERIFY의 라벨 수 대조가 이것에 기댄다. */
    private static List<String> normalizedLabels(JsonNode labels) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (JsonNode label : labels) {
            String value = label.asText("").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
            if (!value.isEmpty() && value.length() <= 64) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        return json.readTree(body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body);
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Instant instantOrNull(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 오류 계약은 위키와 같다 — `{"error": 메시지}`. */
    private void respondError(HttpExchange exchange, int status, String message) throws IOException {
        respond(exchange, status, "{\"error\":" + quote(message) + "}");
    }

    // -- 저장된 것들 -------------------------------------------------

    public record FakeSpace(long id, String name) {
    }

    public static class FakePage {
        public final long id;
        public final long spaceId;
        public Long parentId;
        public String type;
        public String title;
        public String content;
        public Instant createdAt;
        public Instant updatedAt;
        public Long authorId;
        public String importedAuthorName;
        public String importedSourceUrl;
        public long sortOrder;
        public int version;
        public final LinkedHashSet<String> labels = new LinkedHashSet<>();
        public final List<FakeRevision> revisions = new ArrayList<>();
        public final List<FakeAttachment> attachments = new ArrayList<>();
        public final List<FakePrincipal> viewRestrictions = new ArrayList<>();
        public final List<FakePrincipal> editRestrictions = new ArrayList<>();

        FakePage(long id, long spaceId, Long parentId, String type, String title, String content) {
            this.id = id;
            this.spaceId = spaceId;
            this.parentId = parentId;
            this.type = type;
            this.title = title;
            this.content = content;
            this.version = 1;
        }

        public Optional<FakeAttachment> attachment(String filename) {
            return attachments.stream().filter(file -> file.filename.equals(filename)).findFirst();
        }
    }

    public static class FakeRevision {
        public final int version;
        public final String title;
        public String content;
        public final String editorName;
        public final String changeNote;
        public final Instant savedAt;

        FakeRevision(int version, String title, String content, String editorName, String changeNote,
                     Instant savedAt) {
            this.version = version;
            this.title = title;
            this.content = content;
            this.editorName = editorName;
            this.changeNote = changeNote;
            this.savedAt = savedAt;
        }
    }

    public static class FakeAttachment {
        public final long id;
        public final String filename;
        public String contentType;
        public long size;
        public String checksum;
        public int version = 1;

        FakeAttachment(long id, String filename, String contentType, long size, String checksum) {
            this.id = id;
            this.filename = filename;
            this.contentType = contentType;
            this.size = size;
            this.checksum = checksum;
        }
    }

    public record FakePrincipal(String type, long id) {
    }

    public record FakeComment(long id, long pageId, Long parentId, Long authorId, String authorName,
                              String body, Instant createdAt) {
    }

    /**
     * 아주 작은 multipart 파서 — 우리 클라이언트가 보내는 형태(텍스트 필드 여럿 + 파일 하나)만
     * 읽는다. 진짜 파서를 쓰지 않는 이유는 이 테스트가 검증하려는 것이 "우리가 올바른 multipart를
     * 만드는가"이기 때문이다. 여기서 관대하게 읽으면 그 검증이 없어진다.
     */
    private static final class Multipart {
        private final Map<String, String> fields = new LinkedHashMap<>();
        private byte[] file;

        String field(String name) {
            return fields.get(name);
        }

        static Multipart parse(HttpExchange exchange) throws IOException {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                throw new AssertionError("multipart가 아니다: " + contentType);
            }
            int marker = contentType.indexOf("boundary=");
            if (marker < 0) {
                throw new AssertionError("boundary가 없다");
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            byte[] delimiter = ("--" + contentType.substring(marker + "boundary=".length()).trim())
                    .getBytes(StandardCharsets.UTF_8);

            Multipart parsed = new Multipart();
            for (int[] range : split(body, delimiter)) {
                int start = range[0];
                int end = range[1];
                int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), start);
                if (headerEnd < 0 || headerEnd > end) {
                    continue;
                }
                String headers = new String(body, start, headerEnd - start, StandardCharsets.UTF_8);
                int contentStart = headerEnd + 4;
                int contentEnd = Math.max(contentStart, end - 2);   // 마지막 CRLF는 구분자의 것이다
                String name = valueOf(headers, "name=\"");
                if (name == null) {
                    continue;
                }
                if (headers.contains("filename=\"")) {
                    parsed.file = Arrays.copyOfRange(body, contentStart, contentEnd);
                } else {
                    parsed.fields.put(name,
                            new String(body, contentStart, contentEnd - contentStart, StandardCharsets.UTF_8));
                }
            }
            return parsed;
        }

        private static List<int[]> split(byte[] body, byte[] delimiter) {
            List<int[]> ranges = new ArrayList<>();
            int cursor = indexOf(body, delimiter, 0);
            while (cursor >= 0) {
                int partStart = cursor + delimiter.length;
                if (partStart + 2 <= body.length && body[partStart] == '-' && body[partStart + 1] == '-') {
                    break;   // 종료 구분자
                }
                partStart += 2;   // 구분자 뒤의 CRLF
                int next = indexOf(body, delimiter, partStart);
                if (next < 0) {
                    break;
                }
                ranges.add(new int[]{partStart, next});
                cursor = next;
            }
            return ranges;
        }

        private static int indexOf(byte[] haystack, byte[] needle, int from) {
            outer:
            for (int index = from; index <= haystack.length - needle.length; index++) {
                for (int offset = 0; offset < needle.length; offset++) {
                    if (haystack[index + offset] != needle[offset]) {
                        continue outer;
                    }
                }
                return index;
            }
            return -1;
        }

        private static String valueOf(String headers, String key) {
            int start = headers.indexOf(key);
            if (start < 0) {
                return null;
            }
            int from = start + key.length();
            int end = headers.indexOf('"', from);
            return end < 0 ? null : headers.substring(from, end);
        }
    }
}
