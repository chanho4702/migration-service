package com.platform.migrationservice.migration.confluence.link;

import com.platform.migrationservice.migration.confluence.handler.ConfluenceDcIssues;
import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.repository.MigrationObjectMappingRepository;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import com.platform.migrationservice.wiki.WikiImportApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 원본 사이트 링크 하나를 우리 문서 주소로 바꾼다. RESOLVE(문서를 만들 때)와 잡 마무리 pass가
 * 같은 규칙을 쓴다 — 두 곳에 다른 규칙을 두면 "처음엔 됐는데 정리 후 깨졌다"가 생긴다.
 *
 * 못 찾았을 때의 행동만 두 경로가 다르다.
 * - RESOLVE: 아직 안 옮긴 문서일 수 있으므로 임시 스킴 {@code dc-page:}로 남긴다.
 * - 마무리 pass: 더 기다릴 것이 없다. 원본 절대 URL로 되돌리고 LINK_UNRESOLVED로 보고한다.
 *
 * 대상을 찾는 길이 둘이다. id로 온 링크는 이관 원장(object map)에서 바로 찾고, 제목만 있는 옛
 * URL은 위키에 물어본다({@code GET /spaces/{id}/pages?title=}) — 이관 원장은 우리 것이고
 * 제목 색인은 위키 것이다.
 */
@Component
@RequiredArgsConstructor
public class MigrationLinkResolver {

    private final MigrationObjectMappingRepository mappings;
    private final MigrationPageMarkdown markdowns;
    private final WikiImportApi wiki;

