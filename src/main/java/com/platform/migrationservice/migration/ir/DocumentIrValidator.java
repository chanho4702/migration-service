package com.platform.migrationservice.migration.ir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.ADDITIONAL_PROPERTY_FORBIDDEN;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.DUPLICATE_BLOCK_ID;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.DUPLICATE_MEDIA_ID;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.EMBEDDED_MEDIA_LOCATION_FORBIDDEN;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.INVALID_JSON;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.INVALID_TYPE;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.INVALID_VALUE;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.PAGE_LINK_TARGET_MISSING;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.REQUIRED_FIELD_MISSING;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.UNDECLARED_MEDIA_ID;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.UNSUPPORTED_BLOCK_TYPE;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.UNSUPPORTED_PROVIDER;
import static com.platform.migrationservice.migration.ir.DocumentIrValidationCode.UNSUPPORTED_SCHEMA_VERSION;

/**
 * Provider 중립 Document IR v1의 런타임 문법·의미 경계다.
 *
 * <p>enum, pattern, 길이 제한은 bundled JSON Schema에서 읽고, block ID 유일성이나 media 참조
 * 무결성처럼 JSON Schema로 표현하기 어려운 규칙은 여기에서 추가 검증한다. 오류에는 원본 값이나
 * 본문을 넣지 않고 stable code와 JSON path만 제공한다.</p>
 */
@Component
public final class DocumentIrValidator {

    private static final String SCHEMA_RESOURCE = "/schema/document-ir-v1.schema.json";
    private static final Set<String> DOCUMENT_FIELDS = Set.of(
            "schemaVersion", "documentId", "title", "source", "assets", "root");
    private static final Set<String> SOURCE_FIELDS = Set.of(
            "provider", "instanceId", "objectId", "sourceVersion", "capturedAt", "checksum", "payloadRef");
    private static final Set<String> SOURCE_REQUIRED_FIELDS = Set.of(
            "provider", "instanceId", "objectId", "capturedAt", "checksum", "payloadRef");
    private static final Set<String> ASSET_FIELDS = Set.of(
            "mediaId", "sourceExternalId", "filename", "contentType", "sizeBytes", "checksum", "role");
    private static final Set<String> ASSET_REQUIRED_FIELDS = Set.of(
            "mediaId", "filename", "contentType", "sizeBytes", "checksum", "role");
    private static final Set<String> BLOCK_FIELDS = Set.of(
            "id", "type", "attrs", "text", "marks", "content", "sourceRef");
    private static final Set<String> MARK_FIELDS = Set.of("type", "attrs");
    private static final Set<String> SOURCE_REF_FIELDS = Set.of(
            "provider", "objectId", "sourceType", "path", "checksum");

    private final ObjectMapper objectMapper;
    private final Contract contract;

