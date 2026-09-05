package com.platform.migrationservice.migration.confluence.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.MigrationPayloadStore;
import com.platform.migrationservice.migration.confluence.ImportedPageWriter;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.model.MigrationStage;
import com.platform.migrationservice.migration.worker.MigrationStageException;
import com.platform.migrationservice.migration.worker.MigrationStageHandler;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.migration.worker.MigrationStageOutcome;
import com.platform.migrationservice.migration.worker.MigrationStageWork;
import com.platform.migrationservice.wiki.MigrationLabels;
import com.platform.migrationservice.wiki.WikiImportApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 옮긴 결과를 원본과 대조한다(성공지표 S2).
 *
 * 대조는 위키 import API의 조회(`GET /pages/{id}`)로 한다 — 본문 전체가 아니라 제목·종류·본문
 * 길이·라벨 수만 받는다. "옮겼는데 안 보인다"를 잡는 데 필요한 것은 그것뿐이고, 문서 500건의
 * 본문을 되받는 것은 이관이 끝난 뒤에 할 일이 아니다.
 *
 * 대조 실패는 예외가 아니라 ERROR issue다 — 항목 자체는 처리를 마쳤고, 무엇이 어긋났는지는
 * 사람이 보고서에서 보고 판단할 일이다. 여기서 실패시키면 재시도가 같은 문서를 또 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ConfluenceDcVerifyHandler implements MigrationStageHandler {

    private final MigrationPayloadStore payloads;
    private final WikiImportApi wiki;
    private final ObjectMapper objectMapper;

    @Override
    public MigrationProvider provider() {
        return MigrationProvider.CONFLUENCE_DC;
    }

    @Override
    public MigrationStage stage() {
        return MigrationStage.VERIFY;
    }

    @Override
    public MigrationStageOutcome handle(MigrationStageWork work) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        String reference = "page:" + work.externalObjectId();

        if (work.dryRun()) {
            // 쓴 것이 없으니 대조할 대상도 없다. 확인할 수 있는 것은 "마크다운까지 갔는가"뿐이다.
            if (payloads.read(work.itemId(), MigrationPayloadKind.MARKDOWN).isEmpty()) {
                issues.add(MigrationStageIssue.error(ConfluenceDcIssues.VERIFY_MARKDOWN_MISSING, reference));
            }
            return MigrationStageOutcome.ok(issues);
        }

        Long pageId = work.targetPageId();
        Optional<WikiImportApi.ImportedPageView> page = pageId == null
                ? Optional.empty()
                : wiki.getPage(work.requestedBy(), pageId);
        if (page.isEmpty()) {
            issues.add(MigrationStageIssue.error(ConfluenceDcIssues.VERIFY_PAGE_MISSING, reference));
            return MigrationStageOutcome.ok(issues);
        }

        JsonNode content = parse(payloads.require(work.itemId(), MigrationPayloadKind.SNAPSHOT).body())
                .path("content");
        String expectedTitle = content.path("title").asText("");
        String actualTitle = page.get().title();
        // 제목이 상한을 넘어 잘린 경우는 잘린 쪽으로 비교한다 — 그 손실은 이미 TITLE_TRUNCATED로 보고했다.
        String comparableTitle = expectedTitle.length() > ImportedPageWriter.MAX_TITLE_LENGTH
                ? expectedTitle.substring(0, ImportedPageWriter.MAX_TITLE_LENGTH)
                : expectedTitle;
        if (!comparableTitle.trim().equals(actualTitle)) {
            issues.add(MigrationStageIssue.error(ConfluenceDcIssues.VERIFY_TITLE_MISMATCH, reference));
        }
        // 블로그 글이 일반 문서로 들어가면 블로그 목록에서 사라지고, 반대면 트리에서 사라진다(M3 §5.1).
        // 어느 쪽이든 "옮겼는데 안 보인다"가 되므로 대조 대상이다.
        WikiImportApi.ImportedPageType expectedType =
                "blogpost".equalsIgnoreCase(content.path("type").asText(""))
                        ? WikiImportApi.ImportedPageType.BLOG
                        : WikiImportApi.ImportedPageType.PAGE;
        if (page.get().type() != expectedType) {
            issues.add(MigrationStageIssue.error(ConfluenceDcIssues.VERIFY_TYPE_MISMATCH, reference));
        }
        if (page.get().contentLength() == 0) {
            // 원본이 빈 문서였다면 정상이다. 원본에 본문이 있었는데 비었으면 변환이 통째로 날아간 것이다.
            String storage = content.path("body").path("storage").path("value").asText("");
            if (!storage.isBlank()) {
                issues.add(MigrationStageIssue.error(ConfluenceDcIssues.VERIFY_BODY_EMPTY, reference));
            }
        }
        Set<String> expectedLabels = new LinkedHashSet<>();
        for (JsonNode label : content.path("metadata").path("labels").path("results")) {
            String name = label.path("name").asText("");
            if (!name.isBlank()) {
                MigrationLabels.normalize(name).ifPresent(expectedLabels::add);
            }
        }
        if (expectedLabels.size() != page.get().labels().size()) {
            issues.add(MigrationStageIssue.error(ConfluenceDcIssues.VERIFY_LABEL_MISMATCH, reference));
        }
        return MigrationStageOutcome.page(pageId, issues);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw MigrationStageException.permanent(ConfluenceDcIssues.SNAPSHOT_INVALID);
        }
    }
}
