package com.platform.migrationservice.migration.confluence.history;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 원본의 지난 버전을 최신 N개까지 받아 둔다(M3 §5.3, 기획 P3 기본 10). EXTRACT 단계에서 돈다.
 *
 * 버전 목록 API를 부르지 않고 현재 버전 번호에서 아래로 세는 이유: DC의 `/rest/api/content/{id}/history`는
 * 최신과 **직전 한 건**만 알려주고 전체 목록을 주지 않는다. 번호는 1부터 현재까지 빈틈없이 이어지는
 * 것이 규칙이고, 중간이 비어 있는(관리자가 지운) 버전은 404로 돌아와 그 버전만 건너뛴다.
 *
 * 버전 하나가 실패해도 문서는 옮긴다 — 이력이 한 칸 비는 것이 문서를 통째로 못 옮기는 것보다 낫다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrationHistoryExtractor {

    private final ConfluenceDcClient client;
    private final ConfluenceDcProperties properties;
    private final ConfluenceFragmentConverter converter;
    private final MigrationPayloadStore payloads;
    private final ObjectMapper objectMapper;

    /**
     * @param currentVersion 원본의 현재 버전 번호(스냅샷에서 온다)
     * @return 그 과정의 손실(건너뛴 버전·변환 손실)
     */
    public List<MigrationStageIssue> extract(MigrationStageWork work, ConfluenceDcCredentials credentials,
                                             ConfluenceFragmentConverter.Fragment fragment,
                                             int currentVersion) {
        int keep = properties.historyVersions();
        if (keep <= 0 || currentVersion <= 1) {
            payloads.write(work.itemId(), MigrationPayloadKind.HISTORY,
                    write(MigrationHistoryPayload.empty()));
            return List.of();
        }

        int oldest = Math.max(1, currentVersion - keep);
        List<MigrationStageIssue> issues = new ArrayList<>();
        List<MigrationHistoryPayload.Entry> entries = new ArrayList<>();
        for (int number = oldest; number < currentVersion; number++) {
            Optional<MigrationHistoryPayload.Entry> entry =
                    fetchOne(work, credentials, fragment, number, issues);
            entry.ifPresent(entries::add);
        }
        payloads.write(work.itemId(), MigrationPayloadKind.HISTORY,
                write(MigrationHistoryPayload.of(entries)));
        return issues;
    }

    private Optional<MigrationHistoryPayload.Entry> fetchOne(MigrationStageWork work,
                                                             ConfluenceDcCredentials credentials,
                                                             ConfluenceFragmentConverter.Fragment fragment,
                                                             int number,
                                                             List<MigrationStageIssue> issues) {
        String reference = "version:" + work.externalObjectId() + "@" + number;
        JsonNode content;
        try {
            content = client.historicalContent(credentials, work.externalObjectId(), number);
        } catch (MigrationStageException exception) {
            if (ConfluenceDcCodes.UNAVAILABLE.equals(exception.getCode())) {
                // 원본이 잠시 못 받는 상태다. 삼키면 이력 빠진 문서가 "성공"으로 남는다.
                throw exception;
            }
            log.warn("지난 버전을 받지 못했다: content={} version={} code={}",
                    work.externalObjectId(), number, exception.getCode());
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.HISTORY_VERSION_SKIPPED, reference));
            return Optional.empty();
        }

        String storage = content.path("body").path("storage").path("value").asText("");
        if (storage.getBytes(StandardCharsets.UTF_8).length > properties.maxHistoryVersionBytes()) {
            // 현재본은 상한 없이 옮긴다 — 이력만 자른다. 지난 버전 하나가 DB를 채우는 것보다,
            // 그 버전이 이력에 없다는 사실이 보고서에 남는 편이 낫다.
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.HISTORY_VERSION_SKIPPED, reference));
            return Optional.empty();
        }
        Optional<DocumentIrMarkdownResult> converted = converter.convert(storage,
                "version:" + work.externalObjectId() + ":" + number,
                content.path("title").asText("제목 없음"), fragment);
        if (converted.isEmpty()) {
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.HISTORY_VERSION_SKIPPED, reference));
            return Optional.empty();
        }
        issues.addAll(converted.get().issues());

        JsonNode version = content.path("version");
        return Optional.of(new MigrationHistoryPayload.Entry(number,
                version.path("when").asText(""),
                version.path("by").path("displayName").asText(""),
                version.path("message").asText(""),
                content.path("title").asText(""),
                converted.get().markdown()));
    }

    private String write(MigrationHistoryPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("원본 버전 이력을 기록하지 못했습니다", exception);
        }
    }
}
