package com.platform.migrationservice.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.migrationservice.migration.ir.DocumentIrValidationCode;
import com.platform.migrationservice.migration.ir.DocumentIrValidationException;
import com.platform.migrationservice.migration.ir.DocumentIrValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Document IR v1의 provider 중립 문법·의미 계약을 golden fixture와 런타임 validator로 고정한다.
 */
class DocumentIrContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DocumentIrValidator VALIDATOR = new DocumentIrValidator(JSON);
    private static final String SCHEMA = "/schema/document-ir-v1.schema.json";
    private static final String NOTION = "/fixtures/document-ir/notion-page-v1.json";
    private static final String CONFLUENCE = "/fixtures/document-ir/confluence-page-v1.json";

    @Test
    void v1_스키마가_버전과_provider_중립_block_목록을_고정한다() throws IOException {
        JsonNode schema = read(SCHEMA);

        assertThat(schema.path("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.at("/properties/schemaVersion/const").asInt()).isEqualTo(1);
        assertThat(allowedTypes(schema)).contains(
                "doc", "paragraph", "heading", "text", "table", "panel", "columns",
                "image", "attachment", "pageLink", "mention", "opaque");
    }

    @Test
    void Notion_fixture가_런타임_v1_계약을_충족한다() throws IOException {
        VALIDATOR.validate(read(NOTION));
    }

    @Test
    void Confluence_DC_fixture가_런타임_v1_계약을_충족한다() throws IOException {
        VALIDATOR.validate(read(CONFLUENCE));
    }

    @Test
    void 직렬화된_IR도_같은_경계에서_파싱하고_검증한다() throws IOException {
        byte[] input = JSON.writeValueAsBytes(read(NOTION));

        JsonNode document = VALIDATOR.parseAndValidate(input);

        assertThat(document.path("documentId").asText()).isEqualTo("notion:workspace-acme:page-42");
    }

    @Test
    void 잘못된_JSON은_원문을_노출하지_않고_거부한다() {
        String sensitive = "private migration body";

        assertFailure(
                () -> VALIDATOR.parseAndValidate(("{\"title\":\"" + sensitive + "\"")
                        .getBytes(StandardCharsets.UTF_8)),
                DocumentIrValidationCode.INVALID_JSON,
                "/")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(sensitive));
    }

    @Test
    void 스키마에_없는_최상위_필드는_거부한다() throws IOException {
        ObjectNode document = (ObjectNode) read(NOTION);
        document.put("rawPayload", "must-not-cross-runtime-boundary");

        assertFailure(() -> VALIDATOR.validate(document),
                DocumentIrValidationCode.ADDITIONAL_PROPERTY_FORBIDDEN,
                "/*");
    }

    @Test
    void 지원하지_않는_스키마_버전은_구분해서_거부한다() throws IOException {
        ObjectNode document = (ObjectNode) read(NOTION);
        document.put("schemaVersion", 2);

        assertFailure(() -> VALIDATOR.validate(document),
                DocumentIrValidationCode.UNSUPPORTED_SCHEMA_VERSION,
                "/schemaVersion");
    }

    @Test
    void 중복_media_id는_거부한다() throws IOException {
        ObjectNode document = (ObjectNode) read(NOTION);
        ArrayNode assets = (ArrayNode) document.path("assets");
        assets.add(assets.get(0).deepCopy());

        assertFailure(() -> VALIDATOR.validate(document),
                DocumentIrValidationCode.DUPLICATE_MEDIA_ID,
                "/assets/1/mediaId");
    }

    @Test
    void 중복_block_id는_거부한다() throws IOException {
        JsonNode document = read(NOTION);
        String duplicate = document.at("/root/content/0/id").asText();
        ((ObjectNode) document.at("/root/content/1")).put("id", duplicate);

        assertFailure(() -> VALIDATOR.validate(document),
                DocumentIrValidationCode.DUPLICATE_BLOCK_ID,
                "/root/content/1/id");
    }

    @Test
    void 선언되지_않은_media_id_참조는_거부한다() throws IOException {
        JsonNode document = read(NOTION);
        ((ObjectNode) document.at("/root/content/3/attrs")).put("mediaId", "missing-media");

        assertFailure(() -> VALIDATOR.validate(document),
                DocumentIrValidationCode.UNDECLARED_MEDIA_ID,
                "/root/content/3/attrs/mediaId");
    }

    @Test
    void image에_만료_URL을_직접_저장하면_거부한다() throws IOException {
        JsonNode document = read(NOTION);
        ((ObjectNode) document.at("/root/content/3/attrs"))
                .put("url", "https://secure.notion-static.com/temporary-signed-url");

        assertFailure(() -> VALIDATOR.validate(document),
                DocumentIrValidationCode.EMBEDDED_MEDIA_LOCATION_FORBIDDEN,
                "/root/content/3/attrs/url");
    }

    @Test
    void opaque_block의_source_ref가_없으면_거부한다() throws IOException {
        JsonNode document = read(CONFLUENCE);
        ((ObjectNode) document.at("/root/content/4")).remove("sourceRef");

        assertFailure(() -> VALIDATOR.validate(document),
                DocumentIrValidationCode.REQUIRED_FIELD_MISSING,
                "/root/content/4/sourceRef");
    }

    @Test
    void page_link의_target이_비어_있으면_거부한다() throws IOException {
        JsonNode document = read(NOTION);
        ((ObjectNode) document.at("/root/content/2/attrs")).set("target", JSON.createObjectNode());

        assertFailure(() -> VALIDATOR.validate(document),
                DocumentIrValidationCode.PAGE_LINK_TARGET_MISSING,
                "/root/content/2/attrs/target");
    }

    @Test
    void source_timestamp가_date_time이_아니면_거부한다() throws IOException {
        JsonNode document = read(NOTION);
        ((ObjectNode) document.path("source")).put("capturedAt", "2026-08-17");

        assertFailure(() -> VALIDATOR.validate(document),
                DocumentIrValidationCode.INVALID_VALUE,
                "/source/capturedAt");
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            DocumentIrValidationCode code,
            String path) {
        return assertThatThrownBy(callable)
                .isInstanceOf(DocumentIrValidationException.class)
                .satisfies(throwable -> {
                    DocumentIrValidationException exception = (DocumentIrValidationException) throwable;
                    assertThat(exception.getCode()).isEqualTo(code);
                    assertThat(exception.getPath()).isEqualTo(path);
                });
    }

    private static Set<String> allowedTypes(JsonNode schema) {
        Set<String> result = new HashSet<>();
        schema.at("/$defs/block/properties/type/enum").forEach(node -> result.add(node.asText()));
        assertThat(result).as("schema block type enum").isNotEmpty();
        return result;
    }

    private static JsonNode read(String resource) throws IOException {
        try (InputStream input = DocumentIrContractTest.class.getResourceAsStream(resource)) {
            assertThat(input).as("resource: %s", resource).isNotNull();
            return JSON.readTree(input);
        }
    }
}
