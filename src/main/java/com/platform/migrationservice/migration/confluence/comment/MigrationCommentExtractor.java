package com.platform.migrationservice.migration.confluence.comment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.MigrationPayloadStore;
import com.platform.migrationservice.migration.confluence.ConfluenceFragmentConverter;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcClient;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcCodes;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcCredentials;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcProperties;
import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcIssues;
import com.platform.migrationservice.migration.ir.DocumentIrMarkdownResult;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.migration.worker.MigrationStageWork;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 원본 문서의 댓글을 받아 우리 마크다운 목록으로 눕힌다(M3 §5.2). EXTRACT 단계에서 돈다.
 *
 * 여기서 결정하는 것이 둘이다.
 * 1. **인라인 댓글 강등** — 원본 앵커는 원본이 렌더한 본문 기준이라 우리 본문에서 같은 구간을
 *    다시 찾을 수 없다. 페이지 댓글로 내리되 인용문을 본문 앞에 남겨 무엇에 달린 말인지는 지킨다.
 *    이 판단은 원본 응답을 보는 지금만 할 수 있으므로 보고도 여기서 한다 — dry-run도 이 손실을
 *    미리 알려야 "실제 이관에서 놀라지 않는다"는 dry-run의 약속이 성립한다.
 * 2. **답글 평탄화** — 우리 댓글의 중첩은 1단이다. 원본의 답글의 답글은 최상위 답글로 편다.
 *
 * 댓글 하나의 변환 실패로 문서를 데드레터로 보내지 않는다 — 그 댓글만 빠지고 경고가 남는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrationCommentExtractor {

    /** 인용문이 길면 댓글보다 인용이 커진다 — 사람이 무엇에 달린 말인지 알아볼 만큼만 남긴다. */
    private static final int MAX_QUOTE_LENGTH = 200;

    private final ConfluenceDcClient client;
    private final ConfluenceDcProperties properties;
    private final ConfluenceFragmentConverter converter;
    private final MigrationPayloadStore payloads;
    private final ObjectMapper objectMapper;

    /**
     * 이 항목의 댓글을 전부 받아 COMMENTS payload에 적는다.
     *
     * @return 그 과정의 손실(강등·평탄화·변환 실패)
     */
    public List<MigrationStageIssue> extract(MigrationStageWork work, ConfluenceDcCredentials credentials,
                                             ConfluenceFragmentConverter.Fragment fragment) {
        List<JsonNode> source = fetchAll(credentials, work.externalObjectId());
        if (source.isEmpty()) {
            // payload를 지우지는 않는다. 원본에서 댓글이 사라진 경우 다음 RESOLVE가 이미 옮긴 댓글을
            // 지우지 않는 것과 같은 방향이다 — 이관은 더하기만 한다.
            payloads.write(work.itemId(), MigrationPayloadKind.COMMENTS,
                    write(MigrationCommentPayload.empty()));
            return List.of();
        }

        Map<String, String> parents = parentIds(source, work.externalObjectId());
        List<MigrationStageIssue> issues = new ArrayList<>();
        List<MigrationCommentPayload.Entry> entries = new ArrayList<>();

        for (JsonNode comment : source) {
            String id = comment.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            boolean inline = isInline(comment);
            String storage = comment.path("body").path("storage").path("value").asText("");
            Optional<DocumentIrMarkdownResult> converted = converter.convert(storage,
                    "comment:" + id, "댓글 " + id, fragment);
            if (converted.isEmpty()) {
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.COMMENT_NOT_MIGRATED,
                        "comment:" + id));
                continue;
            }
            issues.addAll(converted.get().issues());

            String markdown = converted.get().markdown();
            if (inline) {
                markdown = quoteOf(comment) + markdown;
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.INLINE_COMMENT_DEMOTED,
                        "comment:" + id));
            }
            String parentId = flatten(id, parents, issues);
            JsonNode author = comment.path("history").path("createdBy");
            entries.add(new MigrationCommentPayload.Entry(id, parentId,
                    author.path("displayName").asText(""), author.path("email").asText(""),
                    comment.path("history").path("createdDate").asText(""), markdown, inline));
        }

        payloads.write(work.itemId(), MigrationPayloadKind.COMMENTS,
                write(MigrationCommentPayload.of(entries)));
        return issues;
    }

    /**
     * 댓글 목록을 끝까지 받는다. 상한은 페이지 상한(max-pages)을 그대로 쓴다 — 한 문서에 그보다
     * 많은 댓글이 달렸다면 그것 자체가 사람이 봐야 할 일이다.
     */
    private List<JsonNode> fetchAll(ConfluenceDcCredentials credentials, String contentId) {
        List<JsonNode> all = new ArrayList<>();
        int start = 0;
        int limit = properties.commentPageSize();
        while (all.size() < properties.maxPages()) {
            JsonNode response;
            try {
                response = client.listComments(credentials, contentId, start);
            } catch (MigrationStageException exception) {
                if (ConfluenceDcCodes.UNAVAILABLE.equals(exception.getCode())) {
                    // 원본이 잠시 못 받는 상태다. 삼키면 댓글 없는 문서가 "성공"으로 남는다.
                    throw exception;
                }
                // 댓글 API가 없거나 막힌 사이트다 — 본문은 옮길 수 있으므로 여기서 멈추지 않는다.
                log.warn("원본 댓글을 읽지 못했다 — 본문만 옮긴다: content={} code={}",
                        contentId, exception.getCode());
                return List.of();
            }
            JsonNode results = response.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return all;
            }
            results.forEach(all::add);
            if (results.size() < limit) {
                return all;
            }
            start += results.size();
        }
        return all;
    }

    /** 원본이 알려주는 직계 부모. ancestors의 마지막이 문서 자신이면 최상위 댓글이다. */
    private Map<String, String> parentIds(List<JsonNode> source, String contentId) {
        Map<String, String> parents = new LinkedHashMap<>();
        for (JsonNode comment : source) {
            String id = comment.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            JsonNode ancestors = comment.path("ancestors");
            String parent = null;
            if (ancestors.isArray() && !ancestors.isEmpty()) {
                String last = ancestors.get(ancestors.size() - 1).path("id").asText("");
                parent = last.isBlank() || last.equals(contentId) ? null : last;
            }
            parents.put(id, parent);
        }
        return parents;
    }

    /**
     * 우리 계약(중첩 1단)에 맞게 부모를 최상위 댓글까지 끌어올린다.
     *
     * 손상된 원본 데이터의 순환에도 멈추도록 방문 수를 센다 — 깊이가 목록 크기를 넘으면 순환이다.
     */
    private String flatten(String id, Map<String, String> parents, List<MigrationStageIssue> issues) {
        String parent = parents.get(id);
        if (parent == null) {
            return null;
        }
        int guard = parents.size() + 1;
        boolean flattened = false;
        while (guard-- > 0) {
            String grandparent = parents.get(parent);
            if (grandparent == null) {
                break;
            }
            parent = grandparent;
            flattened = true;
        }
        if (flattened) {
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.COMMENT_REPLY_FLATTENED,
                    "comment:" + id));
        }
        return parent;
    }

    /** location이 inline이거나 inlineProperties가 있으면 본문 구간에 붙은 댓글이다. */
    private static boolean isInline(JsonNode comment) {
        JsonNode extensions = comment.path("extensions");
        return "inline".equalsIgnoreCase(extensions.path("location").asText(""))
                || !extensions.path("inlineProperties").isMissingNode();
    }

    /** 강등된 인라인 댓글이 무엇에 달린 말이었는지 — 본문 앞 한 줄로 남긴다. */
    private static String quoteOf(JsonNode comment) {
        String selection = comment.path("extensions").path("inlineProperties")
                .path("originalSelection").asText("").replaceAll("\\s+", " ").trim();
        if (selection.isEmpty()) {
            return "> 원문 구간을 알 수 없습니다\n\n";
        }
        String trimmed = selection.length() <= MAX_QUOTE_LENGTH
                ? selection
                : selection.substring(0, MAX_QUOTE_LENGTH) + "…";
        return "> 원문: \"" + trimmed + "\"\n\n";
    }

    private String write(MigrationCommentPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("원본 댓글 목록을 기록하지 못했습니다", exception);
        }
    }
}