    public DocumentIrValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.contract = loadContract(objectMapper);
    }

    /**
     * 직렬화된 IR을 파싱하고 검증한다. Jackson 오류 상세에는 원문 일부가 포함될 수 있으므로
     * 외부에는 안전한 INVALID_JSON 오류만 전달한다.
     */
    public JsonNode parseAndValidate(byte[] input) {
        if (input == null) {
            throw failure(INVALID_JSON, "/");
        }
        try {
            JsonNode document = objectMapper.readTree(input);
            if (document == null) {
                throw failure(INVALID_JSON, "/");
            }
            validate(document);
            return document;
        } catch (DocumentIrValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(INVALID_JSON, "/");
        }
    }

    public void validate(JsonNode document) {
        requireObject(document, "/");
        requireOnlyFields(document, DOCUMENT_FIELDS, "/");
        requireFields(document, DOCUMENT_FIELDS, "/");

        JsonNode schemaVersion = document.get("schemaVersion");
        if (!schemaVersion.isIntegralNumber()) {
            throw failure(INVALID_TYPE, "/schemaVersion");
        }
        if (schemaVersion.asInt() != contract.schemaVersion()) {
            throw failure(UNSUPPORTED_SCHEMA_VERSION, "/schemaVersion");
        }

        validateString(document.get("documentId"), contract.documentId(), "/documentId");
        validateString(document.get("title"), contract.title(), "/title");
        validateSource(document.get("source"), "/source");
        Set<String> mediaIds = validateAssets(document.get("assets"), "/assets");
        validateBlocks(document.get("root"), mediaIds, "/root");
    }

    private void validateSource(JsonNode source, String path) {
        requireObject(source, path);
        requireOnlyFields(source, SOURCE_FIELDS, path);
        requireFields(source, SOURCE_REQUIRED_FIELDS, path);

        validateEnum(source.get("provider"), contract.sourceProviders(), path + "/provider", UNSUPPORTED_PROVIDER);
        validateString(source.get("instanceId"), contract.sourceInstanceId(), path + "/instanceId");
        validateString(source.get("objectId"), contract.sourceObjectId(), path + "/objectId");
        if (source.has("sourceVersion")) {
            validateString(source.get("sourceVersion"), contract.sourceVersion(), path + "/sourceVersion");
        }

        JsonNode capturedAt = source.get("capturedAt");
        requireText(capturedAt, path + "/capturedAt");
        try {
            OffsetDateTime.parse(capturedAt.textValue());
        } catch (DateTimeParseException exception) {
            throw failure(INVALID_VALUE, path + "/capturedAt");
        }

        validateString(source.get("checksum"), contract.checksum(), path + "/checksum");
        validateString(source.get("payloadRef"), contract.sourcePayloadRef(), path + "/payloadRef");
    }

    private Set<String> validateAssets(JsonNode assets, String path) {
        requireArray(assets, path);
        Set<String> mediaIds = new HashSet<>();
        for (int index = 0; index < assets.size(); index++) {
            String assetPath = path + "/" + index;
            JsonNode asset = assets.get(index);
            requireObject(asset, assetPath);
            requireOnlyFields(asset, ASSET_FIELDS, assetPath);
            requireFields(asset, ASSET_REQUIRED_FIELDS, assetPath);

            validateString(asset.get("mediaId"), contract.mediaId(), assetPath + "/mediaId");
            if (!mediaIds.add(asset.get("mediaId").textValue())) {
                throw failure(DUPLICATE_MEDIA_ID, assetPath + "/mediaId");
            }
            if (asset.has("sourceExternalId")) {
                validateString(asset.get("sourceExternalId"), contract.assetSourceExternalId(),
                        assetPath + "/sourceExternalId");
            }
            validateString(asset.get("filename"), contract.assetFilename(), assetPath + "/filename");
            validateString(asset.get("contentType"), contract.assetContentType(), assetPath + "/contentType");
            JsonNode sizeBytes = asset.get("sizeBytes");
            if (!sizeBytes.isIntegralNumber() || !sizeBytes.canConvertToLong()) {
                throw failure(INVALID_TYPE, assetPath + "/sizeBytes");
            }
            if (sizeBytes.asLong() < contract.minimumAssetSize()) {
                throw failure(INVALID_VALUE, assetPath + "/sizeBytes");
            }
            validateString(asset.get("checksum"), contract.checksum(), assetPath + "/checksum");
            validateEnum(asset.get("role"), contract.assetRoles(), assetPath + "/role", INVALID_VALUE);
        }
        return mediaIds;
    }

    private void validateBlocks(JsonNode root, Set<String> mediaIds, String rootPath) {
        requireObject(root, rootPath);
        if (!root.has("type")) {
            throw failure(REQUIRED_FIELD_MISSING, rootPath + "/type");
        }
        if (!root.get("type").isTextual()) {
            throw failure(INVALID_TYPE, rootPath + "/type");
        }
        if (!"doc".equals(root.get("type").textValue())) {
            throw failure(INVALID_VALUE, rootPath + "/type");
        }

        Set<String> blockIds = new HashSet<>();
        Deque<BlockAtPath> remaining = new ArrayDeque<>();
        remaining.push(new BlockAtPath(root, rootPath));

        while (!remaining.isEmpty()) {
            BlockAtPath current = remaining.pop();
            JsonNode block = current.block();
            String path = current.path();
            requireObject(block, path);
            requireOnlyFields(block, BLOCK_FIELDS, path);
            requireFields(block, Set.of("id", "type"), path);

            validateString(block.get("id"), contract.blockId(), path + "/id");
            if (!blockIds.add(block.get("id").textValue())) {
                throw failure(DUPLICATE_BLOCK_ID, path + "/id");
            }
            validateEnum(block.get("type"), contract.blockTypes(), path + "/type", UNSUPPORTED_BLOCK_TYPE);
            String type = block.get("type").textValue();

            JsonNode attrs = block.get("attrs");
            if (attrs != null) {
                requireObject(attrs, path + "/attrs");
            }
            if (block.has("text")) {
                requireText(block.get("text"), path + "/text");
            }
            if ("text".equals(type) && !block.has("text")) {
                throw failure(REQUIRED_FIELD_MISSING, path + "/text");
            }
            if (block.has("marks")) {
                validateMarks(block.get("marks"), path + "/marks");
            }
            if (block.has("sourceRef")) {
                validateSourceRef(block.get("sourceRef"), path + "/sourceRef");
            }
            if ("opaque".equals(type) && !block.has("sourceRef")) {
                throw failure(REQUIRED_FIELD_MISSING, path + "/sourceRef");
            }
            if ("image".equals(type) || "attachment".equals(type)) {
                validateMediaBlock(attrs, mediaIds, path);
            }
            if ("pageLink".equals(type)) {
                validatePageLink(attrs, path);
            }

            JsonNode content = block.get("content");
            if (content != null) {
                requireArray(content, path + "/content");
                for (int index = content.size() - 1; index >= 0; index--) {
                    remaining.push(new BlockAtPath(content.get(index), path + "/content/" + index));
                }
            }
        }
    }

    private void validateMarks(JsonNode marks, String path) {
        requireArray(marks, path);
        for (int index = 0; index < marks.size(); index++) {
            String markPath = path + "/" + index;
            JsonNode mark = marks.get(index);
            requireObject(mark, markPath);
            requireOnlyFields(mark, MARK_FIELDS, markPath);
            if (!mark.has("type")) {
                throw failure(REQUIRED_FIELD_MISSING, markPath + "/type");
            }
            validateEnum(mark.get("type"), contract.markTypes(), markPath + "/type", INVALID_VALUE);
            if (mark.has("attrs")) {
                requireObject(mark.get("attrs"), markPath + "/attrs");
            }
        }
    }

    private void validateSourceRef(JsonNode sourceRef, String path) {
        requireObject(sourceRef, path);
        requireOnlyFields(sourceRef, SOURCE_REF_FIELDS, path);
        requireFields(sourceRef, SOURCE_REF_FIELDS, path);
        validateEnum(sourceRef.get("provider"), contract.sourceRefProviders(), path + "/provider",
                UNSUPPORTED_PROVIDER);
        validateString(sourceRef.get("objectId"), contract.sourceRefObjectId(), path + "/objectId");
        validateString(sourceRef.get("sourceType"), contract.sourceRefType(), path + "/sourceType");
        validateString(sourceRef.get("path"), contract.sourceRefPath(), path + "/path");
        validateString(sourceRef.get("checksum"), contract.checksum(), path + "/checksum");
    }

    private void validateMediaBlock(JsonNode attrs, Set<String> mediaIds, String path) {
        if (attrs == null) {
            throw failure(REQUIRED_FIELD_MISSING, path + "/attrs");
        }
        if (!attrs.has("mediaId")) {
            throw failure(REQUIRED_FIELD_MISSING, path + "/attrs/mediaId");
        }
        validateString(attrs.get("mediaId"), contract.mediaId(), path + "/attrs/mediaId");
        if (!mediaIds.contains(attrs.get("mediaId").textValue())) {
            throw failure(UNDECLARED_MEDIA_ID, path + "/attrs/mediaId");
        }
        if (attrs.has("src")) {
            throw failure(EMBEDDED_MEDIA_LOCATION_FORBIDDEN, path + "/attrs/src");
        }
        if (attrs.has("url")) {
            throw failure(EMBEDDED_MEDIA_LOCATION_FORBIDDEN, path + "/attrs/url");
        }
    }

    private void validatePageLink(JsonNode attrs, String path) {
        if (attrs == null) {
            throw failure(REQUIRED_FIELD_MISSING, path + "/attrs");
        }
        JsonNode target = attrs.get("target");
        if (target == null) {
            throw failure(REQUIRED_FIELD_MISSING, path + "/attrs/target");
        }
        requireObject(target, path + "/attrs/target");
        if (!hasNonNull(target, "internalPageId")
                && !hasNonNull(target, "externalObjectId")
                && !hasNonNull(target, "href")) {
            throw failure(PAGE_LINK_TARGET_MISSING, path + "/attrs/target");
        }
    }

    private static boolean hasNonNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull();
    }

    private static void validateString(JsonNode node, StringRule rule, String path) {
        requireText(node, path);
        String value = node.textValue();
        int length = value.codePointCount(0, value.length());
        if (length < rule.minimumLength() || length > rule.maximumLength()) {
            throw failure(INVALID_VALUE, path);
        }
        if (rule.pattern() != null && !rule.pattern().matcher(value).matches()) {
            throw failure(INVALID_VALUE, path);
        }
    }

    private static void validateEnum(JsonNode node, Set<String> allowed, String path,
                                     DocumentIrValidationCode invalidCode) {
        requireText(node, path);
        if (!allowed.contains(node.textValue())) {
            throw failure(invalidCode, path);
        }
    }

    private static void requireText(JsonNode node, String path) {
        if (node == null) {
            throw failure(REQUIRED_FIELD_MISSING, path);
        }
        if (!node.isTextual()) {
            throw failure(INVALID_TYPE, path);
        }
    }

    private static void requireObject(JsonNode node, String path) {
        if (node == null || node.isMissingNode()) {
            throw failure(REQUIRED_FIELD_MISSING, path);
        }
        if (!node.isObject()) {
            throw failure(INVALID_TYPE, path);
        }
    }

    private static void requireArray(JsonNode node, String path) {
        if (node == null) {
            throw failure(REQUIRED_FIELD_MISSING, path);
        }
        if (!node.isArray()) {
            throw failure(INVALID_TYPE, path);
        }
    }

    private static void requireFields(JsonNode node, Set<String> fields, String path) {
        for (String field : fields.stream().sorted().toList()) {
            if (!node.has(field)) {
                throw failure(REQUIRED_FIELD_MISSING, childPath(path, field));
            }
        }
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String path) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!allowed.contains(field)) {
                // 알 수 없는 field name도 외부 입력이므로 오류 응답에 그대로 반사하지 않는다.
                throw failure(ADDITIONAL_PROPERTY_FORBIDDEN, childPath(path, "*"));
            }
        }
    }

    private static String childPath(String parent, String child) {
        return "/".equals(parent) ? "/" + child : parent + "/" + child;
    }

    private static DocumentIrValidationException failure(DocumentIrValidationCode code, String path) {
        return new DocumentIrValidationException(code, path);
    }

    private static Contract loadContract(ObjectMapper objectMapper) {
        try (InputStream input = DocumentIrValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Document IR schema resource is missing");
            }
            JsonNode schema = objectMapper.readTree(input);
            return new Contract(
                    requiredInt(schema, "/properties/schemaVersion/const"),
                    stringRule(schema, "/properties/documentId"),
                    stringRule(schema, "/properties/title"),
                    enumValues(schema, "/$defs/sourceMetadata/properties/provider/enum"),
                    stringRule(schema, "/$defs/sourceMetadata/properties/instanceId"),
                    stringRule(schema, "/$defs/sourceMetadata/properties/objectId"),
                    stringRule(schema, "/$defs/sourceMetadata/properties/sourceVersion"),
                    stringRule(schema, "/$defs/sourceMetadata/properties/payloadRef"),
                    stringRule(schema, "/$defs/checksum"),
                    stringRule(schema, "/$defs/asset/properties/mediaId"),
                    stringRule(schema, "/$defs/asset/properties/sourceExternalId"),
                    stringRule(schema, "/$defs/asset/properties/filename"),
                    stringRule(schema, "/$defs/asset/properties/contentType"),
                    requiredLong(schema, "/$defs/asset/properties/sizeBytes/minimum"),
                    enumValues(schema, "/$defs/asset/properties/role/enum"),
                    stringRule(schema, "/$defs/block/properties/id"),
                    enumValues(schema, "/$defs/block/properties/type/enum"),
                    enumValues(schema, "/$defs/mark/properties/type/enum"),
                    enumValues(schema, "/$defs/sourceRef/properties/provider/enum"),
                    stringRule(schema, "/$defs/sourceRef/properties/objectId"),
                    stringRule(schema, "/$defs/sourceRef/properties/sourceType"),
                    stringRule(schema, "/$defs/sourceRef/properties/path"));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Document IR schema resource is invalid", exception);
        }
    }

    private static StringRule stringRule(JsonNode schema, String pointer) {
        JsonNode rule = schema.at(pointer);
        if (!rule.isObject()) {
            throw new IllegalStateException("Missing string rule at " + pointer);
        }
        int minimum = rule.path("minLength").asInt(0);
        int maximum = rule.path("maxLength").asInt(Integer.MAX_VALUE);
        String expression = rule.path("pattern").asText(null);
        Pattern pattern = expression == null ? null : Pattern.compile(expression);
        return new StringRule(minimum, maximum, pattern);
    }

    private static Set<String> enumValues(JsonNode schema, String pointer) {
        JsonNode values = schema.at(pointer);
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalStateException("Missing enum at " + pointer);
        }
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalStateException("Non-text enum at " + pointer);
            }
            result.add(value.textValue());
        });
        return Set.copyOf(result);
    }

    private static int requiredInt(JsonNode schema, String pointer) {
        JsonNode value = schema.at(pointer);
        if (!value.isIntegralNumber()) {
            throw new IllegalStateException("Missing integer at " + pointer);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode schema, String pointer) {
        JsonNode value = schema.at(pointer);
        if (!value.isIntegralNumber()) {
            throw new IllegalStateException("Missing integer at " + pointer);
        }
        return value.longValue();
    }

    private record BlockAtPath(JsonNode block, String path) {
    }

    private record StringRule(int minimumLength, int maximumLength, Pattern pattern) {
    }

    private record Contract(
            int schemaVersion,
            StringRule documentId,
            StringRule title,
            Set<String> sourceProviders,
            StringRule sourceInstanceId,
            StringRule sourceObjectId,
            StringRule sourceVersion,
            StringRule sourcePayloadRef,
            StringRule checksum,
            StringRule mediaId,
            StringRule assetSourceExternalId,
            StringRule assetFilename,
            StringRule assetContentType,
            long minimumAssetSize,
            Set<String> assetRoles,
            StringRule blockId,
            Set<String> blockTypes,
            Set<String> markTypes,
            Set<String> sourceRefProviders,
            StringRule sourceRefObjectId,
            StringRule sourceRefType,
            StringRule sourceRefPath) {
    }
}
