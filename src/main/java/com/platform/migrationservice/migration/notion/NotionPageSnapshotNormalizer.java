package com.platform.migrationservice.migration.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.migrationservice.migration.ir.DocumentIrValidator;
import com.platform.migrationservice.migration.model.MigrationIssueSeverity;
import com.platform.migrationservice.migration.model.MigrationObjectMapping;
import com.platform.migrationservice.migration.model.MigrationProvider;
import com.platform.migrationservice.migration.normalization.MigrationNormalizationIssue;
import com.platform.migrationservice.migration.normalization.ResolvedMigrationAsset;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.platform.migrationservice.migration.notion.NotionNormalizationCode.INCOMPLETE_PAGINATION;
import static com.platform.migrationservice.migration.notion.NotionNormalizationCode.INVALID_SNAPSHOT;
import static com.platform.migrationservice.migration.notion.NotionNormalizationCode.MISSING_BLOCK_CHILDREN;
import static com.platform.migrationservice.migration.notion.NotionNormalizationCode.PAGE_TITLE_MISSING;
import static com.platform.migrationservice.migration.notion.NotionNormalizationCode.UNSUPPORTED_NOTION_API_VERSION;
import static com.platform.migrationservice.migration.notion.NotionNormalizationCode.UNSUPPORTED_SNAPSHOT_VERSION;

/**
 * Notion page와 Retrieve block children 응답 묶음을 provider 중립 Document IR v1로 정규화한다.
 *
 * <p>snapshot은 API 응답을 합쳐 변형한 block tree가 아니라, parent block ID별 원본 list response
 * 페이지 배열을 보존한다. 따라서 pagination/recursive children 누락을 변환 전에 검출할 수 있다.</p>
 */
@Component
public final class NotionPageSnapshotNormalizer {

    public static final int SNAPSHOT_VERSION = 1;
    public static final String NOTION_API_VERSION = "2026-03-11";

