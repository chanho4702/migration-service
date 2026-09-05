package com.platform.migrationservice.migration.confluence.link;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 원본 컨플루언스의 문서 하나를 가리키는 참조. 링크 재작성이 다루는 유일한 대상이다.
 *
 * 원본 본문의 `<a href>`는 세 가지 꼴로 온다(§4.2).
 * <ul>
 *   <li>{@code /pages/viewpage.action?pageId=12345} — id 직접</li>
 *   <li>{@code /spaces/ENG/pages/12345/제목} — id + 제목(최신 DC의 표준 URL)</li>
 *   <li>{@code /display/ENG/제목} — 제목만(옛 URL). id가 없어 대상 스페이스의 제목으로 찾는다.</li>
 * </ul>
 *
 * 아직 옮기지 않은 문서를 가리키면 임시 스킴 {@code dc-page:{참조}}로 본문에 남았다가, 잡이 끝날 때
 * 마무리 pass가 다시 해석한다. 임시 스킴이 필요한 이유는 이때 원본 URL을 그대로 두면 사용자가
 * 이관된 문서에서 원본 사이트로 튕겨 나가고, 그 링크가 이관 실패라는 사실이 드러나지 않기 때문이다.
 */
public record DcPageReference(String contentId, String spaceKey, String title, String anchor) {

    /** 본문에 남기는 임시 스킴. wiki-front의 Link 확장이 이 스킴을 허용해야 편집기 왕복에서 살아남는다. */
    public static final String TEMP_SCHEME = "dc-page:";

    public boolean byId() {
        return contentId != null && !contentId.isBlank();
    }

    /** 임시 스킴 문자열 — id를 알면 id로, 아니면 {@code 스페이스키/제목}으로 적는다. */
    public String tempTarget() {
        String body = byId() ? contentId : (spaceKey == null ? "" : spaceKey) + "/" + title;
        return TEMP_SCHEME + body + (anchor == null || anchor.isBlank() ? "" : "#" + anchor);
    }

    /** 끝내 못 찾았을 때 되돌릴 원본 절대 URL. 깨진 링크보다 원본으로 가는 링크가 낫다. */
    public String absoluteUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String path = byId()
                ? "/pages/viewpage.action?pageId=" + contentId
                : "/display/" + (spaceKey == null ? "" : spaceKey) + "/" + title.replace(" ", "+");
        return base + path + (anchor == null || anchor.isBlank() ? "" : "#" + anchor);
    }

    /** 본문에 이미 박혀 있는 임시 스킴을 되읽는다. */
    public static Optional<DcPageReference> parseTemp(String target) {
        if (target == null || !target.startsWith(TEMP_SCHEME)) {
            return Optional.empty();
        }
        String body = target.substring(TEMP_SCHEME.length());
        String anchor = null;
        int hash = body.indexOf('#');
        if (hash >= 0) {
            anchor = body.substring(hash + 1);
            body = body.substring(0, hash);
        }
        if (body.isBlank()) {
            return Optional.empty();
        }
        if (body.chars().allMatch(Character::isDigit)) {
            return Optional.of(new DcPageReference(body, null, null, anchor));
        }
        int slash = body.indexOf('/');
        if (slash < 0) {
            return Optional.of(new DcPageReference(null, null, body, anchor));
        }
        return Optional.of(new DcPageReference(null, body.substring(0, slash), body.substring(slash + 1),
                anchor));
    }

    /**
     * 원본 사이트를 가리키는 절대·상대 URL을 참조로 읽는다. 이 사이트가 아니거나 아는 꼴이 아니면 빈 값 —
     * 그때는 링크를 손대지 않는다(바깥 세상으로 가는 링크까지 우리가 고칠 일은 아니다).
     */
    public static Optional<DcPageReference> parseSourceUrl(String target, String baseUrl) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String rest;
        if (!base.isBlank() && target.startsWith(base)) {
            rest = target.substring(base.length());
        } else if (target.startsWith("/")) {
            // 원본 본문의 상대 링크. 같은 사이트를 가리키는 것이 확실하다.
            rest = target;
        } else {
            return Optional.empty();
        }

        String anchor = null;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            anchor = decode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        String query = "";
        int question = rest.indexOf('?');
        if (question >= 0) {
            query = rest.substring(question + 1);
            rest = rest.substring(0, question);
        }

        if (rest.equals("/pages/viewpage.action")) {
            String pageId = queryValue(query, "pageId");
            return pageId == null ? Optional.empty()
                    : Optional.of(new DcPageReference(pageId, null, null, anchor));
        }
        String[] segments = rest.split("/");
        // "/spaces/{KEY}/pages/{id}/..." — 최신 DC의 표준 URL
        if (segments.length >= 5 && "spaces".equals(segments[1]) && "pages".equals(segments[3])
                && !segments[4].isBlank() && segments[4].chars().allMatch(Character::isDigit)) {
            return Optional.of(new DcPageReference(segments[4], decode(segments[2]), null, anchor));
        }
        // "/display/{KEY}/{제목}" — id가 없어 제목으로 찾아야 하는 옛 URL
        if (segments.length >= 4 && "display".equals(segments[1]) && !segments[3].isBlank()) {
            // URLDecoder가 '+'를 공백으로 되돌린다 — 컨플루언스의 display URL이 쓰는 표기다.
            String title = decode(String.join("/",
                    java.util.Arrays.asList(segments).subList(3, segments.length)));
            return Optional.of(new DcPageReference(null, decode(segments[2]), title, anchor));
        }
        return Optional.empty();
    }

    private static String queryValue(String query, String name) {
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return decode(pair.substring(equals + 1));
            }
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            // 퍼센트 인코딩이 깨진 링크. 원문 그대로 쓰는 편이 링크를 버리는 것보다 낫다.
            return value;
        }
    }
}
