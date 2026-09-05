package com.platform.migrationservice.migration.confluence;

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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.platform.migrationservice.migration.confluence.ConfluenceNormalizationCode.INVALID_SNAPSHOT;
import static com.platform.migrationservice.migration.confluence.ConfluenceNormalizationCode.INVALID_STORAGE_XML;
import static com.platform.migrationservice.migration.confluence.ConfluenceNormalizationCode.PAGE_TITLE_MISSING;
import static com.platform.migrationservice.migration.confluence.ConfluenceNormalizationCode.UNSAFE_STORAGE_XML;
import static com.platform.migrationservice.migration.confluence.ConfluenceNormalizationCode.UNSUPPORTED_SNAPSHOT_VERSION;

/**
 * Confluence Data Center storage XHTML/XML의 버전 공통 부분집합을 Document IR v1로 변환한다.
 * 특정 DC 버전 지원을 보장하지 않으며 custom macro와 알 수 없는 element는 opaque로 보존한다.
 */
@Component
public final class ConfluenceStorageNormalizer {

    public static final int SNAPSHOT_VERSION = 1;

    private static final String AC_NS = "http://atlassian.com/content";
    private static final String RI_NS = "http://atlassian.com/resource/identifier";
    private static final int MAX_STORAGE_CHARS = 10_000_000;
    private static final int MAX_XML_DEPTH = 128;
    private static final int MAX_XML_NODES = 100_000;
    private static final Set<String> SNAPSHOT_FIELDS = Set.of("snapshotVersion", "content");
    private static final Set<String> PANEL_MACROS = Set.of("info", "note", "tip", "warning", "panel");
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre",
            "ul", "ol", "table", "hr", "div");

    private final ObjectMapper objectMapper;
    private final DocumentIrValidator documentIrValidator;

    public ConfluenceStorageNormalizer(ObjectMapper objectMapper, DocumentIrValidator documentIrValidator) {
        this.objectMapper = objectMapper;
        this.documentIrValidator = documentIrValidator;
    }

    public ConfluenceNormalizationResult normalize(ConfluenceNormalizationRequest request) {
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

        JsonNode content = snapshot.get("content");
        requireObject(content, "/content");
        String contentId = requireText(content.get("id"), "/content/id");
        String title = requireText(content.get("title"), "/content/title", PAGE_TITLE_MISSING);
        JsonNode version = content.path("version").path("number");
        if (!version.isIntegralNumber() || version.asLong() < 1) {
            throw failure(INVALID_SNAPSHOT, "/content/version/number");
        }
        String spaceKey = content.path("space").path("key").asText("");
        JsonNode storage = content.path("body").path("storage");
        requireObject(storage, "/content/body/storage");
        if (!"storage".equals(requireText(storage.get("representation"),
                "/content/body/storage/representation"))) {
            throw failure(INVALID_SNAPSHOT, "/content/body/storage/representation");
        }
        String storageValue = requireTextAllowEmpty(storage.get("value"), "/content/body/storage/value");
        Document xml = parseStorage(storageValue);
        validateDomLimits(xml.getDocumentElement());

        Context context = new Context(request, contentId, spaceKey);
        ArrayNode rootContent = normalizeBlockChildren(context, xml.getDocumentElement(), "/storage");

        ObjectNode document = objectMapper.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("documentId", "confluence-dc:" + MigrationObjectMapping.sourceKeyFor(
                MigrationProvider.CONFLUENCE_DC, request.sourceInstanceId(), contentId));
        document.put("title", title);
        ObjectNode source = document.putObject("source");
        source.put("provider", "confluence-dc");
        source.put("instanceId", request.sourceInstanceId());
        source.put("objectId", contentId);
        source.put("sourceVersion", version.asText());
        if (request.capturedAt() == null) {
            throw failure(INVALID_SNAPSHOT, "/$metadata/capturedAt");
        }
        source.put("capturedAt", request.capturedAt().toString());
        source.put("checksum", request.sourceChecksum());
        source.put("payloadRef", request.payloadRef());
        document.set("assets", context.assets);
        ObjectNode root = document.putObject("root");
        root.put("id", "confluence:" + contentId + ":root");
        root.put("type", "doc");
        root.set("content", rootContent);

        documentIrValidator.validate(document);
        return new ConfluenceNormalizationResult(document, context.issues);
    }

    private ArrayNode normalizeBlockChildren(Context context, Element parent, String parentPath) {
        ArrayNode result = objectMapper.createArrayNode();
        for (NodeAtPath child : significantChildren(parent, parentPath)) {
            if (child.node() instanceof Element element) {
                result.add(normalizeBlock(context, element, child.path()));
            } else {
                String value = normalizedText(child.node().getNodeValue());
                if (!value.isBlank()) {
                    ObjectNode paragraph = block(context, child.path() + "/paragraph", "paragraph");
                    ArrayNode inline = paragraph.putArray("content");
                    inline.add(textBlock(context, child.path(), value, List.of()));
                    result.add(paragraph);
                }
            }
        }
        return result;
    }

    private ObjectNode normalizeBlock(Context context, Element element, String path) {
        String local = localName(element);
        if (isAc(element, "structured-macro")) {
            return structuredMacro(context, element, path);
        }
        if (isAc(element, "task-list")) {
            return taskList(context, element, path);
        }
        if (isAc(element, "layout")) {
            return layout(context, element, path);
        }
        if (isAc(element, "image")) {
            return media(context, element, path, true);
        }
        if (isAc(element, "link")) {
            return confluenceLink(context, element, path);
        }
        return switch (local) {
            case "p" -> paragraph(context, element, path);
            case "h1", "h2", "h3", "h4", "h5", "h6" -> heading(context, element, path);
            case "blockquote" -> blockquote(context, element, path);
            case "pre" -> preformatted(context, element, path);
            case "ul" -> list(context, element, path, false);
            case "ol" -> list(context, element, path, true);
            case "table" -> table(context, element, path);
            case "hr" -> block(context, path, "horizontalRule");
            default -> opaque(context, element, path, "CONFLUENCE_UNSUPPORTED_ELEMENT",
                    MigrationIssueSeverity.WARNING);
        };
    }

    private ObjectNode paragraph(Context context, Element element, String path) {
        ObjectNode paragraph = block(context, path, "paragraph");
        paragraph.set("content", inlineChildren(context, element, path, List.of()));
        return paragraph;
    }

    private ObjectNode heading(Context context, Element element, String path) {
        ObjectNode heading = block(context, path, "heading");
        heading.putObject("attrs").put("level", Integer.parseInt(localName(element).substring(1)));
        heading.set("content", inlineChildren(context, element, path, List.of()));
        return heading;
    }

    private ObjectNode blockquote(Context context, Element element, String path) {
        ObjectNode quote = block(context, path, "blockquote");
        quote.set("content", mixedChildren(context, element, path));
        return quote;
    }

    private ObjectNode preformatted(Context context, Element element, String path) {
        ObjectNode code = block(context, path, "codeBlock");
        ArrayNode content = code.putArray("content");
        content.add(textBlock(context, path + "/text()[1]", element.getTextContent(), List.of(mark("code"))));
        return code;
    }

    private ObjectNode list(Context context, Element element, String path, boolean ordered) {
        ObjectNode list = block(context, path, ordered ? "orderedList" : "bulletList");
        ArrayNode items = list.putArray("content");
        int index = 0;
        for (NodeAtPath child : significantChildren(element, path)) {
            if (child.node() instanceof Element itemElement && "li".equals(localName(itemElement))) {
                index += 1;
                ObjectNode item = block(context, child.path(), "listItem");
                item.set("content", mixedChildren(context, itemElement, child.path()));
                items.add(item);
            }
        }
        if (index == 0) {
            context.issue(MigrationIssueSeverity.WARNING, "CONFLUENCE_EMPTY_LIST", path);
        }
        return list;
    }

    private ObjectNode taskList(Context context, Element element, String path) {
        ObjectNode list = block(context, path, "taskList");
        ArrayNode content = list.putArray("content");
        for (NodeAtPath child : significantChildren(element, path)) {
            if (!(child.node() instanceof Element task) || !isAc(task, "task")) {
                continue;
            }
            ObjectNode taskItem = block(context, child.path(), "taskItem");
            String status = childText(task, AC_NS, "task-status");
            taskItem.putObject("attrs").put("checked", "complete".equalsIgnoreCase(status));
            if (!status.isBlank() && !"complete".equalsIgnoreCase(status)
                    && !"incomplete".equalsIgnoreCase(status)) {
                context.issue(MigrationIssueSeverity.WARNING, "CONFLUENCE_TASK_STATUS_FALLBACK", child.path());
            }
            Element body = firstChild(task, AC_NS, "task-body");
            taskItem.set("content", body == null
                    ? objectMapper.createArrayNode()
                    : mixedChildren(context, body, child.path() + "/ac:task-body[1]"));
            content.add(taskItem);
        }
        return list;
    }

    private ObjectNode table(Context context, Element table, String path) {
        ObjectNode result = block(context, path, "table");
        ArrayNode rows = result.putArray("content");
        appendTableRows(context, table, path, rows);
        return result;
    }

    private void appendTableRows(Context context, Element parent, String path, ArrayNode rows) {
        for (NodeAtPath child : significantChildren(parent, path)) {
            if (!(child.node() instanceof Element element)) {
                continue;
            }
            String local = localName(element);
            if ("thead".equals(local) || "tbody".equals(local) || "tfoot".equals(local)) {
                appendTableRows(context, element, child.path(), rows);
                continue;
            }
            if (!"tr".equals(local)) {
                continue;
            }
            ObjectNode row = block(context, child.path(), "tableRow");
            ArrayNode cells = row.putArray("content");
            for (NodeAtPath cellNode : significantChildren(element, child.path())) {
                if (cellNode.node() instanceof Element cell
                        && ("th".equals(localName(cell)) || "td".equals(localName(cell)))) {
                    ObjectNode normalizedCell = block(context, cellNode.path(),
                            "th".equals(localName(cell)) ? "tableHeader" : "tableCell");
                    ObjectNode cellAttrs = null;
                    Integer colSpan = positiveIntegerAttribute(cell, "colspan");
                    Integer rowSpan = positiveIntegerAttribute(cell, "rowspan");
                    if (colSpan != null || rowSpan != null) {
                        cellAttrs = normalizedCell.putObject("attrs");
                    }
                    if (colSpan != null) {
                        cellAttrs.put("colSpan", colSpan);
                    }
                    if (rowSpan != null) {
                        cellAttrs.put("rowSpan", rowSpan);
                    }
                    normalizedCell.set("content", mixedChildren(context, cell, cellNode.path()));
                    cells.add(normalizedCell);
                }
            }
            rows.add(row);
        }
    }

    private ObjectNode structuredMacro(Context context, Element macro, String path) {
        String name = attribute(macro, AC_NS, "name");
        if (PANEL_MACROS.contains(name)) {
            ObjectNode panel = block(context, path, "panel");
            ObjectNode attrs = panel.putObject("attrs");
            attrs.put("variant", name);
            String title = macroParameter(macro, "title");
            if (!title.isBlank()) {
                attrs.put("title", title);
            }
            Element body = firstChild(macro, AC_NS, "rich-text-body");
            panel.set("content", body == null
                    ? objectMapper.createArrayNode()
                    : normalizeBlockChildren(context, body, path + "/ac:rich-text-body[1]"));
            return panel;
        }
        if ("code".equals(name)) {
            ObjectNode code = block(context, path, "codeBlock");
            String language = macroParameter(macro, "language");
            if (!language.isBlank()) {
                code.putObject("attrs").put("language", language);
            }
            Element body = firstChild(macro, AC_NS, "plain-text-body");
            ArrayNode content = code.putArray("content");
            content.add(textBlock(context, path + "/ac:plain-text-body[1]",
                    body == null ? "" : body.getTextContent(), List.of(mark("code"))));
            return code;
        }
        return opaque(context, macro, path, "CONFLUENCE_UNSUPPORTED_MACRO", MigrationIssueSeverity.WARNING);
    }

    private ObjectNode layout(Context context, Element layout, String path) {
        ObjectNode columns = block(context, path, "columns");
        ArrayNode content = columns.putArray("content");
        NodeList cells = layout.getElementsByTagNameNS(AC_NS, "layout-cell");
        for (int index = 0; index < cells.getLength(); index++) {
            Element cell = (Element) cells.item(index);
            String cellPath = path + "/ac:layout-cell[" + (index + 1) + "]";
            ObjectNode column = block(context, cellPath, "column");
            column.set("content", normalizeBlockChildren(context, cell, cellPath));
            content.add(column);
        }
        return columns;
    }

    private ObjectNode confluenceLink(Context context, Element link, String path) {
        Element page = firstChild(link, RI_NS, "page");
        if (page != null) {
            String title = attribute(page, RI_NS, "content-title");
            String space = attribute(page, RI_NS, "space-key");
            if (space.isBlank()) {
                space = context.spaceKey;
            }
            if (!title.isBlank()) {
                ObjectNode result = block(context, path, "pageLink");
                ObjectNode attrs = result.putObject("attrs");
                String label = linkLabel(link);
                attrs.put("label", label.isBlank() ? title : label);
                ObjectNode target = attrs.putObject("target");
                target.put("externalObjectId", space.isBlank() ? title : space + ":" + title);
                String anchor = attribute(link, AC_NS, "anchor");
                if (!anchor.isBlank()) {
                    target.put("anchor", anchor);
                }
                return result;
            }
        }
        Element attachment = firstChild(link, RI_NS, "attachment");
        if (attachment != null) {
            return resolvedAttachment(context, attachment, path, linkLabel(link), false);
        }
        String anchor = attribute(link, AC_NS, "anchor");
        if (!anchor.isBlank()) {
            ObjectNode result = block(context, path, "pageLink");
            result.putObject("attrs").putObject("target").put("href", "#" + anchor);
            return result;
        }
        return opaque(context, link, path, "CONFLUENCE_UNSUPPORTED_LINK", MigrationIssueSeverity.WARNING);
    }

    private ObjectNode media(Context context, Element image, String path, boolean inlineImage) {
        Element attachment = firstChild(image, RI_NS, "attachment");
        if (attachment != null) {
            ObjectNode result = resolvedAttachment(context, attachment, path,
                    attribute(image, AC_NS, "alt"), inlineImage);
            if (inlineImage && "image".equals(result.path("type").asText())) {
                ObjectNode attrs = (ObjectNode) result.path("attrs");
                copyPositiveIntegerAttribute(image, AC_NS, "width", attrs, "width");
                copyPositiveIntegerAttribute(image, AC_NS, "height", attrs, "height");
            }
            return result;
        }
        Element url = firstChild(image, RI_NS, "url");
        if (url != null) {
            String value = attribute(url, RI_NS, "value");
            ResolvedMigrationAsset asset = value.isBlank() ? null
                    : context.request.resolvedAssetsByReference().get(ConfluenceMediaReference.externalUrl(value));
            return resolvedMedia(context, image, path, asset, inlineImage, attribute(image, AC_NS, "alt"));
        }
        return opaque(context, image, path, "CONFLUENCE_MEDIA_NOT_COPIED", MigrationIssueSeverity.ERROR);
    }

    private ObjectNode resolvedAttachment(Context context, Element attachment, String path,
                                          String label, boolean inlineImage) {
        String filename = attribute(attachment, RI_NS, "filename");
        ResolvedMigrationAsset asset = filename.isBlank() ? null
                : context.request.resolvedAssetsByReference().get(ConfluenceMediaReference.attachment(filename));
        return resolvedMedia(context, attachment, path, asset, inlineImage, label);
    }

    private ObjectNode resolvedMedia(Context context, Element source, String path,
                                     ResolvedMigrationAsset asset, boolean inlineImage, String label) {
        if (asset == null) {
            return opaque(context, source, path, "CONFLUENCE_MEDIA_NOT_COPIED", MigrationIssueSeverity.ERROR);
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
        ObjectNode result = block(context, path, inlineImage ? "image" : "attachment");
        ObjectNode attrs = result.putObject("attrs");
        attrs.put("mediaId", asset.mediaId());
        if (label != null && !label.isBlank()) {
            attrs.put(inlineImage ? "alt" : "label", label);
        }
        if (inlineImage) {
            copyPositiveIntegerAttribute(source, AC_NS, "width", attrs, "width");
            copyPositiveIntegerAttribute(source, AC_NS, "height", attrs, "height");
        }
        return result;
    }

    private ArrayNode mixedChildren(Context context, Element parent, String path) {
        ArrayNode result = objectMapper.createArrayNode();
        ArrayNode pendingInline = objectMapper.createArrayNode();
        int paragraphIndex = 0;
        for (NodeAtPath child : significantChildren(parent, path)) {
            if (child.node() instanceof Element element && isBlockElement(element)) {
                if (!pendingInline.isEmpty()) {
                    paragraphIndex += 1;
                    ObjectNode paragraph = block(context, path + "/paragraph[" + paragraphIndex + "]", "paragraph");
                    paragraph.set("content", pendingInline);
                    result.add(paragraph);
                    pendingInline = objectMapper.createArrayNode();
                }
                result.add(normalizeBlock(context, element, child.path()));
            } else {
                appendInline(context, child.node(), child.path(), List.of(), pendingInline);
            }
        }
        if (!pendingInline.isEmpty()) {
            paragraphIndex += 1;
            ObjectNode paragraph = block(context, path + "/paragraph[" + paragraphIndex + "]", "paragraph");
            paragraph.set("content", pendingInline);
            result.add(paragraph);
        }
        return result;
    }

    private ArrayNode inlineChildren(Context context, Element parent, String path, List<Mark> inherited) {
        ArrayNode result = objectMapper.createArrayNode();
        for (NodeAtPath child : significantChildren(parent, path)) {
            appendInline(context, child.node(), child.path(), inherited, result);
        }
        return result;
    }

    private void appendInline(Context context, Node node, String path, List<Mark> inherited, ArrayNode target) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String value = normalizedText(node.getNodeValue());
            if (!value.isBlank()) {
                target.add(textBlock(context, path, value, inherited));
            }
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        String local = localName(element);
        if (isAc(element, "link")) {
            target.add(confluenceLink(context, element, path));
            return;
        }
        if (isAc(element, "image")) {
            target.add(media(context, element, path, true));
            return;
        }
        if (isAc(element, "emoticon")) {
            String name = attribute(element, AC_NS, "name");
            target.add(textBlock(context, path, name.isBlank() ? "" : ":" + name + ":", inherited));
            return;
        }
        if (isAc(element, "structured-macro")) {
            target.add(structuredMacro(context, element, path));
            return;
        }
        if ("br".equals(local)) {
            target.add(block(context, path, "hardBreak"));
            return;
        }

        List<Mark> marks = new ArrayList<>(inherited);
        switch (local) {
            case "b", "strong" -> marks.add(mark("bold"));
            case "em", "i" -> marks.add(mark("italic"));
            case "s", "strike", "del" -> marks.add(mark("strike"));
            case "u" -> marks.add(mark("underline"));
            case "code", "tt" -> marks.add(mark("code"));
            case "a" -> {
                String href = element.getAttribute("href");
                if (isSafeHref(href)) {
                    marks.add(new Mark("link", "href", href));
                } else if (!href.isBlank()) {
                    context.issue(MigrationIssueSeverity.WARNING, "CONFLUENCE_UNSAFE_LINK_DROPPED", path);
                }
            }
            case "span" -> {
                // provider CSS는 실행하지 않고 텍스트만 보존한다.
                if (!element.getAttribute("style").isBlank()) {
                    context.issue(MigrationIssueSeverity.WARNING, "CONFLUENCE_INLINE_STYLE_DROPPED", path);
                }
            }
            case "sub", "sup" -> {
                context.issue(MigrationIssueSeverity.WARNING, "CONFLUENCE_INLINE_FORMAT_FALLBACK", path);
            }
            default -> {
                target.add(opaque(context, element, path, "CONFLUENCE_UNSUPPORTED_INLINE",
                        MigrationIssueSeverity.WARNING));
                return;
            }
        }
        target.addAll(inlineChildren(context, element, path, marks));
    }

    private ObjectNode textBlock(Context context, String path, String value, List<Mark> marks) {
        ObjectNode text = block(context, path, "text");
        text.put("text", value);
        if (!marks.isEmpty()) {
            ArrayNode markNodes = text.putArray("marks");
            for (Mark mark : marks) {
                ObjectNode markNode = markNodes.addObject();
                markNode.put("type", mark.type());
                if (mark.attributeName() != null) {
                    markNode.putObject("attrs").put(mark.attributeName(), mark.attributeValue());
                }
            }
        }
        return text;
    }

    private ObjectNode opaque(Context context, Element element, String path, String issueCode,
                              MigrationIssueSeverity severity) {
        String sourceType = qualifiedName(element);
        if (isAc(element, "structured-macro")) {
            String name = attribute(element, AC_NS, "name");
            sourceType = "ac:structured-macro[" + (name.isBlank() ? "unknown" : name) + "]";
        }
        if (sourceType.codePointCount(0, sourceType.length()) > 255) {
            sourceType = "unsupported";
        }
        ObjectNode opaque = block(context, path, "opaque");
        opaque.putObject("attrs").put("fallbackText", "지원하지 않는 Confluence 요소: " + sourceType);
        ObjectNode sourceRef = opaque.putObject("sourceRef");
        sourceRef.put("provider", "confluence-dc");
        sourceRef.put("objectId", context.contentId);
        sourceRef.put("sourceType", sourceType);
        sourceRef.put("path", safeSourcePath(path));
        sourceRef.put("checksum", context.request.sourceChecksum());
        context.issue(severity, issueCode, path);
        return opaque;
    }

    private ObjectNode block(Context context, String path, String type) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("id", "confluence:" + context.contentId + ":" + sha256(path + "|" + type).substring(0, 20));
        block.put("type", type);
        return block;
    }

    private Document parseStorage(String storage) {
        if (storage.length() > MAX_STORAGE_CHARS) {
            throw failure(INVALID_STORAGE_XML, "/content/body/storage/value");
        }
        String lowered = storage.toLowerCase(Locale.ROOT);
        if (lowered.contains("<!doctype") || lowered.contains("<!entity") || lowered.contains("<?")) {
            throw failure(UNSAFE_STORAGE_XML, "/content/body/storage/value");
        }
        String wrapped = "<migration-root xmlns:ac=\"" + AC_NS + "\" xmlns:ri=\"" + RI_NS + "\">"
                + storage + "</migration-root>";
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void warning(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return builder.parse(new InputSource(new StringReader(wrapped)));
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException("Secure XML parser is unavailable", exception);
        } catch (SAXException | IOException exception) {
            throw failure(INVALID_STORAGE_XML, "/content/body/storage/value");
        }
    }

    private static void validateDomLimits(Element root) {
        Deque<NodeDepth> remaining = new ArrayDeque<>();
        remaining.push(new NodeDepth(root, 0));
        int nodes = 0;
        while (!remaining.isEmpty()) {
            NodeDepth current = remaining.pop();
            nodes += 1;
            if (nodes > MAX_XML_NODES || current.depth() > MAX_XML_DEPTH) {
                throw failure(INVALID_STORAGE_XML, "/content/body/storage/value");
            }
            NodeList children = current.node().getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                remaining.push(new NodeDepth(children.item(index), current.depth() + 1));
            }
        }
    }

    private static List<NodeAtPath> significantChildren(Element parent, String parentPath) {
        List<NodeAtPath> result = new ArrayList<>();
        Map<String, Integer> elementCounts = new HashMap<>();
        int textCount = 0;
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element) {
                String name = qualifiedName(element);
                int count = elementCounts.merge(name, 1, Integer::sum);
                result.add(new NodeAtPath(child, parentPath + "/" + name + "[" + count + "]"));
            } else if ((child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !normalizedText(child.getNodeValue()).isBlank()) {
                textCount += 1;
                result.add(new NodeAtPath(child, parentPath + "/text()[" + textCount + "]"));
            }
        }
        return result;
    }

    private static boolean isBlockElement(Element element) {
        return BLOCK_TAGS.contains(localName(element))
                || isAc(element, "structured-macro")
                || isAc(element, "task-list")
                || isAc(element, "layout");
    }

    private static String linkLabel(Element link) {
        Element plain = firstChild(link, AC_NS, "plain-text-link-body");
        if (plain != null) {
            return plain.getTextContent();
        }
        Element rich = firstChild(link, AC_NS, "link-body");
        return rich == null ? "" : rich.getTextContent();
    }

    private static String macroParameter(Element macro, String parameterName) {
        NodeList parameters = macro.getElementsByTagNameNS(AC_NS, "parameter");
        for (int index = 0; index < parameters.getLength(); index++) {
            Element parameter = (Element) parameters.item(index);
            if (parameterName.equals(attribute(parameter, AC_NS, "name"))) {
                return parameter.getTextContent();
            }
        }
        return "";
    }

    private static Integer positiveIntegerAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void copyPositiveIntegerAttribute(Element element, String namespace, String sourceName,
                                                     ObjectNode target, String targetName) {
        String value = attribute(element, namespace, sourceName);
        if (value.isBlank()) {
            value = element.getAttribute(sourceName);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                target.put(targetName, parsed);
            }
        } catch (NumberFormatException ignored) {
            // 지원하지 않는 크기 표현은 renderer에 전달하지 않는다.
        }
    }

    private static Element firstChild(Element parent, String namespace, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element element
                    && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String namespace, String localName) {
        Element child = firstChild(parent, namespace, localName);
        return child == null ? "" : child.getTextContent();
    }

    private static boolean isAc(Element element, String localName) {
        return AC_NS.equals(element.getNamespaceURI()) && localName.equals(element.getLocalName());
    }

    private static String attribute(Element element, String namespace, String localName) {
        return element.hasAttributeNS(namespace, localName) ? element.getAttributeNS(namespace, localName) : "";
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }

    private static String qualifiedName(Element element) {
        String prefix = element.getPrefix();
        return prefix == null || prefix.isBlank() ? localName(element) : prefix + ":" + localName(element);
    }

    private static String normalizedText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ");
    }

    private static boolean isSafeHref(String href) {
        if (href == null || href.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(href);
            String scheme = uri.getScheme();
            return scheme != null && Set.of("http", "https", "mailto")
                    .contains(scheme.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static Mark mark(String type) {
        return new Mark(type, null, null);
    }

    private static String safeSourcePath(String path) {
        return path.codePointCount(0, path.length()) <= 1024 ? path : "/storage/#" + sha256(path);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw failure(INVALID_SNAPSHOT, path);
        }
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String path) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw failure(INVALID_SNAPSHOT, path + ("/".equals(path) ? "*" : "/*"));
            }
        });
    }

    private static void requireFields(JsonNode node, Set<String> required, String path) {
        for (String field : required.stream().sorted().toList()) {
            if (!node.has(field)) {
                throw failure(INVALID_SNAPSHOT, "/".equals(path) ? "/" + field : path + "/" + field);
            }
        }
    }

    private static String requireText(JsonNode node, String path) {
        return requireText(node, path, INVALID_SNAPSHOT);
    }

    private static String requireText(JsonNode node, String path, ConfluenceNormalizationCode code) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw failure(code, path);
        }
        return node.textValue();
    }

    private static String requireTextAllowEmpty(JsonNode node, String path) {
        if (node == null || !node.isTextual()) {
            throw failure(INVALID_SNAPSHOT, path);
        }
        return node.textValue();
    }

    private static ConfluenceNormalizationException failure(ConfluenceNormalizationCode code, String path) {
        return new ConfluenceNormalizationException(code, path);
    }

    private record NodeAtPath(Node node, String path) {
    }

    private record NodeDepth(Node node, int depth) {
    }

    private record Mark(String type, String attributeName, String attributeValue) {
    }

    private final class Context {
        private final ConfluenceNormalizationRequest request;
        private final String contentId;
        private final String spaceKey;
        private final ArrayNode assets = objectMapper.createArrayNode();
        private final List<MigrationNormalizationIssue> issues = new ArrayList<>();
        private final Map<String, ResolvedMigrationAsset> usedAssets = new HashMap<>();

        private Context(ConfluenceNormalizationRequest request, String contentId, String spaceKey) {
            this.request = request;
            this.contentId = contentId;
            this.spaceKey = spaceKey;
        }

        private void issue(MigrationIssueSeverity severity, String code, String path) {
            issues.add(new MigrationNormalizationIssue(severity, code, safeSourcePath(path)));
        }
    }
}
