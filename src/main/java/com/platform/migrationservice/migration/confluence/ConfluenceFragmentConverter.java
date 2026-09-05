package com.platform.migrationservice.migration.confluence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.migrationservice.migration.ir.DocumentIrMarkdownContext;
import com.platform.migrationservice.migration.ir.DocumentIrMarkdownResult;
import com.platform.migrationservice.migration.ir.DocumentIrMarkdownWriter;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 문서 본문이 아닌 storage XHTML 조각(댓글 본문·지난 버전 본문)을 우리 마크다운으로 옮긴다(M3).
 *
 * 조각도 **같은 정규화기와 같은 writer**를 탄다. 댓글용 변환기를 따로 두면 표·패널·매크로가
 * 본문과 다르게 깨지기 시작하고, 그 차이는 이관이 끝난 뒤 문서마다 다른 모양으로 드러난다.
 * 정규화기는 "페이지 스냅샷"만 읽으므로 조각을 최소 스냅샷 껍데기에 담아 넘긴다.
 *
 * 실패는 예외로 올리지 않는다 — 댓글 하나, 지난 버전 하나 때문에 문서를 통째로 데드레터로
 * 보내는 것은 손해다. 빈 값을 돌려주고 호출부가 그 항목만 건너뛴다.
 */
@Component
@RequiredArgsConstructor
public class ConfluenceFragmentConverter {

    private final ConfluenceStorageNormalizer normalizer;
    private final DocumentIrMarkdownWriter markdownWriter;
    private final ObjectMapper objectMapper;

    /**
     * @param storage   원본 storage XHTML
     * @param fragmentId 정규화기가 IR documentId에 쓸 조각 식별자(`comment:123` 같은 값)
     * @param title      IR 계약이 제목을 요구한다 — 조각에는 없으므로 호출부가 임시 제목을 준다
     * @return 변환 결과. 옮기지 못했으면 빈 값
     */
    public Optional<DocumentIrMarkdownResult> convert(String storage, String fragmentId, String title,
                                                      Fragment context) {
        if (storage == null || storage.isBlank()) {
            return Optional.of(new DocumentIrMarkdownResult("", List.of()));
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("snapshotVersion", ConfluenceStorageNormalizer.SNAPSHOT_VERSION);
        ObjectNode content = snapshot.putObject("content");
        content.put("id", fragmentId);
        content.put("title", title);
        content.putObject("version").put("number", 1);
        content.putObject("space").put("key", context.spaceKey() == null ? "" : context.spaceKey());
        ObjectNode body = content.putObject("body").putObject("storage");
        body.put("value", storage);
        body.put("representation", "storage");

        ConfluenceNormalizationResult normalized;
        try {
            normalized = normalizer.normalize(new ConfluenceNormalizationRequest(snapshot,
                    context.sourceInstanceId(), context.capturedAt(), context.sourceChecksum(),
                    context.payloadRef(), Map.of()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        DocumentIrMarkdownResult rendered = markdownWriter.write(normalized.documentIr(),
                new DocumentIrMarkdownContext(context.spaceKey(), context.baseUrl()));

        // 정규화 손실과 마크다운 손실을 한 목록으로 합친다 — 호출부에는 "이 조각에서 잃은 것" 하나면 된다.
        List<MigrationStageIssue> issues = java.util.stream.Stream.concat(
                        normalized.issues().stream()
                                .map(issue -> new MigrationStageIssue(issue.severity(), issue.code(),
                                        issue.sourcePath())),
                        rendered.issues().stream())
                .toList();
        return Optional.of(new DocumentIrMarkdownResult(rendered.markdown(), issues));
    }

    /** 조각 하나를 옮기는 데 필요한 원본 쪽 맥락. 전부 item에서 그대로 온다. */
    public record Fragment(String sourceInstanceId, Instant capturedAt, String sourceChecksum,
                           String payloadRef, String spaceKey, String baseUrl) {
    }
}
