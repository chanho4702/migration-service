package com.platform.migrationservice.wiki;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * 위키 내부 import API 접속 정보.
 *
 * baseUri는 **내부망 주소**다. 이 요청에는 {@code X-Internal-Token}이 붙으므로 게이트웨이·nginx를
 * 거치는 공개 주소를 넣으면 토큰이 우리 통제 밖 홉을 지난다.
 *
 * requestTimeout은 worker lease(기본 5분)보다 짧아야 한다 — 더 길면 handler가 아직 응답을
 * 기다리는 동안 다른 노드가 같은 item을 회수해 같은 문서를 두 번 쓴다.
 */
@Component
public record WikiImportProperties(
        @Value("${platform.migration.wiki.base-uri:http://localhost:9110}") URI baseUri,
        @Value("${platform.migration.wiki.internal-token:}") String internalToken,
        @Value("${platform.migration.wiki.connect-timeout:PT10S}") Duration connectTimeout,
        @Value("${platform.migration.wiki.request-timeout:PT60S}") Duration requestTimeout) {

    /** 위키 내부 import API의 고정 접두사. 게이트웨이는 이 경로를 라우팅하지 않는다. */
    public static final String IMPORT_PATH = "/internal/wiki/import";

    public WikiImportProperties {
        if (baseUri == null || baseUri.getScheme() == null
                || !(baseUri.getScheme().equals("http") || baseUri.getScheme().equals("https"))) {
            throw new IllegalArgumentException("위키 주소는 http 또는 https여야 합니다");
        }
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
    }

    /** import API 경로 하나의 절대 주소. 호출부가 URL을 직접 조립하지 않게 여기서만 만든다(SSRF 규칙). */
    public URI resolve(String path) {
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + IMPORT_PATH + path);
    }
}
