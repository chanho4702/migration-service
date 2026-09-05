package com.platform.migrationservice.migration.confluence.dc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Confluence Server/Data Center REST 클라이언트. 새 의존성 없이 JDK HttpClient만 쓴다.
 *
 * 두 가지를 구조로 막는다.
 * - **SSRF**: 요청 URI는 검증된 baseUrl + 이 클래스가 가진 고정 경로로만 만든다. 응답의 `_links.next`를
 *   따라가지 않고 start 오프셋을 우리가 센다. 리다이렉트는 따라가지 않는다(따라가면 baseUrl 검증이 무의미해진다).
 * - **토큰 유출**: 자격증명은 어떤 로그·예외 메시지에도 넣지 않는다. 실패는 코드 문자열로만 말한다.
 */
@Component
@Slf4j
public class ConfluenceDcClient {

    private final ObjectMapper objectMapper;
    private final ConfluenceDcProperties properties;
    private final HttpClient http;

    public ConfluenceDcClient(ObjectMapper objectMapper, ConfluenceDcProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 연결 확인 — 스페이스 이름·홈페이지 id와, 사이트가 알려주면 페이지 총계까지. */
    public ConfluenceSpaceProbe probe(ConfluenceDcCredentials credentials) {
        JsonNode space = get(credentials, "/rest/api/space/" + encode(credentials.spaceKey())
                + "?expand=homepage");
        String name = space.path("name").asText(null);
        String homepageId = space.path("homepage").path("id").asText(null);

        Integer pageCount = null;
        try {
            JsonNode probe = get(credentials, "/rest/api/content?spaceKey=" + encode(credentials.spaceKey())
                    + "&type=page&status=current&limit=1");
            // DC 버전에 따라 totalSize가 없다. 없으면 null로 두고 화면이 "발견 후 확인"을 안내한다.
            JsonNode total = probe.hasNonNull("totalSize") ? probe.get("totalSize") : probe.get("size");
            if (total != null && total.isIntegralNumber()) {
                pageCount = total.intValue();
            }
        } catch (MigrationStageException exception) {
            // 총계는 부가 정보다. 스페이스가 보이는데 개수만 못 세는 사이트 때문에 연결 확인을 실패시키지 않는다.
            log.warn("원본 페이지 수 조회 실패 — 총계 없이 진행한다: code={}", exception.getCode());
        }
        return new ConfluenceSpaceProbe(name, homepageId, pageCount);
    }

    /**
     * 페이지 한 묶음. `_links.next`가 아니라 우리가 센 start를 넘긴다 — 다음 주소를 원본이
     * 정하게 두면 검증한 baseUrl 밖으로 끌려갈 수 있다.
     */
    public ConfluenceContentPage listPages(ConfluenceDcCredentials credentials, int start) {
        return listContent(credentials, "page", start);
    }

    /**
     * 블로그 글 한 묶음(M3 §5.1). 페이지와 같은 목록 API를 type만 바꿔 부른다 — 블로그 글은
     * 트리 밖에 살아 ancestors가 늘 비어 있고, 그래서 형제 순서도 물어볼 곳이 없다.
     */
    public ConfluenceContentPage listBlogPosts(ConfluenceDcCredentials credentials, int start) {
        return listContent(credentials, "blogpost", start);
    }

    private ConfluenceContentPage listContent(ConfluenceDcCredentials credentials, String type, int start) {
        int limit = properties.pageSize();
        JsonNode response = get(credentials, "/rest/api/content"
                + "?spaceKey=" + encode(credentials.spaceKey())
                + "&type=" + type + "&status=current&expand=version,ancestors"
                + "&start=" + start + "&limit=" + limit);
        JsonNode results = response.path("results");
        if (!results.isArray()) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
        }
        List<ConfluenceContentSummary> page = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
            }
            List<String> ancestors = new ArrayList<>();
            for (JsonNode ancestor : node.path("ancestors")) {
                String ancestorId = ancestor.path("id").asText(null);
                if (ancestorId != null && !ancestorId.isBlank()) {
                    ancestors.add(ancestorId);
                }
            }
            page.add(new ConfluenceContentSummary(id, node.path("title").asText(""),
                    node.path("version").path("number").asInt(1), List.copyOf(ancestors)));
        }
        // 마지막 묶음 판정: 받은 수가 limit보다 적으면 끝이다. size 필드를 믿지 않는 이유는
        // 버전에 따라 없거나 전체 총계를 담는 사이트가 있기 때문이다.
        return new ConfluenceContentPage(List.copyOf(page), page.size() >= limit);
    }

    /**
     * 본문·이력·라벨·첨부 목록·제한까지 한 번에 — 페이지당 왕복을 늘리지 않는다.
     *
     * restrictions는 DC 7.x/8.x가 같은 이름으로 준다. 없는 사이트에서는 그냥 빠진 채로 오고,
     * 그때는 "제한 없음"으로 읽힌다 — 제한을 못 읽었다고 페이지를 못 옮기게 하지는 않는다.
     */
    public JsonNode content(ConfluenceDcCredentials credentials, String contentId) {
        return get(credentials, "/rest/api/content/" + encode(contentId)
                + "?expand=body.storage,version,ancestors,history,metadata.labels,children.attachment"
                + ",restrictions.read.restrictions.user,restrictions.read.restrictions.group"
                + ",restrictions.update.restrictions.user,restrictions.update.restrictions.group");
    }

    /**
     * 한 부모의 자식 페이지를 **원본이 정한 순서 그대로** 받는다. 목록 API(`/content?spaceKey=`)는
     * 형제 순서를 알려주지 않아, 원본 정렬을 지키려면 부모마다 이 호출이 한 번씩 더 필요하다.
     */
    public ConfluenceContentPage listChildPages(ConfluenceDcCredentials credentials, String parentId,
                                                int start) {
        int limit = properties.childPageSize();
        return toPage(get(credentials, "/rest/api/content/" + encode(parentId)
                + "/child/page?expand=version&start=" + start + "&limit=" + limit), limit);
    }

    /**
     * 한 문서의 댓글 한 묶음(M3 §5.2). `children.comment`를 content 응답에 얹지 않고 전용
     * 엔드포인트를 부르는 이유는 **페이지네이션** 때문이다 — expand로 딸려 오는 목록은 사이트가
     * 정한 수에서 조용히 잘리고, 그 사실이 응답 어디에도 남지 않는다.
     *
     * inlineProperties가 있으면 원본에서 본문 구간에 붙은 댓글이다. 우리는 그 앵커를 다시 찾을 수
     * 없어(원본 렌더 기준이다) 페이지 댓글로 강등하되, 인용문은 여기서 받아 본문 앞에 남긴다.
     */
    public JsonNode listComments(ConfluenceDcCredentials credentials, String contentId, int start) {
        int limit = properties.commentPageSize();
        return get(credentials, "/rest/api/content/" + encode(contentId)
                + "/child/comment?expand=body.storage,history,ancestors"
                + ",extensions.location,extensions.inlineProperties"
                + "&start=" + start + "&limit=" + limit);
    }

    /**
     * 지난 버전 하나의 본문(M3 §5.3).
     *
     * 버전 목록 API를 따로 부르지 않는다 — DC의 `/history`는 최신과 직전 한 건만 알려주고
     * 전체 목록을 주지 않는다. 현재 버전 번호에서 아래로 세는 것이 실제로 가능한 유일한 방법이다.
     */
    public JsonNode historicalContent(ConfluenceDcCredentials credentials, String contentId, int version) {
        return get(credentials, "/rest/api/content/" + encode(contentId)
                + "?status=historical&version=" + version
                + "&expand=body.storage,version,history");
    }

    /** 스페이스 최상단 페이지 — 루트의 형제 순서다. */
    public ConfluenceContentPage listRootPages(ConfluenceDcCredentials credentials, int start) {
        int limit = properties.childPageSize();
        return toPage(get(credentials, "/rest/api/space/" + encode(credentials.spaceKey())
                + "/content/page?depth=root&expand=version&start=" + start + "&limit=" + limit), limit);
    }

    /**
     * 첨부 본체. 주소는 원본 응답의 `_links.download`가 아니라 고정 패턴으로 조합한다 —
     * 남의 서버가 알려준 주소를 그대로 따라가면 baseUrl 검증이 무의미해진다(SSRF).
     *
     * 바이트를 통째로 메모리에 담는다. 상한(maxAttachmentBytes)을 호출부가 미리 걸러 주는 것을
     * 전제로 하고, 여기서도 Content-Length가 상한을 넘으면 받기 전에 끊는다.
     */
    public byte[] downloadAttachment(ConfluenceDcCredentials credentials, String pageId,
                                     String filename, int version) {
        String path = "/download/attachments/" + encode(pageId) + "/" + encode(filename)
                + "?version=" + version + "&api=v2";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(credentials.baseUrl() + path))
                .timeout(properties.readTimeout())
                .header("Authorization", "Bearer " + credentials.token())
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw MigrationStageException.retryable(ConfluenceDcCodes.UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw MigrationStageException.retryable(ConfluenceDcCodes.UNAVAILABLE);
        }
        raiseForStatus(response.statusCode());
        byte[] body = response.body();
        if (body.length > properties.maxAttachmentBytes()) {
            // 목록이 알려준 크기가 거짓이었다. 받아 놓고 버리지만, 저장소에는 넣지 않는다.
            throw MigrationStageException.permanent(ConfluenceDcCodes.ATTACHMENT_TOO_LARGE);
        }
        return body;
    }

    private ConfluenceContentPage toPage(JsonNode response, int limit) {
        JsonNode results = response.path("results");
        if (!results.isArray()) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
        }
        List<ConfluenceContentSummary> page = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
            }
            page.add(new ConfluenceContentSummary(id, node.path("title").asText(""),
                    node.path("version").path("number").asInt(1), List.of()));
        }
        return new ConfluenceContentPage(List.copyOf(page), page.size() >= limit);
    }

    private JsonNode get(ConfluenceDcCredentials credentials, String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(credentials.baseUrl() + path))
                .timeout(properties.readTimeout())
                .header("Authorization", "Bearer " + credentials.token())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            // 예외 메시지에 URI가 붙을 수 있어 cause를 붙이지 않는다 — 토큰은 헤더라 안 새지만
            // 원본 주소도 굳이 손실 보고서에 남길 값이 아니다.
            throw MigrationStageException.retryable(ConfluenceDcCodes.UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw MigrationStageException.retryable(ConfluenceDcCodes.UNAVAILABLE);
        }
        raiseForStatus(response.statusCode());
        try {
            JsonNode body = objectMapper.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
            }
            return body;
        } catch (IOException exception) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
        }
    }

    /** 상태 코드 → 재시도 정책. JSON과 첨부 다운로드가 같은 규칙을 쓴다. */
    private static void raiseForStatus(int status) {
        if (status >= 300 && status < 400) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.REDIRECT_REFUSED);
        }
        if (status == 401 || status == 403) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.AUTH);
        }
        if (status == 404) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.NOT_FOUND);
        }
        if (status == 429 || status >= 500) {
            throw MigrationStageException.retryable(ConfluenceDcCodes.UNAVAILABLE);
        }
        if (status >= 400) {
            throw MigrationStageException.permanent(ConfluenceDcCodes.INVALID_RESPONSE);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
