package com.platform.migrationservice.migration.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.migrationservice.migration.ir.DocumentIrValidator;
import com.platform.migrationservice.migration.normalization.DocumentIrAssetRole;
import com.platform.migrationservice.migration.normalization.ResolvedMigrationAsset;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotionPageSnapshotNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DocumentIrValidator IR_VALIDATOR = new DocumentIrValidator(JSON);
    private static final NotionPageSnapshotNormalizer NORMALIZER =
            new NotionPageSnapshotNormalizer(JSON, IR_VALIDATOR);
    private static final String FIXTURE = "/fixtures/migration/notion/notion-page-snapshot-v1.json";
    private static final String IMAGE_BLOCK_ID = "77777777-7777-4777-8777-777777777777";

    @Test
    void paginated_recursive_snapshot을_유효한_Document_IR로_정규화한다() throws IOException {
        NotionNormalizationResult result = NORMALIZER.normalize(request(readFixture(), resolvedImage()));
        JsonNode document = result.documentIr();

        IR_VALIDATOR.validate(document);
        assertThat(document.path("title").asText()).isEqualTo("배포 체크리스트");
        assertThat(document.at("/source/provider").asText()).isEqualTo("notion");
        assertThat(document.at("/root/content/0/type").asText()).isEqualTo("heading");
        assertThat(document.at("/root/content/1/type").asText()).isEqualTo("paragraph");
        assertThat(document.at("/root/content/2/type").asText()).isEqualTo("bulletList");
        assertThat(document.at("/root/content/2/content")).hasSize(2);
        assertThat(document.at("/root/content/2/content/0/content/1/type").asText()).isEqualTo("paragraph");
        assertThat(document.at("/root/content/3/type").asText()).isEqualTo("pageLink");
        assertThat(document.at("/root/content/4/type").asText()).isEqualTo("image");
        assertThat(document.at("/root/content/5/type").asText()).isEqualTo("opaque");

        assertThat(document.at("/root/content/1/content/0/marks").findValuesAsText("type"))
                .containsExactly("italic", "highlight", "link");
        assertThat(document.path("assets")).hasSize(1);
        assertThat(document.at("/assets/0/mediaId").asText()).isEqualTo("media-notion-flow");
        assertThat(document.toString())
                .doesNotContain("secure.notion-static.com", "temporary-signed-url", "expiry_time");
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .containsExactly("NOTION_UNSUPPORTED_BLOCK");
        assertThat(document.at("/root/content/5/sourceRef/checksum").asText()).matches("[a-f0-9]{64}");
    }

    @Test
    void 같은_snapshot은_stable_id와_checksum을_재현한다() throws IOException {
        NotionNormalizationRequest request = request(readFixture(), resolvedImage());

        NotionNormalizationResult first = NORMALIZER.normalize(request);
        NotionNormalizationResult second = NORMALIZER.normalize(request);

        assertThat(second.documentIr()).isEqualTo(first.documentIr());
        assertThat(second.issues()).isEqualTo(first.issues());
    }

    @Test
    void 복사되지_않은_Notion_image는_임시_URL_대신_opaque_issue로_보존한다() throws IOException {
        NotionNormalizationResult result = NORMALIZER.normalize(request(readFixture(), Map.of()));

        assertThat(result.documentIr().at("/root/content/4/type").asText()).isEqualTo("opaque");
        assertThat(result.documentIr().path("assets")).isEmpty();
        assertThat(result.documentIr().toString()).doesNotContain("temporary-signed-url");
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .containsExactly("NOTION_MEDIA_NOT_COPIED", "NOTION_UNSUPPORTED_BLOCK");
    }

    @Test
    void 마지막_page가_has_more면_부분_import를_거부한다() throws IOException {
        JsonNode snapshot = readFixture();
        ((ObjectNode) snapshot.at(
                "/blockChildren/11111111-1111-4111-8111-111111111111/1")).put("has_more", true);

        assertFailure(() -> NORMALIZER.normalize(request(snapshot, resolvedImage())),
                NotionNormalizationCode.INCOMPLETE_PAGINATION,
                "/blockChildren/11111111-1111-4111-8111-111111111111/1/has_more");
    }

    @Test
    void has_children_block의_응답이_없으면_부분_tree를_거부한다() throws IOException {
        JsonNode snapshot = readFixture();
        ((ObjectNode) snapshot.path("blockChildren"))
                .remove("44444444-4444-4444-8444-444444444444");

        assertFailure(() -> NORMALIZER.normalize(request(snapshot, resolvedImage())),
                NotionNormalizationCode.MISSING_BLOCK_CHILDREN,
                "/blockChildren/44444444-4444-4444-8444-444444444444");
    }

    @Test
    void 지원하지_않는_Notion_API_version은_명시적으로_거부한다() throws IOException {
        JsonNode snapshot = readFixture();
        ((ObjectNode) snapshot).put("notionApiVersion", "2025-09-03");

        assertFailure(() -> NORMALIZER.normalize(request(snapshot, resolvedImage())),
                NotionNormalizationCode.UNSUPPORTED_NOTION_API_VERSION,
                "/notionApiVersion");
    }

    @Test
    void 실행가능한_link_scheme은_mark에서_제거하고_issue를_남긴다() throws IOException {
        JsonNode snapshot = readFixture();
        ObjectNode richText = (ObjectNode) snapshot.at(
                "/blockChildren/11111111-1111-4111-8111-111111111111/0/results/1/paragraph/rich_text/0");
        richText.put("href", "javascript:alert(1)");
        ((ObjectNode) richText.at("/text/link")).put("url", "javascript:alert(1)");

        NotionNormalizationResult result = NORMALIZER.normalize(request(snapshot, resolvedImage()));

        assertThat(result.documentIr().at("/root/content/1/content/0/marks").findValuesAsText("type"))
                .containsExactly("italic", "highlight");
        assertThat(result.documentIr().toString()).doesNotContain("javascript:alert");
        assertThat(result.issues())
                .extracting(issue -> issue.code())
                .contains("NOTION_UNSAFE_LINK_DROPPED");
    }

    private static NotionNormalizationRequest request(
            JsonNode snapshot, Map<String, ResolvedMigrationAsset> assets) {
        return new NotionNormalizationRequest(
                snapshot,
                "workspace-acme",
                Instant.parse("2026-08-17T00:05:00Z"),
                "a".repeat(64),
                "imports/notion/job-10/page-42.snapshot.json",
                assets);
    }

    private static Map<String, ResolvedMigrationAsset> resolvedImage() {
        return Map.of(IMAGE_BLOCK_ID, new ResolvedMigrationAsset(
                "media-notion-flow",
                IMAGE_BLOCK_ID,
                "deployment-flow.png",
                "image/png",
                2048,
                "b".repeat(64),
                DocumentIrAssetRole.INLINE));
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                      NotionNormalizationCode code, String path) {
        assertThatThrownBy(callable)
                .isInstanceOf(NotionNormalizationException.class)
                .satisfies(throwable -> {
                    NotionNormalizationException exception = (NotionNormalizationException) throwable;
                    assertThat(exception.getCode()).isEqualTo(code);
                    assertThat(exception.getPath()).isEqualTo(path);
                });
    }

    private static JsonNode readFixture() throws IOException {
        try (InputStream input = NotionPageSnapshotNormalizerTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(input).as("resource: %s", FIXTURE).isNotNull();
            return JSON.readTree(input);
        }
    }
}