    /**
     * 링크 대상 문자열 하나를 해석한다. 우리가 다룰 대상이 아니면 {@code null} 교체값을 돌려주고,
     * 그때 호출부는 원문을 그대로 둔다.
     */
    public Resolution resolve(DcPageReference reference, Context context) {
        List<MigrationStageIssue> issues = new ArrayList<>();
        Optional<Target> target = reference.byId()
                ? byObjectMap(reference.contentId(), context)
                : byTitle(reference, context, issues);
        if (target.isEmpty()) {
            if (context.finalPass()) {
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.LINK_UNRESOLVED,
                        "link:" + linkPath(reference)));
                return new Resolution(reference.absoluteUrl(context.baseUrl()), issues);
            }
            return new Resolution(reference.tempTarget(), issues);
        }
        Target found = target.get();
        String url = "/wiki/spaces/" + found.spaceId() + "/pages/" + found.pageId();
        String anchor = reference.anchor();
        if (anchor != null && !anchor.isBlank()) {
            String matched = matchingHeadingSlug(found.markdown(), anchor);
            if (matched == null) {
                issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.ANCHOR_DROPPED,
                        "link:" + linkPath(reference) + "#" + anchor));
            } else {
                url = url + "#" + matched;
            }
        }
        return new Resolution(url, issues);
    }

    /**
     * 이관 원장으로 찾는다. 대상 문서가 위키에서 사라졌으면(사람이 지웠다) 못 찾은 것으로 본다 —
     * 지워진 문서로 가는 링크를 만들어 두면 이관 뒤 404가 남는다.
     */
    private Optional<Target> byObjectMap(String contentId, Context context) {
        MigrationObjectMapping mapping = mappings.findBySourceKey(MigrationObjectMapping.sourceKeyFor(
                        context.provider(), context.sourceInstanceId(), contentId))
                .filter(row -> row.getTargetPageId() != null)
                .orElse(null);
        if (mapping == null) {
            return Optional.empty();
        }
        // 스페이스는 위키가 알려 준 값을 쓴다 — 지난 잡이 다른 스페이스로 옮긴 문서일 수 있다.
        return wiki.getPage(context.actorId(), mapping.getTargetPageId())
                .map(page -> new Target(page.pageId(), page.spaceId(),
                        markdowns.of(mapping).orElse("")));
    }

    /**
     * 제목만 있는 옛 URL. 대상 스페이스에서 같은 제목을 찾는다 — 우리 위키링크가 제목 기준이라
     * (W21-2) 같은 규칙을 쓰는 것이 일관적이다. 여러 건이면 고르지 않고 보고한다.
     */
    private Optional<Target> byTitle(DcPageReference reference, Context context,
                                     List<MigrationStageIssue> issues) {
        if (reference.title() == null || reference.title().isBlank()) {
            return Optional.empty();
        }
        List<WikiImportApi.PageRef> found = wiki.findPagesByTitle(context.actorId(),
                context.targetSpaceId(), reference.title());
        if (found.size() > 1) {
            issues.add(MigrationStageIssue.warning(ConfluenceDcIssues.LINK_AMBIGUOUS,
                    "link:" + linkPath(reference)));
            return Optional.empty();
        }
        return found.stream().findFirst()
                .map(page -> new Target(page.pageId(), page.spaceId(),
                        // 제목으로 찾은 문서의 본문은 우리 원장에 없을 수도 있다(사람이 쓴 문서).
                        // 그때는 앵커를 대조할 근거가 없어 앵커만 떨어진다.
                        markdownOf(context, page.pageId())));
    }

    /** 이관 원장에 있는 문서면 우리가 쓴 마크다운을, 아니면 빈 문자열을 준다. */
    private String markdownOf(Context context, long pageId) {
        return mappings.findFirstByTargetPageIdOrderByIdAsc(pageId)
                .filter(mapping -> mapping.getProvider() == context.provider())
                .flatMap(markdowns::of)
                .orElse("");
    }

    private static String linkPath(DcPageReference reference) {
        return reference.byId() ? reference.contentId()
                : (reference.spaceKey() == null ? "" : reference.spaceKey() + ":") + reference.title();
    }

    /**
     * 앵커가 대상 문서의 헤딩 하나와 맞는가.
     *
     * 우리 보기 화면은 `rehype-slug`(github-slugger)가 붙인 id로 스크롤한다. 여기서는 그 규칙을
     * 근사한다 — 소문자화, 문장부호 제거, 공백은 `-`. 원본 컨플루언스의 앵커는 `제목-헤딩` 꼴이라
     * 뒤쪽만 맞는 경우가 흔해 접미사 일치도 인정한다. 어긋나면 앵커만 떼고 링크는 살린다.
     */
    static String matchingHeadingSlug(String markdown, String anchor) {
        String wanted = slug(anchor);
        if (wanted.isBlank()) {
            return null;
        }
        Set<String> slugs = headingSlugs(markdown);
        if (slugs.contains(wanted)) {
            return wanted;
        }
        for (String candidate : slugs) {
            if (wanted.endsWith("-" + candidate) || candidate.endsWith("-" + wanted)) {
                return candidate;
            }
        }
        return null;
    }

    private static Set<String> headingSlugs(String markdown) {
        Set<String> slugs = new LinkedHashSet<>();
        if (markdown == null) {
            return slugs;
        }
        boolean inFence = false;
        for (String line : markdown.split("\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence || !trimmed.startsWith("#")) {
                continue;
            }
            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                level++;
            }
            if (level > 6 || level >= trimmed.length() || trimmed.charAt(level) != ' ') {
                continue;
            }
            slugs.add(slug(trimmed.substring(level + 1)));
        }
        slugs.remove("");
        return slugs;
    }

    /** github-slugger 근사 — 글자·숫자·`-`·`_`만 남기고 공백을 `-`로 바꾼다. */
    private static String slug(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char character : text.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character) || character == '-' || character == '_') {
                out.append(character);
            } else if (Character.isWhitespace(character)) {
                out.append('-');
            }
        }
        return out.toString();
    }

    /** 찾은 대상 문서 — 주소를 만들 좌표와 앵커를 대조할 본문. */
    private record Target(long pageId, long spaceId, String markdown) {
    }

    /**
     * 해석에 필요한 잡의 좌표.
     *
     * @param actorId 위키에 물을 때 실을 주체(잡 요청자)
     */
    public record Context(MigrationProvider provider, String sourceInstanceId, String baseUrl,
                          long targetSpaceId, long actorId, boolean finalPass) {
    }

    /** 바꿔 넣을 대상 문자열과 그 과정의 손실. */
    public record Resolution(String target, List<MigrationStageIssue> issues) {

        public Resolution {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
