package com.platform.migrationservice.migration.confluence.link;

import com.platform.migrationservice.migration.MigrationPayloadStore;
import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcIssues;
import com.platform.migrationservice.migration.model.MigrationIssue;
import com.platform.migrationservice.migration.model.MigrationItem;
import com.platform.migrationservice.migration.model.MigrationJob;
import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.model.MigrationPayloadKind;
import com.platform.migrationservice.migration.repository.MigrationIssueRepository;
import com.platform.migrationservice.migration.repository.MigrationItemRepository;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.wiki.WikiImportApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 링크 정리 pass의 문서 한 건. 별도 빈으로 둔 이유는 트랜잭션 때문이다 — 같은 클래스 안에서
 * 부르면 프록시를 타지 않아 REQUIRES_NEW가 무시되고, 한 문서의 손실 기록 실패가 나머지 정리를
 * 통째로 무른다.
 *
 * 고칠 본문은 우리가 그 문서에 마지막으로 쓴 마크다운({@link MigrationPageMarkdown})이다. 위키에
 * 본문을 되묻지 않는 이유는 거기 적어 두었다.
 */
@Component
@RequiredArgsConstructor
public class MigrationLinkFixupWriter {

    /** `[[제목]]` — writer가 이스케이프 없이 그대로 쓰는 형태다. */
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]\\n]+)\\]\\]");

    /** 링크 정리 리비전에 붙는 변경 요약. 화면에서 이 문구로 이관 정리를 알아본다. */
    public static final String CHANGE_NOTE = "이관 링크 정리";

    private final MigrationLinkRewriter rewriter;
    private final MigrationPageMarkdown markdowns;
    private final MigrationItemRepository items;
    private final MigrationIssueRepository issues;
    private final MigrationPayloadStore payloads;
    private final WikiImportApi wiki;

    /** @return 본문이 실제로 바뀌었으면 true */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fixOne(MigrationJob job, MigrationObjectMapping mapping,
                          MigrationLinkResolver.Context context) {
        String before = markdowns.of(mapping).orElse(null);
        if (before == null) {
            return false;
        }
        List<MigrationStageIssue> reported =
                new ArrayList<>(ambiguousWikiLinks(before, context));
        if (!before.contains(DcPageReference.TEMP_SCHEME)) {
            // 임시 링크가 없는 문서는 더 볼 것이 없다. 대부분의 문서가 여기서 걸러진다.
            recordIssues(job, mapping, reported);
            return false;
        }
        MigrationLinkRewriter.Result result = rewriter.rewriteTempLinks(before, context);
        reported.addAll(result.issues());
        recordIssues(job, mapping, reported);
        if (result.markdown().equals(before)) {
            return false;
        }
        wiki.rewriteContent(job.getRequestedBy(), mapping.getTargetPageId(), result.markdown(),
                CHANGE_NOTE);
        // 우리 산출물도 함께 눌러 둔다 — 그러지 않으면 다음 잡의 정리 pass가 임시 링크가 남아
        // 있는 옛 본문을 보고 아무것도 안 바뀐 리비전을 한 번 더 쌓는다.
        rememberFixed(mapping, result.markdown());
        return true;
    }

    /** 고친 본문을 그 문서를 만든 항목의 MARKDOWN 산출물에 되쓴다. */
    private void rememberFixed(MigrationObjectMapping mapping, String markdown) {
        if (mapping.getLastJobId() == null) {
            return;
        }
        items.findByJobIdAndSourceKey(mapping.getLastJobId(),
                        MigrationItem.sourceKeyFor(mapping.getExternalObjectId()))
                .ifPresent(item -> payloads.write(item.getId(), MigrationPayloadKind.MARKDOWN, markdown));
    }

    /**
     * 제목 기반 위키링크(`[[제목]]`)가 대상 스페이스에서 여럿에 걸리는지 본다.
     *
     * 이 링크는 저장 시점이 아니라 **보는 시점에** 제목으로 해석된다. 그래서 우리가 고쳐 줄 것은
     * 없지만, 어느 문서로 열릴지 모른다는 사실은 보고서에 남아야 한다 — 이관 뒤 "링크가 엉뚱한
     * 문서로 간다"를 원인 없이 만나는 것이 가장 나쁘다.
     */
    private List<MigrationStageIssue> ambiguousWikiLinks(String markdown,
                                                         MigrationLinkResolver.Context context) {
        Matcher matcher = WIKI_LINK.matcher(markdown);
        Set<String> seen = new LinkedHashSet<>();
        List<MigrationStageIssue> found = new ArrayList<>();
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            if (title.isEmpty() || !seen.add(title)) {
                continue;
            }
            if (wiki.findPagesByTitle(context.actorId(), context.targetSpaceId(), title).size() > 1) {
                found.add(MigrationStageIssue.warning(ConfluenceDcIssues.LINK_AMBIGUOUS,
                        "link:" + title));
            }
        }
        return found;
    }

    /** 손실은 그 문서를 만든 항목에 붙인다 — 보고서가 "어느 원본에서 난 문제인지"를 잃지 않는다. */
    private void recordIssues(MigrationJob job, MigrationObjectMapping mapping,
                      List<MigrationStageIssue> reported) {
        if (reported.isEmpty()) {
            return;
        }
        Optional<MigrationItem> item = items.findByJobIdAndSourceKey(job.getId(),
                MigrationItem.sourceKeyFor(mapping.getExternalObjectId()));
        if (item.isEmpty()) {
            return;
        }
        for (MigrationStageIssue issue : reported) {
            String issueKey = MigrationIssue.issueKeyFor(issue.code(), issue.sourcePath());
            Optional<MigrationIssue> existing =
                    issues.findByItemIdAndIssueKey(item.get().getId(), issueKey);
            if (existing.isPresent()) {
                existing.get().incrementOccurrence();
                issues.save(existing.get());
                continue;
            }
            issues.save(MigrationIssue.of(job.getId(), item.get().getId(), issue.severity(),
                    issue.code(), issue.sourcePath()));
        }
    }
}
