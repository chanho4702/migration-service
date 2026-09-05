package com.platform.migrationservice.migration.confluence.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.MigrationPayloadStore;
import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcIssues;
import com.platform.migrationservice.migration.confluence.link.MarkdownLinkTargets;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.wiki.WikiImportApi;
import com.platform.migrationservice.wiki.WikiImportCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 스테이징해 둔 첨부를 대상 문서에 올리고, 본문의 `attachment:{파일명}` 참조를 위키가 돌려준
 * 실제 URL로 바꾼다(M2 §4.1).
 *
 * 순서가 이렇게 될 수밖에 없는 이유: 첨부는 페이지에 매달리므로 페이지가 먼저 있어야 하고,
 * 본문의 참조는 첨부 **id**로 걸리므로 업로드가 먼저 있어야 한다. 그래서 문서를 한 번 쓰고,
 * 첨부를 올리고, 본문만 다시 눌러 준다(새 리비전 없이).
 *
 * 주소를 우리가 만들지 않는다는 점이 이전과 다르다 — 인라인/내려받기 주소는 위키가 응답에
 * 실어 준다. 어떤 형식을 인라인으로 열어도 되는지는 위키의 판단이고, 그 표를 여기에 복제하면
 * 둘이 조용히 갈라진다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrationAttachmentImporter {

    /** 본문에 남아 있는 미해결 참조의 스킴. IR→마크다운 writer가 이 꼴로 쓴다. */
    public static final String ATTACHMENT_SCHEME = "attachment:";

    private final MigrationPayloadStore payloads;
    private final MigrationMediaStaging staging;
    private final WikiImportApi wiki;
    private final ObjectMapper objectMapper;

    /**
     * 이 항목의 첨부를 전부 올린다. 파일 하나가 실패해도 나머지는 계속 간다 — 첨부 한 건 때문에
     * 문서를 통째로 못 옮기게 하지 않는다.
     *
     * 예외는 위키에 닿지 못한 경우다. 그때는 다음 파일도 어차피 실패하고, 삼키면 첨부가 빠진
     * 문서가 "성공"으로 남는다 — 단계 실패로 올려 재시도에 맡긴다.
     */
    public Registered register(long itemId, long pageId, long actorId) {
        MigrationMediaManifest manifest = readManifest(itemId);
        Map<String, Registered.Entry> byFilename = new LinkedHashMap<>();
        List<MigrationStageIssue> issues = new ArrayList<>();
        for (MigrationMediaManifest.Entry entry : manifest.files()) {
            Optional<Path> staged = staging.locate(entry.checksum());
            if (staged.isEmpty()) {
                // MEDIA_COPY와 이 단계 사이에 받아 둔 파일이 사라졌다(볼륨 초기화·수동 삭제).
                // 파일 하나 때문에 문서를 데드레터로 보내지 않고 보고서에 남긴다 — 다시 이관하면
                // MEDIA_COPY가 없어진 것을 알아채고 원본에서 새로 받는다.
                log.warn("스테이징 파일이 없다 — 첨부를 건너뛴다: page={} file={}", pageId, entry.filename());
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_NOT_COPIED,
                        "attachment:" + entry.filename()));
                continue;
            }
            try {
                WikiImportApi.UploadedAttachment uploaded = wiki.uploadAttachment(actorId, pageId,
                        staged.get(), entry.filename(), entry.contentType(), entry.checksum(),
                        entry.sourceVersion());
                byFilename.put(entry.filename(), new Registered.Entry(uploaded.attachmentId(),
                        uploaded.inlineUrl(), uploaded.downloadUrl(), entry.contentType()));
            } catch (MigrationStageException exception) {
                if (WikiImportCodes.UNAVAILABLE.equals(exception.getCode())) {
                    throw exception;
                }
                log.warn("첨부 등록 실패 — 본문 참조는 그대로 둔다: page={} file={}",
                        pageId, entry.filename(), exception);
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_NOT_COPIED,
                        "attachment:" + entry.filename()));
            }
        }
        return new Registered(byFilename, issues);
    }

    /**
     * 본문의 `attachment:{파일명}`을 실제 주소로 바꾼다.
     *
     * 이미지는 인라인 주소로, 그 밖의 파일은 내려받기 주소로 간다 — inline 엔드포인트는 안전한
     * 형식만 열어 주므로 문서 파일을 인라인으로 걸면 열 때 400이 난다.
     */
    public Rewritten rewrite(String markdown, Registered registered) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        String rewritten = MarkdownLinkTargets.rewrite(markdown, target -> {
            if (target == null || !target.startsWith(ATTACHMENT_SCHEME)) {
                return null;
            }
            String filename = target.substring(ATTACHMENT_SCHEME.length());
            Registered.Entry entry = registered.byFilename().get(filename);
            if (entry == null) {
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_REF_UNRESOLVED,
                        "attachment:" + filename));
                return null;
            }
            String url = entry.contentType() != null && entry.contentType().startsWith("image/")
                    ? entry.inlineUrl()
                    : entry.downloadUrl();
            if (url == null || url.isBlank()) {
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_REF_UNRESOLVED,
                        "attachment:" + filename));
                return null;
            }
            return url;
        });
        return new Rewritten(rewritten, issues);
    }

    private MigrationMediaManifest readManifest(long itemId) {
        return payloads.read(itemId, MigrationPayloadKind.MEDIA_MANIFEST)
                .map(payload -> {
                    try {
                        return objectMapper.readValue(payload.body(), MigrationMediaManifest.class);
                    } catch (JsonProcessingException exception) {
                        return MigrationMediaManifest.empty();
                    }
                })
                .orElseGet(MigrationMediaManifest::empty);
    }

    /** 올린 첨부 — 파일명으로 찾는다(본문 참조가 파일명으로 걸려 있기 때문). */
    public record Registered(Map<String, Entry> byFilename, List<MigrationStageIssue> issues) {

        public Registered {
            byFilename = byFilename == null ? Map.of() : Map.copyOf(byFilename);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean isEmpty() {
            return byFilename.isEmpty();
        }

        /** 주소는 위키가 준 값을 그대로 쓴다 — 여기서 만들지 않는다. */
        public record Entry(long attachmentId, String inlineUrl, String downloadUrl, String contentType) {
        }
    }

    public record Rewritten(String markdown, List<MigrationStageIssue> issues) {

        public Rewritten {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
