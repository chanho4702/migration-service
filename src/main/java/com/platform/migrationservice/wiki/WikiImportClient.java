package com.platform.migrationservice.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 위키 내부 import API를 부르는 JDK HttpClient 구현.
 *
 * 세 가지가 이 클래스의 규칙이다.
 *
 * 1. **주소를 조립하는 곳은 여기뿐이다.** 원본 응답이 알려 준 URL을 따라가지 않고 고정 패턴으로만
 *    만든다 — 이관 엔진 전체에 걸린 SSRF 규칙을 위키 호출에도 그대로 적용한다.
 * 2. **토큰은 로그에 남기지 않는다.** 실패 로그에 싣는 것은 상태 코드와 경로뿐이다.
 * 3. **실패를 삼키지 않는다.** 닿지 못했으면 재시도 가능한 단계 실패로, 거부당했으면 비재시도
 *    실패로 올린다. "못 옮겼다"를 조용한 성공으로 만들면 문서가 빠진 잡이 COMPLETED로 끝난다.
 */
@Component
@Slf4j
public class WikiImportClient implements WikiImportApi {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String ACTOR_HEADER = "X-Actor-Id";

    private final WikiImportProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public WikiImportClient(WikiImportProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                // 위키가 리다이렉트로 다른 곳을 가리켜도 따라가지 않는다 — 내부 토큰이 붙은 요청이다.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public PageWritten createPage(long actorId, NewPage page) {
        ObjectNode body = json.createObjectNode();
        body.put("spaceId", page.spaceId());
        putNullable(body, "parentId", page.parentId());
        body.put("type", page.type().json());
        body.put("title", page.title());
        body.put("content", page.content());
        body.put("createdAt", page.createdAt().toString());
        body.put("updatedAt", page.updatedAt().toString());
        putNullable(body, "authorId", page.authorId());
        putNullable(body, "importedAuthorName", page.importedAuthorName());
        putNullable(body, "sourceUrl", page.sourceUrl());
        putNullable(body, "sortOrder", page.sortOrder());
        body.set("labels", stringArray(page.labels()));
        if (!page.revisions().isEmpty()) {
            ArrayNode revisions = body.putArray("revisions");
            for (NewRevision revision : page.revisions()) {
                ObjectNode node = revisions.addObject();
                node.put("version", revision.version());
                putNullable(node, "title", revision.title());
                node.put("content", revision.content());
                putNullable(node, "editorId", revision.editorId());
                putNullable(node, "editorName", revision.editorName());
                putNullable(node, "savedAt", revision.savedAt() == null ? null
                        : revision.savedAt().toString());
                putNullable(node, "changeNote", revision.changeNote());
            }
        }
        return pageWritten(send(jsonRequest("/pages", actorId, "POST", body)));
    }

    @Override
    public PageWritten updatePage(long actorId, long pageId, PageUpdate update) {
        ObjectNode body = json.createObjectNode();
        body.put("title", update.title());
        body.put("content", update.content());
        body.put("updatedAt", update.updatedAt().toString());
        putNullable(body, "editorId", update.editorId());
        putNullable(body, "editorName", update.editorName());
        putNullable(body, "changeNote", update.changeNote());
        putNullable(body, "sourceUrl", update.sourceUrl());
        body.set("labels", stringArray(update.labels()));
        return pageWritten(send(jsonRequest("/pages/" + pageId, actorId, "PUT", body)));
    }

    /** 쓰기 응답에서 위키가 보고한 손실까지 꺼낸다 — 버리면 보고서에서 사라진다. */
    private PageWritten pageWritten(HttpResponse<String> response) {
        JsonNode node = parse(response.body());
        List<WriteIssue> issues = new ArrayList<>();
        for (JsonNode issue : node.path("issues")) {
            String code = issue.path("code").asText("");
            if (!code.isBlank()) {
                issues.add(new WriteIssue(issue.path("severity").asText("WARNING"), code));
            }
        }
        return new PageWritten(node.path("pageId").asLong(), node.path("version").asInt(1), issues);
    }

    @Override
    public void replaceContent(long actorId, long pageId, String content) {
        ObjectNode body = json.createObjectNode();
        body.put("content", content);
        body.put("bumpVersion", false);
        send(jsonRequest("/pages/" + pageId + "/content", actorId, "PUT", body));
    }

    @Override
    public void rewriteContent(long actorId, long pageId, String content, String changeNote) {
        ObjectNode body = json.createObjectNode();
        body.put("content", content);
        body.put("bumpVersion", true);
        body.put("changeNote", changeNote);
        send(jsonRequest("/pages/" + pageId + "/content", actorId, "PUT", body));
    }

    @Override
    public void reorder(long actorId, long pageId, long sortOrder) {
        ObjectNode body = json.createObjectNode();
        body.put("sortOrder", sortOrder);
        send(jsonRequest("/pages/" + pageId + "/order", actorId, "PUT", body));
    }

