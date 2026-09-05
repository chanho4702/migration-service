package com.platform.migrationservice.migration.confluence;

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

class ConfluenceStorageNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DocumentIrValidator IR_VALIDATOR = new DocumentIrValidator(JSON);
    private static final ConfluenceStorageNormalizer NORMALIZER =
            new ConfluenceStorageNormalizer(JSON, IR_VALIDATOR);
    private static final String FIXTURE =
            "/fixtures/migration/confluence/confluence-page-snapshot-v1.json";

    @Test
    void 공통_storage_XHTML을_유효한_Document_IR로_정규화한다() throws IOException {
        ConfluenceNormalizationResult result = NORMALIZER.normalize(request(readFixture(), resolvedTopology()));
        JsonNode document = result.documentIr();

        IR_VALIDATOR.validate(document);
        assertThat(document.path("title").asText()).isEqualTo("서비스 운영 가이드");
        assertThat(document.at("/source/provider").asText()).isEqualTo("confluence-dc");
        assertThat(document.at("/source/sourceVersion").asText()).isEqualTo("27");
        assertThat(document.at("/root/content/0/type").asText()).isEqualTo("heading");
        assertThat(document.at("/root/content/1/type").asText()).isEqualTo("paragraph");
        assertThat(document.at("/root/content/1/content/0/marks/0/type").asText()).isEqualTo("bold");
        assertThat(document.at("/root/content/1/content/2/marks/0/type").asText()).isEqualTo("link");
        assertThat(document.at("/root/content/2/type").asText()).isEqualTo("panel");
        assertThat(document.at("/root/content/2/attrs/variant").asText()).isEqualTo("warning");
        assertThat(document.at("/root/content/2/attrs/title").asText()).isEqualTo("운영 주의");
        assertThat(document.at("/root/content/3/type").asText()).isEqualTo("bulletList");
        assertThat(document.at("/root/content/3/content")).hasSize(2);
        assertThat(document.at("/root/content/4/type").asText()).isEqualTo("taskList");
        assertThat(document.at("/root/content/4/content/0/attrs/checked").asBoolean()).isFalse();
        assertThat(document.at("/root/content/5/type").asText()).isEqualTo("columns");
        assertThat(document.at("/root/content/5/content")).hasSize(2);
        assertThat(document.at("/root/content/6/type").asText()).isEqualTo("table");
        assertThat(document.at("/root/content/6/content")).hasSize(2);
        assertThat(document.at("/root/content/7/content/0/type").asText()).isEqualTo("pageLink");
        assertThat(document.at("/root/content/7/content/0/attrs/target/externalObjectId").asText())
                .isEqualTo("OPS:장애 대응 절차");
        assertThat(document.at("/root/content/8/type").asText()).isEqualTo("image");
        assertThat(document.at("/root/content/8/attrs/mediaId").asText()).isEqualTo("media-topology");
        assertThat(document.at("/root/content/8/attrs/width").asInt()).isEqualTo(960);
        assertThat(document.at("/root/content/9/type").asText()).isEqualTo("opaque");
        assertThat(document.at("/root/content/9/sourceRef/sourceType").asText())
                .isEqualTo("ac:structured-macro[jira]");
        assertThat(document.path("assets")).hasSize(1);
        assertThat(result.issues()).extracting(issue -> issue.code())
                .containsExactly("CONFLUENCE_UNSUPPORTED_MACRO");
    }

    @Test
    void 같은_storage는_stable_id와_issue_path를_재현한다() throws IOException {
        ConfluenceNormalizationRequest request = request(readFixture(), resolvedTopology());

        ConfluenceNormalizationResult first = NORMALIZER.normalize(request);
        ConfluenceNormalizationResult second = NORMALIZER.normalize(request);

        assertThat(second.documentIr()).isEqualTo(first.documentIr());
        assertThat(second.issues()).isEqualTo(first.issues());
    }

    @Test
    void 복사되지_않은_attachment_image는_opaque_issue로_남긴다() throws IOException {
        ConfluenceNormalizationResult result = NORMALIZER.normalize(request(readFixture(), Map.of()));

        assertThat(result.documentIr().at("/root/content/8/type").asText()).isEqualTo("opaque");
        assertThat(result.documentIr().path("assets")).isEmpty();
        assertThat(result.issues()).extracting(issue -> issue.code())
                .containsExactly("CONFLUENCE_MEDIA_NOT_COPIED", "CONFLUENCE_UNSUPPORTED_MACRO");
    }

    @Test
    void DOCTYPE과_external_entity는_parser_진입_전에_거부한다() throws IOException {
        JsonNode snapshot = readFixture();
        storage(snapshot).put("value",
                "<!DOCTYPE page [<!ENTITY xxe SYSTEM \"file:///C:/Windows/win.ini\">]><p>&xxe;</p>");

        assertFailure(() -> NORMALIZER.normalize(request(snapshot, resolvedTopology())),
                ConfluenceNormalizationCode.UNSAFE_STORAGE_XML,
                "/content/body/storage/value");
    }

    @Test
    void malformed_storage_XML은_원문을_노출하지_않고_거부한다() throws IOException {
        JsonNode snapshot = readFixture();
        String sensitive = "private confluence body";
        storage(snapshot).put("value", "<p>" + sensitive);

        assertThatThrownBy(() -> NORMALIZER.normalize(request(snapshot, resolvedTopology())))
                .isInstanceOf(ConfluenceNormalizationException.class)
                .satisfies(throwable -> {
                    ConfluenceNormalizationException exception = (ConfluenceNormalizationException) throwable;
                    assertThat(exception.getCode()).isEqualTo(ConfluenceNormalizationCode.INVALID_STORAGE_XML);
                    assertThat(exception.getPath()).isEqualTo("/content/body/storage/value");
                    assertThat(exception.getMessage()).doesNotContain(sensitive);
                });
    }

    @Test
    void 실행가능한_external_link_scheme은_mark에서_제거한다() throws IOException {
        JsonNode snapshot = readFixture();
        storage(snapshot).put("value", storage(snapshot).path("value").asText()
                .replace("https://example.com/runbook", "javascript:alert(1)"));

        ConfluenceNormalizationResult result = NORMALIZER.normalize(request(snapshot, resolvedTopology()));

        assertThat(result.documentIr().at("/root/content/1/content/2/marks").isMissingNode()).isTrue();
        assertThat(result.documentIr().toString()).doesNotContain("javascript:alert");
        assertThat(result.issues()).extracting(issue -> issue.code())
                .contains("CONFLUENCE_UNSAFE_LINK_DROPPED");
    }

    @Test
    void provider_CSS는_실행하지_않고_text와_loss_issue만_보존한다() throws IOException {
        JsonNode snapshot = readFixture();
        storage(snapshot).put("value", storage(snapshot).path("value").asText()
                .replace("운영 변경", "<span style=\"color:red\">운영 변경</span>"));

        ConfluenceNormalizationResult result = NORMALIZER.normalize(request(snapshot, resolvedTopology()));

        assertThat(result.documentIr().toString()).contains("운영 변경").doesNotContain("color:red", "style");
        assertThat(result.issues()).extracting(issue -> issue.code())
                .contains("CONFLUENCE_INLINE_STYLE_DROPPED");
    }

    @Test
    void 특정_DC_version_field가_없어도_공통_fixture_parser는_동작한다() throws IOException {
        JsonNode snapshot = readFixture();

        ConfluenceNormalizationResult result = NORMALIZER.normalize(request(snapshot, resolvedTopology()));

        assertThat(result.documentIr().at("/source/sourceVersion").asText()).isEqualTo("27");
        assertThat(snapshot.has("confluenceVersion")).isFalse();
    }

    private static ConfluenceNormalizationRequest request(
            JsonNode snapshot, Map<String, ResolvedMigrationAsset> assets) {
        return new ConfluenceNormalizationRequest(
                snapshot,
                "engineering-dc",
                Instant.parse("2026-08-17T00:05:00Z"),
                "c".repeat(64),
                "imports/confluence/job-20/content-10001.snapshot.json",
                assets);
    }

    private static Map<String, ResolvedMigrationAsset> resolvedTopology() {
        return Map.of(ConfluenceMediaReference.attachment("topology.png"), new ResolvedMigrationAsset(
                "media-topology",
                "attachment-30001:version-4",
                "topology.png",
                "image/png",
                4096,
                "d".repeat(64),
                DocumentIrAssetRole.INLINE));
    }

    private static ObjectNode storage(JsonNode snapshot) {
        return (ObjectNode) snapshot.at("/content/body/storage");
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                      ConfluenceNormalizationCode code, String path) {
        assertThatThrownBy(callable)
                .isInstanceOf(ConfluenceNormalizationException.class)
                .satisfies(throwable -> {
                    ConfluenceNormalizationException exception = (ConfluenceNormalizationException) throwable;
                    assertThat(exception.getCode()).isEqualTo(code);
                    assertThat(exception.getPath()).isEqualTo(path);
                });
    }

    private static JsonNode readFixture() throws IOException {
        try (InputStream input = ConfluenceStorageNormalizerTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(input).as("resource: %s", FIXTURE).isNotNull();
            return JSON.readTree(input);
        }
    }
}