    private static final int MAX_BLOCK_DEPTH = 128;
    private static final int MAX_BLOCKS = 100_000;
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "snapshotVersion", "notionApiVersion", "page", "blockChildren");

    private final ObjectMapper objectMapper;
    private final DocumentIrValidator documentIrValidator;

    public NotionPageSnapshotNormalizer(ObjectMapper objectMapper, DocumentIrValidator documentIrValidator) {
        this.objectMapper = objectMapper;
        this.documentIrValidator = documentIrValidator;
    }

    public NotionNormalizationResult normalize(NotionNormalizationRequest request) {
        if (request == null) {
            throw failure(INVALID_SNAPSHOT, "/");
        }
        JsonNode snapshot = request.snapshot();
        requireObject(snapshot, "/");
        requireOnlyFields(snapshot, SNAPSHOT_FIELDS, "/");
        requireFields(snapshot, SNAPSHOT_FIELDS, "/");

        JsonNode snapshotVersion = snapshot.get("snapshotVersion");
        if (!snapshotVersion.isIntegralNumber()) {
            throw failure(INVALID_SNAPSHOT, "/snapshotVersion");
        }
        if (snapshotVersion.intValue() != SNAPSHOT_VERSION) {
            throw failure(UNSUPPORTED_SNAPSHOT_VERSION, "/snapshotVersion");
        }
        String notionApiVersion = requireText(snapshot.get("notionApiVersion"), "/notionApiVersion");
        if (!NOTION_API_VERSION.equals(notionApiVersion)) {
            throw failure(UNSUPPORTED_NOTION_API_VERSION, "/notionApiVersion");
        }

        JsonNode page = snapshot.get("page");
        requireObject(page, "/page");
        String pageId = requireText(page.get("id"), "/page/id");
        String sourceVersion = requireText(page.get("last_edited_time"), "/page/last_edited_time");
        String title = pageTitle(page);

        JsonNode childResponses = snapshot.get("blockChildren");
        requireObject(childResponses, "/blockChildren");
        Context context = new Context(request, childResponses);
        ArrayNode rootContent = normalizeChildren(context, pageId, 0);

        ObjectNode document = objectMapper.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("documentId", "notion:" + MigrationObjectMapping.sourceKeyFor(
                MigrationProvider.NOTION, request.sourceInstanceId(), pageId));
        document.put("title", title);

        ObjectNode source = document.putObject("source");
        source.put("provider", "notion");
        source.put("instanceId", request.sourceInstanceId());
        source.put("objectId", pageId);
        source.put("sourceVersion", sourceVersion);
        if (request.capturedAt() == null) {
            throw failure(INVALID_SNAPSHOT, "/$metadata/capturedAt");
        }
        source.put("capturedAt", request.capturedAt().toString());
        source.put("checksum", request.sourceChecksum());
        source.put("payloadRef", request.payloadRef());

        document.set("assets", context.assets);
        ObjectNode root = document.putObject("root");
        root.put("id", pageId + ":root");
        root.put("type", "doc");
        root.set("content", rootContent);

        documentIrValidator.validate(document);
        return new NotionNormalizationResult(document, context.issues);
    }

    private ArrayNode normalizeChildren(Context context, String parentId, int depth) {
        if (depth > MAX_BLOCK_DEPTH) {
            throw failure(INVALID_SNAPSHOT, childrenPath(parentId));
        }
        if (!context.activeParents.add(parentId)) {
            throw failure(INVALID_SNAPSHOT, childrenPath(parentId));
        }
        try {
            List<BlockAtPath> blocks = childBlocks(context, parentId);
            ArrayNode normalized = objectMapper.createArrayNode();
            for (int index = 0; index < blocks.size();) {
                BlockAtPath current = blocks.get(index);
                String type = blockType(current);
                if (isListItem(type)) {
                    int end = index + 1;
                    while (end < blocks.size() && blockType(blocks.get(end)).equals(type)) {
                        end += 1;
                    }
                    normalized.add(normalizeListGroup(context, blocks.subList(index, end), type, depth));
                    index = end;
                    continue;
                }
                if ("to_do".equals(type)) {
                    int end = index + 1;
                    while (end < blocks.size() && "to_do".equals(blockType(blocks.get(end)))) {
                        end += 1;
                    }
                    normalized.add(normalizeTaskGroup(context, blocks.subList(index, end), depth));
                    index = end;
                    continue;
                }
                normalized.add(normalizeBlock(context, current, depth));
                index += 1;
            }
            return normalized;
        } finally {
            context.activeParents.remove(parentId);
        }
    }

    private List<BlockAtPath> childBlocks(Context context, String parentId) {
        String path = childrenPath(parentId);
        JsonNode pages = context.childResponses.get(parentId);
        if (pages == null) {
            throw failure(MISSING_BLOCK_CHILDREN, path);
        }
        requireArray(pages, path);
        if (pages.isEmpty()) {
            throw failure(INCOMPLETE_PAGINATION, path);
        }

        List<BlockAtPath> blocks = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            String pagePath = path + "/" + pageIndex;
            JsonNode response = pages.get(pageIndex);
            requireObject(response, pagePath);
            JsonNode results = response.get("results");
            requireArray(results, pagePath + "/results");
            JsonNode hasMoreNode = response.get("has_more");
            if (hasMoreNode == null || !hasMoreNode.isBoolean()) {
                throw failure(INVALID_SNAPSHOT, pagePath + "/has_more");
            }
            boolean hasMore = hasMoreNode.booleanValue();
            boolean lastCapturedPage = pageIndex == pages.size() - 1;
            if (hasMore == lastCapturedPage) {
                throw failure(INCOMPLETE_PAGINATION, pagePath + "/has_more");
            }
            if (hasMore && (response.get("next_cursor") == null
                    || !response.get("next_cursor").isTextual()
                    || response.get("next_cursor").textValue().isBlank())) {
                throw failure(INCOMPLETE_PAGINATION, pagePath + "/next_cursor");
            }

            for (int resultIndex = 0; resultIndex < results.size(); resultIndex++) {
                String blockPath = pagePath + "/results/" + resultIndex;
                JsonNode block = results.get(resultIndex);
                requireObject(block, blockPath);
                requireText(block.get("id"), blockPath + "/id");
                requireText(block.get("type"), blockPath + "/type");
                JsonNode hasChildren = block.get("has_children");
                if (hasChildren == null || !hasChildren.isBoolean()) {
                    throw failure(INVALID_SNAPSHOT, blockPath + "/has_children");
                }
                context.blockCount += 1;
                if (context.blockCount > MAX_BLOCKS) {
                    throw failure(INVALID_SNAPSHOT, blockPath);
                }
                if (hasChildren.booleanValue() && !context.childResponses.has(block.get("id").textValue())) {
                    throw failure(MISSING_BLOCK_CHILDREN, childrenPath(block.get("id").textValue()));
                }
                blocks.add(new BlockAtPath(block, blockPath));
            }
        }
        return blocks;
    }

    private ObjectNode normalizeBlock(Context context, BlockAtPath current, int depth) {
        JsonNode block = current.block();
        String type = blockType(current);
        String id = block.get("id").textValue();
        return switch (type) {
            case "paragraph" -> richTextContainer(context, current, "paragraph", depth);
            case "heading_1" -> heading(context, current, 1, depth);
            case "heading_2" -> heading(context, current, 2, depth);
            case "heading_3" -> heading(context, current, 3, depth);
            case "heading_4" -> heading(context, current, 4, depth);
            case "quote" -> richTextContainer(context, current, "blockquote", depth);
            case "code" -> codeBlock(context, current, depth);
            case "divider" -> simpleBlock(id, "horizontalRule");
            case "callout" -> panel(context, current, "callout", depth);
            case "toggle" -> panel(context, current, "expand", depth);
            case "column_list" -> childrenOnlyContainer(context, current, "columns", depth);
            case "column" -> childrenOnlyContainer(context, current, "column", depth);
            case "child_page" -> childPage(current);
            case "link_to_page" -> linkToPage(context, current);
            case "image", "file", "pdf" -> media(context, current, type);
            default -> opaque(context, current, "NOTION_UNSUPPORTED_BLOCK", MigrationIssueSeverity.WARNING);
        };
    }

    private ObjectNode richTextContainer(Context context, BlockAtPath current, String targetType, int depth) {
        String sourceType = blockType(current);
        String id = current.block().get("id").textValue();
        ObjectNode result = simpleBlock(id, targetType);
        ArrayNode content = richText(context, current, sourceType);
        appendChildren(context, current, content, depth);
        result.set("content", content);
        return result;
    }

    private ObjectNode heading(Context context, BlockAtPath current, int level, int depth) {
        ObjectNode result = richTextContainer(context, current, "heading", depth);
        result.putObject("attrs").put("level", level);
        return result;
    }

    private ObjectNode codeBlock(Context context, BlockAtPath current, int depth) {
        ObjectNode result = richTextContainer(context, current, "codeBlock", depth);
        JsonNode body = blockBody(current);
        String language = body.path("language").asText("");
        if (!language.isBlank()) {
            result.putObject("attrs").put("language", language);
        }
        return result;
    }

    private ObjectNode panel(Context context, BlockAtPath current, String variant, int depth) {
        ObjectNode result = richTextContainer(context, current, "panel", depth);
        ObjectNode attrs = result.putObject("attrs");
        attrs.put("variant", variant);
        JsonNode body = blockBody(current);
        String color = body.path("color").asText("");
        if (!color.isBlank() && !"default".equals(color)) {
            attrs.put("sourceColor", color);
        }
        JsonNode icon = body.path("icon");
        if (icon.isObject() && "emoji".equals(icon.path("type").asText())
                && icon.path("emoji").isTextual()) {
            attrs.put("icon", icon.path("emoji").textValue());
        }
        return result;
    }

    private ObjectNode childrenOnlyContainer(Context context, BlockAtPath current, String targetType, int depth) {
        String id = current.block().get("id").textValue();
        ObjectNode result = simpleBlock(id, targetType);
        result.set("content", childrenIfPresent(context, current, depth));
        return result;
    }

    private ObjectNode childPage(BlockAtPath current) {
        String id = current.block().get("id").textValue();
        ObjectNode result = simpleBlock(id, "pageLink");
        ObjectNode attrs = result.putObject("attrs");
        attrs.put("label", requireText(blockBody(current).get("title"), current.path() + "/child_page/title"));
        attrs.putObject("target").put("externalObjectId", id);
        return result;
    }

    private ObjectNode linkToPage(Context context, BlockAtPath current) {
        JsonNode body = blockBody(current);
        String linkType = requireText(body.get("type"), current.path() + "/link_to_page/type");
        JsonNode externalId = body.get(linkType);
        if (externalId == null || !externalId.isTextual() || externalId.textValue().isBlank()) {
            return opaque(context, current, "NOTION_UNSUPPORTED_LINK_TARGET", MigrationIssueSeverity.WARNING);
        }
        ObjectNode result = simpleBlock(current.block().get("id").textValue(), "pageLink");
        ObjectNode attrs = result.putObject("attrs");
        attrs.putObject("target").put("externalObjectId", externalId.textValue());
        return result;
    }

    private ObjectNode media(Context context, BlockAtPath current, String sourceType) {
        String blockId = current.block().get("id").textValue();
        ResolvedMigrationAsset asset = context.request.resolvedAssetsByBlockId().get(blockId);
        if (asset == null) {
            return opaque(context, current, "NOTION_MEDIA_NOT_COPIED", MigrationIssueSeverity.ERROR);
        }
        if (asset.mediaId() == null || asset.mediaId().isBlank() || asset.role() == null) {
            throw failure(INVALID_SNAPSHOT, "/$metadata/resolvedAssets/*");
        }
        ResolvedMigrationAsset previous = context.usedAssets.putIfAbsent(asset.mediaId(), asset);
        if (previous != null && !previous.equals(asset)) {
            throw failure(INVALID_SNAPSHOT, "/$metadata/resolvedAssets/*");
        }
        if (previous == null) {
            ObjectNode assetNode = context.assets.addObject();
            assetNode.put("mediaId", asset.mediaId());
            if (asset.sourceExternalId() != null) {
                assetNode.put("sourceExternalId", asset.sourceExternalId());
            }
            assetNode.put("filename", asset.filename());
            assetNode.put("contentType", asset.contentType());
            assetNode.put("sizeBytes", asset.sizeBytes());
            assetNode.put("checksum", asset.checksum());
            assetNode.put("role", asset.role().documentIrValue());
        }

        ObjectNode result = simpleBlock(blockId, "image".equals(sourceType) ? "image" : "attachment");
        ObjectNode attrs = result.putObject("attrs");
        attrs.put("mediaId", asset.mediaId());
        String caption = plainText(blockBody(current).path("caption"));
        if (!caption.isBlank()) {
            attrs.put("caption", caption);
            if ("image".equals(sourceType)) {
                attrs.put("alt", caption);
            }
        }
        return result;
    }

    private ObjectNode normalizeListGroup(Context context, List<BlockAtPath> items, String sourceType, int depth) {
        String targetType = "bulleted_list_item".equals(sourceType) ? "bulletList" : "orderedList";
        String firstId = items.getFirst().block().get("id").textValue();
        ObjectNode list = simpleBlock(firstId + ":group:" + targetType, targetType);
        ArrayNode content = list.putArray("content");
        for (BlockAtPath item : items) {
            content.add(normalizeListItem(context, item, depth));
        }
        return list;
    }

    private ObjectNode normalizeListItem(Context context, BlockAtPath current, int depth) {
        String id = current.block().get("id").textValue();
        ObjectNode item = simpleBlock(id, "listItem");
        ArrayNode content = item.putArray("content");
        ObjectNode paragraph = simpleBlock(id + ":paragraph", "paragraph");
        paragraph.set("content", richText(context, current, blockType(current)));
        content.add(paragraph);
        appendChildren(context, current, content, depth);
        return item;
    }

    private ObjectNode normalizeTaskGroup(Context context, List<BlockAtPath> items, int depth) {
        String firstId = items.getFirst().block().get("id").textValue();
        ObjectNode list = simpleBlock(firstId + ":group:taskList", "taskList");
        ArrayNode content = list.putArray("content");
        for (BlockAtPath current : items) {
            String id = current.block().get("id").textValue();
            ObjectNode task = simpleBlock(id, "taskItem");
            task.putObject("attrs").put("checked", blockBody(current).path("checked").asBoolean(false));
            ArrayNode taskContent = task.putArray("content");
            ObjectNode paragraph = simpleBlock(id + ":paragraph", "paragraph");
            paragraph.set("content", richText(context, current, "to_do"));
            taskContent.add(paragraph);
            appendChildren(context, current, taskContent, depth);
            content.add(task);
        }
        return list;
    }

    private ArrayNode richText(Context context, BlockAtPath current, String bodyType) {
        JsonNode richText = blockBody(current).get("rich_text");
        String path = current.path() + "/" + pointerSegment(bodyType) + "/rich_text";
        requireArray(richText, path);
        ArrayNode content = objectMapper.createArrayNode();
        String blockId = current.block().get("id").textValue();
        for (int index = 0; index < richText.size(); index++) {
            JsonNode segment = richText.get(index);
            String segmentPath = path + "/" + index;
            requireObject(segment, segmentPath);
            String segmentType = requireText(segment.get("type"), segmentPath + "/type");
            String nodeId = blockId + ":text:" + index;
            if ("mention".equals(segmentType)) {
                content.add(mention(context, segment, nodeId, segmentPath));
                continue;
            }
            ObjectNode text = simpleBlock(nodeId, "text");
            text.put("text", richTextValue(segment, segmentType));
            ArrayNode marks = marks(context, segment, segmentPath);
            if (!marks.isEmpty()) {
                text.set("marks", marks);
            }
            if (!"text".equals(segmentType)) {
                text.putObject("attrs").put("sourceRichTextType", segmentType);
                context.issue(MigrationIssueSeverity.WARNING, "NOTION_RICH_TEXT_FALLBACK", segmentPath);
            }
            content.add(text);
        }
        return content;
    }

    private ObjectNode mention(Context context, JsonNode segment, String nodeId, String path) {
        ObjectNode mention = simpleBlock(nodeId, "mention");
        ObjectNode attrs = mention.putObject("attrs");
        attrs.put("label", segment.path("plain_text").asText(""));
        JsonNode detail = segment.path("mention");
        String mentionType = detail.path("type").asText("unknown");
        attrs.put("mentionType", mentionType);
        JsonNode target = detail.path(mentionType);
        if (target.isObject() && target.path("id").isTextual()) {
            attrs.put("externalObjectId", target.path("id").textValue());
        }
        ArrayNode marks = marks(context, segment, path);
        if (!marks.isEmpty()) {
            mention.set("marks", marks);
        }
        context.issue(MigrationIssueSeverity.INFO, "NOTION_MENTION_REQUIRES_MAPPING", path);
        return mention;
    }

    private ArrayNode marks(Context context, JsonNode segment, String path) {
        ArrayNode marks = objectMapper.createArrayNode();
        JsonNode annotations = segment.path("annotations");
        addBooleanMark(marks, annotations, "bold", "bold");
        addBooleanMark(marks, annotations, "italic", "italic");
        addBooleanMark(marks, annotations, "strikethrough", "strike");
        addBooleanMark(marks, annotations, "underline", "underline");
        addBooleanMark(marks, annotations, "code", "code");

        String color = annotations.path("color").asText("default");
        if (!"default".equals(color)) {
            ObjectNode mark = marks.addObject();
            boolean background = color.endsWith("_background");
            mark.put("type", background ? "highlight" : "textColor");
            mark.putObject("attrs").put("color", color);
        }

        String href = segment.path("href").isTextual() ? segment.path("href").textValue() : null;
        if (href == null && "text".equals(segment.path("type").asText())) {
            JsonNode link = segment.path("text").path("link");
            if (link.isObject() && link.path("url").isTextual()) {
                href = link.path("url").textValue();
            }
        }
        if (href != null && !href.isBlank()) {
            if (isSafeHref(href)) {
                ObjectNode mark = marks.addObject();
                mark.put("type", "link");
                mark.putObject("attrs").put("href", href);
            } else {
                context.issue(MigrationIssueSeverity.WARNING, "NOTION_UNSAFE_LINK_DROPPED", path);
            }
        }
        return marks;
    }

    private static void addBooleanMark(ArrayNode marks, JsonNode annotations,
                                       String sourceField, String targetType) {
        if (annotations.path(sourceField).asBoolean(false)) {
            marks.addObject().put("type", targetType);
        }
    }

    private static boolean isSafeHref(String href) {
        try {
            URI uri = new URI(href);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            return Set.of("http", "https", "mailto").contains(scheme.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private String richTextValue(JsonNode segment, String segmentType) {
        if (segment.path("plain_text").isTextual()) {
            return segment.path("plain_text").textValue();
        }
        if ("text".equals(segmentType) && segment.path("text").path("content").isTextual()) {
            return segment.path("text").path("content").textValue();
        }
        if ("equation".equals(segmentType) && segment.path("equation").path("expression").isTextual()) {
            return segment.path("equation").path("expression").textValue();
        }
        return "";
    }

    private void appendChildren(Context context, BlockAtPath current, ArrayNode target, int depth) {
        ArrayNode children = childrenIfPresent(context, current, depth);
        target.addAll(children);
    }

    private ArrayNode childrenIfPresent(Context context, BlockAtPath current, int depth) {
        if (!current.block().path("has_children").asBoolean(false)) {
            return objectMapper.createArrayNode();
        }
        return normalizeChildren(context, current.block().get("id").textValue(), depth + 1);
    }

    private ObjectNode opaque(Context context, BlockAtPath current, String issueCode,
                              MigrationIssueSeverity severity) {
        JsonNode block = current.block();
        String rawType = blockType(current);
        String sourceType = rawType;
        if ("unsupported".equals(rawType) && block.path("unsupported").path("block_type").isTextual()) {
            sourceType = block.path("unsupported").path("block_type").textValue();
        }
        if (sourceType.isBlank() || sourceType.codePointCount(0, sourceType.length()) > 255) {
            sourceType = "unsupported";
        }
        ObjectNode result = simpleBlock(block.get("id").textValue(), "opaque");
        result.putObject("attrs").put("fallbackText", "지원하지 않는 Notion 블록: " + sourceType);
        ObjectNode sourceRef = result.putObject("sourceRef");
        sourceRef.put("provider", "notion");
        sourceRef.put("objectId", block.get("id").textValue());
        sourceRef.put("sourceType", sourceType);
        sourceRef.put("path", current.path());
        sourceRef.put("checksum", checksum(block));
        context.issue(severity, issueCode, current.path());
        return result;
    }

    private JsonNode blockBody(BlockAtPath current) {
        String type = blockType(current);
        JsonNode body = current.block().get(type);
        requireObject(body, current.path() + "/" + pointerSegment(type));
        return body;
    }

    private static String blockType(BlockAtPath block) {
        return requireText(block.block().get("type"), block.path() + "/type");
    }

    private ObjectNode simpleBlock(String id, String type) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("id", id);
        result.put("type", type);
        return result;
    }

    private String pageTitle(JsonNode page) {
        JsonNode properties = page.get("properties");
        requireObject(properties, "/page/properties");
        Iterator<JsonNode> values = properties.elements();
        while (values.hasNext()) {
            JsonNode property = values.next();
            if (property.isObject() && "title".equals(property.path("type").asText())) {
                JsonNode title = property.get("title");
                requireArray(title, "/page/properties/*/title");
                return plainText(title);
            }
        }
        throw failure(PAGE_TITLE_MISSING, "/page/properties");
    }

    private static String plainText(JsonNode richText) {
        if (!richText.isArray()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        richText.forEach(segment -> {
            if (segment.path("plain_text").isTextual()) {
                result.append(segment.path("plain_text").textValue());
            } else if (segment.path("text").path("content").isTextual()) {
                result.append(segment.path("text").path("content").textValue());
            }
        });
        return result.toString();
    }

    private String checksum(JsonNode node) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(objectMapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Notion block cannot be serialized", exception);
        }
    }

    private static boolean isListItem(String type) {
        return "bulleted_list_item".equals(type) || "numbered_list_item".equals(type);
    }

    private static String childrenPath(String parentId) {
        return "/blockChildren/" + pointerSegment(parentId);
    }

    private static String pointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String path) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw failure(INVALID_SNAPSHOT, childPath(path, "*"));
            }
        }
    }

    private static void requireFields(JsonNode node, Set<String> required, String path) {
        for (String field : required.stream().sorted().toList()) {
            if (!node.has(field)) {
                throw failure(INVALID_SNAPSHOT, childPath(path, field));
            }
        }
    }

    private static void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw failure(INVALID_SNAPSHOT, path);
        }
    }

    private static void requireArray(JsonNode node, String path) {
        if (node == null || !node.isArray()) {
            throw failure(INVALID_SNAPSHOT, path);
        }
    }

    private static String requireText(JsonNode node, String path) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw failure(INVALID_SNAPSHOT, path);
        }
        return node.textValue();
    }

    private static String childPath(String parent, String child) {
        return "/".equals(parent) ? "/" + child : parent + "/" + child;
    }

    private static NotionNormalizationException failure(NotionNormalizationCode code, String path) {
        return new NotionNormalizationException(code, path);
    }

    private record BlockAtPath(JsonNode block, String path) {
    }

    private final class Context {
        private final NotionNormalizationRequest request;
        private final JsonNode childResponses;
        private final ArrayNode assets = objectMapper.createArrayNode();
        private final List<MigrationNormalizationIssue> issues = new ArrayList<>();
        private final Map<String, ResolvedMigrationAsset> usedAssets = new HashMap<>();
        private final Set<String> activeParents = new HashSet<>();
        private int blockCount;

        private Context(NotionNormalizationRequest request, JsonNode childResponses) {
            this.request = request;
            this.childResponses = childResponses;
        }

        private void issue(MigrationIssueSeverity severity, String code, String path) {
            issues.add(new MigrationNormalizationIssue(severity, code, path));
        }
    }
}
