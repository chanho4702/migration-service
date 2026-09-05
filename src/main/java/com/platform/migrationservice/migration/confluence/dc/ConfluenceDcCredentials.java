package com.platform.migrationservice.migration.confluence.dc;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 원본 DC 한 곳에 붙는 데 필요한 값. baseUrl은 여기서 한 번만 검증하고, 이후 모든 요청은
 * 이 값 + 고정 경로로만 만든다 — 응답이 알려주는 `_links`를 따라가지 않는다(SSRF 방지).
 */
public record ConfluenceDcCredentials(String baseUrl, String spaceKey, String token) {

    public ConfluenceDcCredentials {
        baseUrl = normalizeBaseUrl(baseUrl);
        spaceKey = requireText(spaceKey, "스페이스 키를 입력하세요", 255);
        token = requireText(token, "개인 액세스 토큰을 입력하세요", 4096);
    }

    /**
     * http(s)만, 자격증명 없이, 경로 이하만 남긴다.
     *
     * 스킴을 열어두면 `file:`·`gopher:` 같은 것으로 서버 로컬을 읽게 만들 수 있고, userinfo를
     * 허용하면 `https://evil@internal/`처럼 사람 눈과 파서가 다르게 읽는 주소가 통과한다.
     * 쿼리·프래그먼트를 지우는 이유는 우리가 뒤에 붙일 고정 경로가 그것들 뒤로 밀려나기 때문이다.
     */
    private static String normalizeBaseUrl(String raw) {
        String value = requireText(raw, "원본 컨플루언스 주소를 입력하세요", 512);
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("원본 컨플루언스 주소 형식이 올바르지 않습니다");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("원본 컨플루언스 주소는 http 또는 https여야 합니다");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("원본 컨플루언스 주소에 호스트가 없습니다");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("원본 컨플루언스 주소에 계정 정보를 넣을 수 없습니다");
        }
        StringBuilder normalized = new StringBuilder(scheme).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            normalized.append(':').append(uri.getPort());
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return normalized.append(path).toString();
    }

    /** 원본 사이트를 식별하는 값 — job.sourceInstanceId의 기본값이다. */
    public String instanceId() {
        return URI.create(baseUrl).getHost();
    }

    /** 토큰이 로그·오류 메시지로 새지 않도록 전체를 가린다. */
    @Override
    public String toString() {
        return "ConfluenceDcCredentials[baseUrl=" + baseUrl + ", spaceKey=" + spaceKey + ", token=***]";
    }

    private static String requireText(String value, String message, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
