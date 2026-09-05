package com.platform.migrationservice.wiki;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * 가짜 위키 import 서버를 띄우고 이 서비스의 클라이언트를 거기로 돌린다.
 *
 * 서버는 테스트 JVM 하나에 하나다. `@SpringBootTest` 컨텍스트는 프로퍼티 조합마다 따로 캐시되는데,
 * 그때마다 서버를 새로 열면 포트가 달라져 이미 뜬 컨텍스트의 클라이언트가 갈 곳을 잃는다.
 * 상태는 각 테스트가 {@code reset()}으로 비운다.
 */
public abstract class WikiImportTestSupport {

    protected static final FakeWikiImportServer wiki = new FakeWikiImportServer();

    private static final Path STAGING_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "migration-service-staging-test");

    @DynamicPropertySource
    static void wikiImportProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.migration.wiki.base-uri", wiki::baseUrl);
        registry.add("platform.migration.wiki.internal-token", () -> FakeWikiImportServer.INTERNAL_TOKEN);
        registry.add("platform.migration.staging-dir", STAGING_DIR::toString);
    }
}
