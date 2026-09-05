package com.platform.migrationservice.migration.confluence.link;

import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 본문 전체의 원본 사이트 링크를 우리 주소로 바꾼다.
 *
 * 두 번 돈다. 문서를 만들 때 한 번(그 시점에 이미 옮겨진 문서는 바로 연결된다), 잡이 끝날 때 한 번
 * (그 사이에 옮겨진 문서를 마저 연결한다). 첫 패스에서 못 찾은 것은 `dc-page:` 임시 스킴으로 남아
 * 두 번째 패스가 알아본다 — 원본 URL로 두면 두 번째 패스가 "이건 원래 바깥 링크였나 못 찾은
 * 링크였나"를 구분할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class MigrationLinkRewriter {

    private final MigrationLinkResolver resolver;

    /** 문서를 만들 때(RESOLVE) — 원본 절대·상대 URL을 본다. */
    public Result rewriteSourceLinks(String markdown, MigrationLinkResolver.Context context) {
        return rewrite(markdown, context,
                target -> DcPageReference.parseSourceUrl(target, context.baseUrl()));
    }

    /** 잡 마무리 pass — 첫 패스가 남긴 임시 스킴만 본다. */
    public Result rewriteTempLinks(String markdown, MigrationLinkResolver.Context context) {
        return rewrite(markdown, context, DcPageReference::parseTemp);
    }

    private Result rewrite(String markdown, MigrationLinkResolver.Context context,
                           ReferenceParser parser) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        String rewritten = MarkdownLinkTargets.rewrite(markdown, target -> parser.parse(target)
                .map(reference -> {
                    MigrationLinkResolver.Resolution resolution = resolver.resolve(reference, context);
                    issues.addAll(resolution.issues());
                    return resolution.target();
                })
                .orElse(null));
        return new Result(rewritten, issues);
    }

    @FunctionalInterface
    private interface ReferenceParser {
        java.util.Optional<DcPageReference> parse(String target);
    }

    public record Result(String markdown, List<MigrationStageIssue> issues) {

        public Result {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
