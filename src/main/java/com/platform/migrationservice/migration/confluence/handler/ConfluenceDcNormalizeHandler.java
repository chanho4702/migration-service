package com.platform.migrationservice.migration.confluence.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.MigrationPayloadStore;
import com.platform.migrationservice.migration.confluence.ConfluenceNormalizationException;
import com.platform.migrationservice.migration.confluence.ConfluenceNormalizationRequest;
import com.platform.migrationservice.migration.confluence.ConfluenceNormalizationResult;
import com.platform.migrationservice.migration.confluence.ConfluenceStorageNormalizer;
import com.platform.migrationservice.migration.ir.DocumentIrValidationException;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.normalization.MigrationNormalizationIssue;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.migration.worker.MigrationStageHandler;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.migration.worker.MigrationStageOutcome;
import com.platform.migrationservice.migration.worker.MigrationStageWork;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 스냅샷 → Document IR. 정규화기가 이미 있었지만 아무도 부르지 않던 자리를 여기서 메운다.
 *
 * 실패는 전부 비재시도다. 같은 XHTML을 다시 읽어도 결과가 달라질 이유가 없어, 재시도는 데드레터를
 * 늦출 뿐이고 그동안 관리자는 job이 진행 중이라고 오해한다.
 */
@Component
@RequiredArgsConstructor
public class ConfluenceDcNormalizeHandler implements MigrationStageHandler {

    private final ConfluenceStorageNormalizer normalizer;
    private final MigrationPayloadStore payloads;
    private final ObjectMapper objectMapper;

    @Override
    public MigrationProvider provider() {
        return MigrationProvider.CONFLUENCE_DC;
    }

    @Override
    public MigrationStage stage() {
        return MigrationStage.NORMALIZE;
    }

    @Override
    public MigrationStageOutcome handle(MigrationStageWork work) {
        MigrationPayloadStore.StoredPayload stored =
                payloads.require(work.itemId(), MigrationPayloadKind.SNAPSHOT);
        JsonNode snapshot = parse(stored.body());

        ConfluenceNormalizationResult result;
        try {
            // M1은 첨부 본체를 옮기지 않으므로 해결된 자산이 없다(MEDIA_COPY가 경고로 보고한다).
            result = normalizer.normalize(new ConfluenceNormalizationRequest(
                    snapshot, work.sourceInstanceId(), stored.createdAt(), work.sourceChecksum(),
                    work.payloadRef(), Map.of()));
        } catch (DocumentIrValidationException e) {
            throw new MigrationStageException(ConfluenceDcIssues.IR_INVALID, false, e);
        } catch (ConfluenceNormalizationException e) {
            throw new MigrationStageException(ConfluenceDcIssues.SNAPSHOT_INVALID, false, e);
        }
        payloads.write(work.itemId(), MigrationPayloadKind.IR, result.documentIr().toString());

        List<MigrationStageIssue> issues = result.issues().stream()
                .map(ConfluenceDcNormalizeHandler::toStageIssue)
                .toList();
        return MigrationStageOutcome.ok(issues);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw MigrationStageException.permanent(ConfluenceDcIssues.SNAPSHOT_INVALID);
        }
    }

    private static MigrationStageIssue toStageIssue(MigrationNormalizationIssue issue) {
        return new MigrationStageIssue(issue.severity(), issue.code(), issue.sourcePath());
    }
}
