package com.platform.migrationservice.migration.confluence.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.MigrationIssueReset;
import com.platform.migrationservice.migration.MigrationPayloadStore;
import com.platform.migrationservice.migration.confluence.ConfluenceMediaReference;
import com.platform.migrationservice.migration.confluence.ConfluenceNormalizationException;
import com.platform.migrationservice.migration.confluence.ConfluenceNormalizationRequest;
import com.platform.migrationservice.migration.confluence.ConfluenceNormalizationResult;
import com.platform.migrationservice.migration.confluence.ConfluenceStorageNormalizer;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcClient;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcCodes;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcCredentials;
import com.platform.migrationservice.migration.confluence.dc.ConfluenceDcProperties;
import com.platform.migrationservice.migration.confluence.media.MigrationMediaManifest;
import com.platform.migrationservice.migration.confluence.media.MigrationMediaStaging;
import com.platform.migrationservice.migration.ir.DocumentIrValidationException;
import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationSource;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.normalization.DocumentIrAssetRole;
import com.platform.migrationservice.migration.normalization.ResolvedMigrationAsset;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import com.platform.migrationservice.migration.repository.MigrationSourceRepository;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.migration.worker.MigrationStageHandler;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.migration.worker.MigrationStageOutcome;
import com.platform.migrationservice.migration.worker.MigrationStageWork;
import com.platform.migrationservice.wiki.WikiImportApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 첨부 본체를 원본에서 받아 **스테이징**하고, 그 사실을 IR에 반영한다(M2 §4.1).
 *
 * 왜 받아 두기만 하는가: 이 단계는 RESOLVE보다 먼저 돌아 대상 페이지가 아직 없다. 첨부 레코드는
 * 페이지에 매달리므로 만들 수 없고, 그렇다고 RESOLVE에서 받으면 재시도마다 원본을 통째로 다시
 * 긁는다. 그래서 바이트는 여기서 한 번만 받아 저장소에 두고 좌표를 MEDIA_MANIFEST에 남긴다 —
 * RESOLVE는 그 좌표를 첨부 레코드로 옮겨 적기만 한다(재업로드 없음).
 *
 * IR을 다시 만드는 이유: 정규화기는 "해결된 자산 목록"을 받아야 이미지를 image 노드로 편다.
 * 단계 순서가 NORMALIZE → MEDIA_COPY라 첫 정규화 때는 그 목록이 비어 있어 모든 이미지가 "옮기지
 * 못한 원본 요소"로 눕는다. 파일을 받아 둔 지금이 자산 목록이 완성되는 시점이므로, 같은 스냅샷을
 * 자산과 함께 한 번 더 정규화해 IR을 갈아끼운다. 첫 패스가 남긴 정규화 손실 기록은 틀린 말이 되므로
 * 함께 지운다.
 *
 * dry-run은 한 바이트도 받지 않는다(M-02: 쓰기 0건). 대신 무엇을 얼마나 옮길지 INFO로 보고한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConfluenceDcMediaCopyHandler implements MigrationStageHandler {

    private final ConfluenceDcClient client;
    private final ConfluenceDcProperties properties;
    private final MigrationSourceRepository sources;
    private final MigrationObjectMappingRepository mappings;
    private final WikiImportApi wiki;
    private final MigrationPayloadStore payloads;
    private final MigrationMediaStaging staging;
    private final ConfluenceStorageNormalizer normalizer;
    private final MigrationIssueReset issueReset;
    private final ObjectMapper objectMapper;

    @Override
    public MigrationProvider provider() {
        return MigrationProvider.CONFLUENCE_DC;
    }

    @Override
    public MigrationStage stage() {
        return MigrationStage.MEDIA_COPY;
    }

    @Override
    public MigrationStageOutcome handle(MigrationStageWork work) {
        MigrationPayloadStore.StoredPayload snapshotPayload =
                payloads.require(work.itemId(), MigrationPayloadKind.SNAPSHOT);
        JsonNode snapshot = parse(snapshotPayload.body(), ConfluenceDcIssues.SNAPSHOT_INVALID);
        List<SourceAttachment> files = sourceAttachments(snapshot.path("content"));
        if (files.isEmpty()) {
            return MigrationStageOutcome.ok();
        }
        if (work.dryRun()) {
            return MigrationStageOutcome.ok(plan(files));
        }
        if (alreadyImported(work)) {
            // 이 원본은 같은 상태로 이미 옮겨져 있다. RESOLVE도 손대지 않을 문서이므로 여기서 파일을
            // 다시 받으면 남의 서버를 헛되이 긁고 저장소에 고아 객체만 쌓인다.
            return MigrationStageOutcome.ok();
        }
        return copy(work, snapshotPayload, snapshot, files);
    }

    /** RESOLVE의 멱등 판정과 같은 조건 — 원본도 그대로고 대상 문서도 살아 있는가. */
    private boolean alreadyImported(MigrationStageWork work) {
        return mappings.findBySourceKey(MigrationObjectMapping.sourceKeyFor(work.provider(),
                        work.sourceInstanceId(), work.externalObjectId()))
                .filter(mapping -> work.sourceChecksum().equals(mapping.getSourceChecksum()))
                .map(MigrationObjectMapping::getTargetPageId)
                .filter(java.util.Objects::nonNull)
                .map(pageId -> wiki.getPage(work.requestedBy(), pageId).isPresent())
                .orElse(false);
    }

    /** dry-run 보고 — 상한을 넘는 것은 지금 알려야 실제 이관에서 놀라지 않는다. */
    private List<MigrationStageIssue> plan(List<SourceAttachment> files) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        for (SourceAttachment file : files) {
            if (file.size() > properties.maxAttachmentBytes()) {
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_TOO_LARGE,
                        "attachment:" + file.filename()));
                continue;
            }
            issues.add(MigrationStageIssue.info(ConfluenceDcIssues.ATTACHMENT_PLANNED,
                    "attachment:" + file.filename()));
        }
        return issues;
    }

    private MigrationStageOutcome copy(MigrationStageWork work,
                                       MigrationPayloadStore.StoredPayload snapshotPayload,
                                       JsonNode snapshot, List<SourceAttachment> files) {
        MigrationSource source = sources.findById(work.jobId())
                .orElseThrow(() -> MigrationStageException.permanent(ConfluenceDcIssues.SOURCE_MISSING));
        ConfluenceDcCredentials credentials = new ConfluenceDcCredentials(
                source.getBaseUrl(), source.getSpaceKey(), source.getAuthToken());
        String pageId = snapshot.path("content").path("id").asText("");

        Map<String, MigrationMediaManifest.Entry> staged = existingManifest(work.itemId());
        List<MigrationMediaManifest.Entry> entries = new ArrayList<>();
        List<MigrationStageIssue> issues = new ArrayList<>();

        for (SourceAttachment file : files) {
            if (file.size() > properties.maxAttachmentBytes()) {
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_TOO_LARGE,
                        "attachment:" + file.filename()));
                continue;
            }
            MigrationMediaManifest.Entry reused = staged.get(file.stagingKey());
            if (reused != null) {
                // 앞선 시도가 이미 받아 뒀다. 같은 바이트를 다시 긁는 것은 원본에 대한 예의가 아니다.
                entries.add(reused);
                continue;
            }
            byte[] bytes;
            try {
                bytes = client.downloadAttachment(credentials, pageId, file.filename(), file.version());
            } catch (MigrationStageException exception) {
                if (ConfluenceDcCodes.UNAVAILABLE.equals(exception.getCode())) {
                    // 원본이 잠시 못 받는 상태다. 삼키면 첨부 빠진 문서가 "성공"으로 남는다.
                    throw exception;
                }
                if (ConfluenceDcCodes.ATTACHMENT_TOO_LARGE.equals(exception.getCode())) {
                    issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_TOO_LARGE,
                            "attachment:" + file.filename()));
                    continue;
                }
                // 404·권한 부족 등 — 파일 하나 때문에 문서 전체를 데드레터로 보내지 않는다.
                log.warn("첨부를 받지 못했다: job={} item={} code={}",
                        work.jobId(), work.itemId(), exception.getCode());
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_NOT_COPIED,
                        "attachment:" + file.filename()));
                continue;
            }
            MigrationMediaStaging.StagedFile stored = staging.stage(bytes);
            entries.add(new MigrationMediaManifest.Entry(file.filename(), stored.contentType(),
                    stored.sizeBytes(), stored.checksum(), file.version()));
        }

        writeManifest(work.itemId(), entries);
        issues.addAll(renormalize(work, snapshotPayload, snapshot, entries));
        return MigrationStageOutcome.ok(issues);
    }

    /**
     * 자산을 해결한 상태로 스냅샷을 한 번 더 정규화해 IR을 갈아끼운다.
     *
     * 실패하면 IR을 건드리지 않고 경고만 남긴다 — 첫 패스의 IR은 여전히 유효하고(이미지가 안내
     * 문구로 눕는 것뿐이다), 여기서 항목을 데드레터로 보내면 본문까지 통째로 잃는다.
     */
    private List<MigrationStageIssue> renormalize(MigrationStageWork work,
                                                  MigrationPayloadStore.StoredPayload snapshotPayload,
                                                  JsonNode snapshot,
                                                  List<MigrationMediaManifest.Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, ResolvedMigrationAsset> resolved = new HashMap<>();
        for (MigrationMediaManifest.Entry entry : entries) {
            String reference = ConfluenceMediaReference.attachment(entry.filename());
            resolved.put(reference, new ResolvedMigrationAsset(mediaIdOf(entry.filename()), reference,
                    entry.filename(), entry.contentType(), entry.size(), entry.checksum(),
                    entry.contentType().startsWith("image/")
                            ? DocumentIrAssetRole.INLINE
                            : DocumentIrAssetRole.ATTACHMENT));
        }
        ConfluenceNormalizationResult result;
        try {
            result = normalizer.normalize(new ConfluenceNormalizationRequest(snapshot,
                    work.sourceInstanceId(), snapshotPayload.createdAt(), work.sourceChecksum(),
                    work.payloadRef(), resolved));
        } catch (DocumentIrValidationException | ConfluenceNormalizationException exception) {
            log.warn("첨부 반영 후 재정규화 실패 — 첫 패스 IR을 유지한다: item={}", work.itemId(), exception);
            return List.of(MigrationStageIssue.warning(ConfluenceDcIssues.ATTACHMENT_REF_UNRESOLVED,
                    "page:" + work.externalObjectId()));
        }
        payloads.write(work.itemId(), MigrationPayloadKind.IR, result.documentIr().toString());
        issueReset.clearNormalizationIssues(work.itemId());
        return result.issues().stream()
                .map(issue -> new MigrationStageIssue(issue.severity(), issue.code(), issue.sourcePath()))
                .toList();
    }

    private Map<String, MigrationMediaManifest.Entry> existingManifest(long itemId) {
        return payloads.read(itemId, MigrationPayloadKind.MEDIA_MANIFEST)
                .map(payload -> {
                    Map<String, MigrationMediaManifest.Entry> byKey = new LinkedHashMap<>();
                    for (MigrationMediaManifest.Entry entry : readManifest(payload.body()).files()) {
                        // 목록에 있어도 받아 둔 파일이 사라졌으면(볼륨 초기화) 없는 것으로 본다 —
                        // 다시 받는 편이 첨부 없는 문서를 성공으로 남기는 것보다 낫다.
                        if (staging.locate(entry.checksum()).isPresent()) {
                            byKey.put(entry.stagingKey(), entry);
                        }
                    }
                    return byKey;
                })
                .orElseGet(LinkedHashMap::new);
    }

    private MigrationMediaManifest readManifest(String body) {
        try {
            return objectMapper.readValue(body, MigrationMediaManifest.class);
        } catch (JsonProcessingException exception) {
            // 형식을 못 읽으면 없는 것으로 본다 — 다시 받는 편이 조용히 첨부를 빠뜨리는 것보다 낫다.
            return MigrationMediaManifest.empty();
        }
    }

    private void writeManifest(long itemId, List<MigrationMediaManifest.Entry> entries) {
        try {
            payloads.write(itemId, MigrationPayloadKind.MEDIA_MANIFEST,
                    objectMapper.writeValueAsString(MigrationMediaManifest.of(entries)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("첨부 목록을 기록하지 못했습니다", exception);
        }
    }

    /** 스냅샷의 첨부 목록. 파일명이 없는 행은 내려받을 주소를 만들 수 없어 건너뛴다. */
    private List<SourceAttachment> sourceAttachments(JsonNode content) {
        List<SourceAttachment> files = new ArrayList<>();
        for (JsonNode file : content.path("children").path("attachment").path("results")) {
            String filename = file.path("title").asText("");
            if (filename.isBlank()) {
                continue;
            }
            files.add(new SourceAttachment(filename, file.path("fileSize").asLong(0),
                    file.path("version").asInt(1)));
        }
        return files;
    }

    /** IR의 mediaId — 파일명에서 결정적으로 만든다. 재실행해도 같은 값이라 IR이 흔들리지 않는다. */
    private static String mediaIdOf(String filename) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "media-" + HexFormat.of()
                    .formatHex(digest.digest(filename.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }

    private JsonNode parse(String body, String code) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw MigrationStageException.permanent(code);
        }
    }

    private record SourceAttachment(String filename, long size, int version) {
        String stagingKey() {
            return filename + "@" + version;
        }
    }
}