    /**
     * 파일을 multipart로 **스트리밍**해 올린다. 바이트를 다시 메모리에 담지 않는 이유는 상한이
     * 100MB이기 때문이다 — 한 번에 읽으면 워커가 파일 하나에 그만큼을 붙든다.
     *
     * 길이를 미리 알리지 않으므로(chunked) 위키 쪽 multipart 상한은 첨부 설정과 같아야 한다.
     */
    @Override
    public UploadedAttachment uploadAttachment(long actorId, long pageId, Path file, String filename,
                                               String contentType, String checksum,
                                               Integer sourceVersion) {
        String boundary = "----migration" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder fields = new StringBuilder();
        appendField(fields, boundary, "filename", filename);
        appendField(fields, boundary, "contentType", contentType);
        appendField(fields, boundary, "checksum", checksum);
        if (sourceVersion != null) {
            appendField(fields, boundary, "sourceVersion", String.valueOf(sourceVersion));
        }
        // 파일명은 헤더에도 넣되 ASCII로 눕힌다 — 권위 있는 값은 위의 filename 필드다(한글 파일명).
        String header = fields
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + asciiName(filename) + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        byte[] preamble = header.getBytes(StandardCharsets.UTF_8);
        byte[] epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpRequest request = baseRequest("/pages/" + pageId + "/attachments", actorId)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> streamOf(preamble, file, epilogue)))
                .build();
        JsonNode response = parse(send(request).body());
        return new UploadedAttachment(response.path("attachmentId").asLong(),
                response.path("inlineUrl").asText(null), response.path("downloadUrl").asText(null),
                outcomeOf(response.path("outcome").asText("")));
    }

    @Override
    public long createComment(long actorId, long pageId, NewComment comment) {
        ObjectNode body = json.createObjectNode();
        putNullable(body, "parentCommentId", comment.parentCommentId());
        putNullable(body, "authorId", comment.authorId());
        putNullable(body, "authorName", comment.authorName());
        body.put("body", comment.body());
        body.put("createdAt", (comment.createdAt() == null ? Instant.now() : comment.createdAt()).toString());
        JsonNode response = parse(
                send(jsonRequest("/pages/" + pageId + "/comments", actorId, "POST", body)).body());
        return response.path("commentId").asLong();
    }

    @Override
    public boolean commentExists(long actorId, long commentId) {
        HttpResponse<String> response = call(baseRequest("/comments/" + commentId, actorId)
                .GET().build());
        if (response.statusCode() == 404) {
            return false;
        }
        checkStatus(response);
        return true;
    }

    @Override
    public void replaceRestrictions(long actorId, long pageId, List<RestrictionPrincipal> view,
                                    List<RestrictionPrincipal> edit) {
        ObjectNode body = json.createObjectNode();
        body.set("view", principalArray(view));
        body.set("edit", principalArray(edit));
        send(jsonRequest("/pages/" + pageId + "/restrictions", actorId, "PUT", body));
    }

    @Override
    public Optional<ImportedPageView> getPage(long actorId, long pageId) {
        HttpResponse<String> response = call(baseRequest("/pages/" + pageId, actorId).GET().build());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        checkStatus(response);
        JsonNode node = parse(response.body());
        List<String> labels = new ArrayList<>();
        for (JsonNode label : node.path("labels")) {
            labels.add(label.asText(""));
        }
        List<AttachmentView> attachments = new ArrayList<>();
        for (JsonNode attachment : node.path("attachments")) {
            attachments.add(new AttachmentView(attachment.path("id").asLong(),
                    attachment.path("filename").asText(""), attachment.path("checksum").asText(null)));
        }
        JsonNode parent = node.get("parentId");
        return Optional.of(new ImportedPageView(node.path("pageId").asLong(pageId),
                node.path("spaceId").asLong(), parent == null || parent.isNull() ? null : parent.asLong(),
                node.path("title").asText(""), typeOf(node.path("type").asText("")),
                node.path("contentLength").asInt(0), node.path("version").asInt(1),
                node.path("sortOrder").asLong(0), labels, attachments,
                node.path("commentCount").asLong(0)));
    }

    @Override
    public List<PageRef> findPagesByTitle(long actorId, long spaceId, String title) {
        String query = "?title=" + URLEncoder.encode(title, StandardCharsets.UTF_8);
        HttpResponse<String> response = call(
                baseRequest("/spaces/" + spaceId + "/pages" + query, actorId).GET().build());
        if (response.statusCode() == 404) {
            return List.of();
        }
        checkStatus(response);
        JsonNode node = parse(response.body());
        // 응답은 배열이거나 {pages:[...]} 중 하나다. 위키가 어느 쪽을 주든 같은 목록으로 읽는다.
        JsonNode rows = node.isArray() ? node : node.path("pages");
        List<PageRef> found = new ArrayList<>();
        for (JsonNode row : rows) {
            found.add(new PageRef(row.path("pageId").asLong(row.path("id").asLong()),
                    row.path("spaceId").asLong(spaceId), row.path("title").asText("")));
        }
        return found;
    }

    @Override
    public Optional<SpaceRef> getSpace(long actorId, long spaceId) {
        HttpResponse<String> response = call(baseRequest("/spaces/" + spaceId, actorId).GET().build());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        checkStatus(response);
        JsonNode node = parse(response.body());
        return Optional.of(new SpaceRef(node.path("spaceId").asLong(spaceId),
                node.path("key").asText(""), node.path("name").asText("")));
    }

    // -- 전송 -------------------------------------------------------

    private HttpRequest.Builder baseRequest(String path, long actorId) {
        URI uri = properties.resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header(ACTOR_HEADER, String.valueOf(actorId))
                .header("Accept", "application/json");
        if (!properties.internalToken().isBlank()) {
            builder.header(INTERNAL_TOKEN_HEADER, properties.internalToken());
        }
        return builder;
    }

    private HttpRequest jsonRequest(String path, long actorId, String method, ObjectNode body) {
        return baseRequest(path, actorId)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    /** 보내고 상태를 검사한다. 본문이 필요 없는 호출은 결과를 버린다. */
    private HttpResponse<String> send(HttpRequest request) {
        HttpResponse<String> response = call(request);
        checkStatus(response);
        return response;
    }

    private HttpResponse<String> call(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            log.error("위키 import API에 닿지 못했다: {} {}", request.method(), request.uri().getPath(), exception);
            throw MigrationStageException.retryable(WikiImportCodes.UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw MigrationStageException.retryable(WikiImportCodes.UNAVAILABLE);
        }
    }

    /**
     * 상태 코드를 단계 실패로 옮긴다. 5xx·429는 다시 시도할 수 있는 실패고, 나머지 4xx는 같은
     * 본문을 다시 보내도 같은 답이 온다. 위키의 오류 계약({@code {"error"}})을 로그에 남긴다 —
     * 토큰은 요청 헤더에만 있으므로 여기 실리지 않는다.
     */
    private void checkStatus(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String message = errorMessage(response.body());
        String path = response.uri().getPath();
        if (status >= 500 || status == 429) {
            log.error("위키 import API 실패(재시도): status={} path={} error={}", status, path, message);
            throw MigrationStageException.retryable(WikiImportCodes.UNAVAILABLE);
        }
        if (status == 404) {
            log.warn("위키 import API 대상 없음: path={} error={}", path, message);
            throw MigrationStageException.permanent(WikiImportCodes.NOT_FOUND);
        }
        log.error("위키 import API 거부: status={} path={} error={}", status, path, message);
        throw MigrationStageException.permanent(WikiImportCodes.REJECTED);
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException exception) {
            log.error("위키 import API 응답을 이해할 수 없다", exception);
            throw MigrationStageException.permanent(WikiImportCodes.REJECTED);
        }
    }

    private String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return json.readTree(body).path("error").asText("");
        } catch (IOException exception) {
            return "";
        }
    }

    // -- 본문 조립 ---------------------------------------------------

    private static InputStream streamOf(byte[] preamble, Path file, byte[] epilogue) {
        try {
            return new SequenceInputStream(new SequenceInputStream(
                    new ByteArrayInputStream(preamble), Files.newInputStream(file)),
                    new ByteArrayInputStream(epilogue));
        } catch (IOException exception) {
            // 스테이징 파일이 사라졌다 — 다음 시도가 원본에서 다시 받으면 된다.
            throw MigrationStageException.retryable(WikiImportCodes.UNAVAILABLE);
        }
    }

    private static void appendField(StringBuilder out, String boundary, String name, String value) {
        out.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n")
                .append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
                .append(value).append("\r\n");
    }

    /** 헤더에 넣을 파일명 — 비ASCII는 `_`로 눕힌다(진짜 이름은 filename 필드로 간다). */
    private static String asciiName(String filename) {
        StringBuilder out = new StringBuilder(filename.length());
        for (char character : filename.toCharArray()) {
            out.append(character < 128 && character != '"' && character != '\\' && character >= 0x20
                    ? character : '_');
        }
        return out.toString();
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = json.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private ArrayNode principalArray(List<RestrictionPrincipal> principals) {
        ArrayNode array = json.createArrayNode();
        for (RestrictionPrincipal principal : principals) {
            ObjectNode node = array.addObject();
            node.put("type", principal.type());
            node.put("id", principal.id());
        }
        return array;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.longValue());
        }
    }

    private static void putNullable(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.intValue());
        }
    }

    private static ImportedPageType typeOf(String raw) {
        return "BLOG".equalsIgnoreCase(raw) ? ImportedPageType.BLOG : ImportedPageType.PAGE;
    }

    private static AttachmentOutcome outcomeOf(String raw) {
        try {
            return AttachmentOutcome.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            // 위키가 판정을 알려주지 않으면 "새로 만들었다"로 본다 — 이 값은 보고용일 뿐이다.
            return AttachmentOutcome.CREATED;
        }
    }
}
