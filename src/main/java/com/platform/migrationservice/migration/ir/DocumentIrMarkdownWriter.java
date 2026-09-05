package com.platform.migrationservice.migration.ir;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Document IR v1 → 우리 위키의 마크다운 방언.
 *
 * 방언의 정본은 wiki-front다: `editor/markdown.test.ts`(왕복 케이스), `lib/remark*.ts`(보기 경로),
 * `editor/extensions/{textColors,wikiLink,columns}.ts`(저장 문법의 근거 주석). 여기서 만든 문자열은
 * 그 파이프라인의 **고정점**이어야 한다 — `parseMarkdown → serializeMarkdown`을 통과해도 글자가
 * 그대로여야 편집기에 한 번 들어갔다 나온 문서가 diff로 통째로 바뀌지 않는다. 그래서 이 클래스는
 * "보기 좋은 마크다운"이 아니라 tiptap-markdown 직렬화기가 내놓는 형태를 그대로 흉내 낸다:
 * 대괄호 이스케이프, 태스크 목록의 빈 줄, 순서 목록의 들여쓰기 폭까지.
 *
 * 표현할 수 없는 것은 지우지 않고 남기거나 경고로 보고한다 — 본문에서 조용히 사라지는 것이
 * 이관에서 가장 나쁜 실패다.
 */
@Component
public class DocumentIrMarkdownWriter {

    /** 팔레트 밖 색·밑줄처럼 우리 문법에 자리가 없는 마크를 떼어냈다. */
    public static final String MARK_DROPPED = "MARK_DROPPED";

    /** 병합 셀을 마커로 펼 수 없어 평범한 셀로 눕혔다. */
    public static final String TABLE_SPAN_DROPPED = "TABLE_SPAN_DROPPED";

    /** 원본 매크로를 실체화하지 못하고 안내 패널로 남겼다. */
    public static final String MACRO_OPAQUE = "MACRO_OPAQUE";

    /** 다른 스페이스로 가는 링크라 `[[제목]]`으로 열 수 없어 원본 URL을 남겼다. */
    public static final String LINK_EXTERNAL_SPACE = "LINK_EXTERNAL_SPACE";

    /** `[[제목]]`에는 앵커를 실을 수 없어 문서 첫머리로 간다. */
    public static final String LINK_ANCHOR_DROPPED = "LINK_ANCHOR_DROPPED";

    /** 선언되지 않은 자산을 가리키는 이미지·첨부. 파일 이름조차 알 수 없다. */
    public static final String MEDIA_UNRESOLVED = "MEDIA_UNRESOLVED";

    /** wiki-front `editor/extensions/textColors.ts`의 TEXT_COLORS와 같은 목록이어야 한다. */
    private static final Set<String> TEXT_COLORS = Set.of(
            "gray", "red", "orange", "yellow", "green", "teal", "blue", "purple", "magenta");

    /** 같은 파일의 BG_COLORS. 지금은 값이 같지만 팔레트가 갈라질 수 있어 따로 둔다. */
    private static final Set<String> BG_COLORS = Set.of(
            "gray", "red", "orange", "yellow", "green", "teal", "blue", "purple", "magenta");

    /** `lib/remarkAlerts.ts`의 마커 ↔ IR panel variant. 컨플루언스 패널 5종이 여기로 접힌다. */
    private static final Map<String, String> PANEL_ALERTS = Map.of(
            "info", "NOTE",
            "note", "NOTE",
            "tip", "TIP",
            "success", "TIP",
            "warning", "WARNING",
            "panel", "NOTE",
            "error", "CAUTION",
            "caution", "CAUTION",
            "important", "IMPORTANT");

    /**
     * 열 병합으로 덮인 자리를 채우는 마커(`lib/tableSpans.ts`의 COLSPAN_MARKER).
     *
     * 원문은 `<<`인데 HTML 엔티티로 쓴다. tiptap-markdown이 `<`를 엔티티로 직렬화해서
     * **엔티티 형태가 왕복의 고정점**이기 때문이다(`tableSpanBridge.test.ts`가 그 형태를 고정하고
     * 있다). 보기 경로의 remark는 파싱 시점에 엔티티를 되돌리므로 병합 인식에는 영향이 없다 —
     * alert 마커의 대괄호 이스케이프와 같은 사정이다.
     */
    static final String COLSPAN_MARKER = "&lt;&lt;";

    /** 행 병합 마커. `^`는 이스케이프 대상이 아니라 원문 그대로다. */
    static final String ROWSPAN_MARKER = "^^";

    /**
     * 우리 에디터가 렌더하는 제목 깊이. 그보다 깊은 원본 제목은 여기로 눕는다.
     *
     * 3인 이유는 wiki-front `editor/extensions/base.ts`가 StarterKit을 `levels: [1, 2, 3]`으로
     * 구성하기 때문이다 — `####`를 쓰면 편집기가 제목으로 읽지 못하고 평범한 문단으로 눕혀서,
     * 이관된 문서를 한 번만 편집해도 제목 계층이 사라진다.
     */
    private static final int MAX_HEADING_LEVEL = 3;

    public DocumentIrMarkdownResult write(JsonNode document, DocumentIrMarkdownContext context) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("documentIr is required");
        }
        Writer writer = new Writer(document, context == null ? DocumentIrMarkdownContext.none() : context);
        String markdown = writer.render(document.path("root"));
        return new DocumentIrMarkdownResult(markdown, writer.issues);
    }

    /** 한 문서를 쓰는 동안의 상태 — 자산 색인과 손실 목록. */
    private static final class Writer {

        private final DocumentIrMarkdownContext context;
        private final Map<String, JsonNode> assetsByMediaId = new HashMap<>();
        private final List<MigrationStageIssue> issues = new ArrayList<>();

        private Writer(JsonNode document, DocumentIrMarkdownContext context) {
            this.context = context;
            for (JsonNode asset : document.path("assets")) {
                String mediaId = asset.path("mediaId").asText(null);
                if (mediaId != null) {
                    assetsByMediaId.put(mediaId, asset);
                }
            }
        }

        private String render(JsonNode root) {
            return String.join("\n\n", blocks(root.path("content")));
        }

        /** 블록 목록 → 블록마다 하나의 덩어리 문자열. 빈 덩어리는 빈 줄만 남기므로 버린다. */
        private List<String> blocks(JsonNode content) {
            List<String> out = new ArrayList<>();
            for (JsonNode block : content) {
                String rendered = block(block);
                if (!rendered.isEmpty()) {
                    out.add(rendered);
                }
            }
            return out;
        }

        private String block(JsonNode node) {
            String type = node.path("type").asText("");
            return switch (type) {
                case "paragraph" -> inline(node.path("content"), true);
                case "heading" -> heading(node);
                case "blockquote" -> quote(String.join("\n\n", blocks(node.path("content"))));
                case "codeBlock" -> codeBlock(node);
                case "bulletList" -> list(node, false);
                case "orderedList" -> list(node, true);
                case "taskList" -> taskList(node);
                case "table" -> table(node);
                case "horizontalRule" -> "---";
                case "panel" -> panel(node);
                case "columns" -> columns(node);
                case "image" -> image(node);
                case "attachment" -> attachment(node);
                case "opaque" -> opaque(node);
                // 인라인 노드가 블록 자리에 온 IR(정규화기가 문단으로 감싸지 못한 경우)은
                // 문단 하나로 취급한다 — 타입을 모른다고 내용을 버리지 않는다.
                default -> inline(singleton(node), true);
            };
        }

        private String heading(JsonNode node) {
            int level = node.path("attrs").path("level").asInt(1);
            int clamped = Math.min(Math.max(level, 1), MAX_HEADING_LEVEL);
            return "#".repeat(clamped) + " " + inline(node.path("content"), false);
        }

        private String codeBlock(JsonNode node) {
            String language = node.path("attrs").path("language").asText("");
            StringBuilder body = new StringBuilder();
            collectPlainText(node.path("content"), body);
            String code = body.toString();
            while (code.endsWith("\n")) {
                code = code.substring(0, code.length() - 1);
            }
            return "```" + language + "\n" + code + "\n```";
        }

        /**
         * 글머리·번호 목록. 이어지는 줄의 들여쓰기 폭은 tiptap-markdown(prosemirror-markdown)과
         * 같게 맞춘다 — 글머리는 2칸, 번호는 가장 긴 번호 길이 + 2칸이다.
         */
        private String list(JsonNode node, boolean ordered) {
            JsonNode items = node.path("content");
            int start = node.path("attrs").path("start").asInt(1);
            int width = String.valueOf(start + Math.max(items.size() - 1, 0)).length();
            String indent = " ".repeat(ordered ? width + 2 : 2);
            List<String> lines = new ArrayList<>();
            int index = 0;
            for (JsonNode item : items) {
                String number = String.valueOf(start + index);
                String marker = ordered ? " ".repeat(width - number.length()) + number + ". " : "- ";
                lines.add(prefixFirstLine(itemBody(item.path("content")), marker, indent));
                index++;
            }
            return String.join("\n", lines);
        }

        /**
         * 목록 항목 안의 블록 이음새.
         *
         * 하위 목록 앞에는 빈 줄을 두지 않는다. 빈 줄을 두면 markdown-it이 목록 전체를 loose로
         * 읽고, 편집기를 한 번 지나면 모든 항목 사이에 빈 줄이 생겨 문서가 통째로 벌어진다
         * (markdown.test.ts "중첩 목록" 케이스가 tight 형태를 고정점으로 잡고 있다).
         */
        private String itemBody(JsonNode content) {
            StringBuilder out = new StringBuilder();
            String previous = null;
            for (JsonNode child : content) {
                String rendered = block(child);
                if (rendered.isEmpty()) {
                    continue;
                }
                if (previous != null) {
                    out.append(isList(child) ? "\n" : "\n\n");
                }
                out.append(rendered);
                previous = rendered;
            }
            return out.toString();
        }

        private static boolean isList(JsonNode node) {
            String type = node.path("type").asText("");
            return "bulletList".equals(type) || "orderedList".equals(type) || "taskList".equals(type);
        }

        /**
         * 태스크 목록은 항목 사이에 빈 줄이 들어간다(loose list). 우리가 고른 형태가 아니라
         * tiptap-markdown이 그렇게 직렬화하기 때문이며, `markdown.test.ts`의 "체크박스" 케이스가 정본이다.
         */
        private String taskList(JsonNode node) {
            List<String> lines = new ArrayList<>();
            for (JsonNode item : node.path("content")) {
                boolean checked = item.path("attrs").path("checked").asBoolean(false);
                String marker = checked ? "- [x] " : "- [ ] ";
                lines.add(prefixFirstLine(itemBody(item.path("content")), marker, "  "));
            }
            return String.join("\n\n", lines);
        }

        /**
         * 안내 패널 — GitHub Alerts 문법의 순수 blockquote다(`lib/remarkAlerts.ts`).
         *
         * 마커의 대괄호를 이스케이프하는 이유: tiptap-markdown 직렬화기가 `[`를 항상 이스케이프해서
         * 이스케이프된 형태가 왕복의 고정점이다. 보기 경로의 remark-parse는 파싱 시점에 이스케이프를
         * 되돌리므로 패널 인식에는 영향이 없다(markdown.test.ts의 alert 케이스 주석과 같은 사정).
         */
        private String panel(JsonNode node) {
            String variant = node.path("attrs").path("variant").asText("info").toLowerCase(Locale.ROOT);
            String alert = PANEL_ALERTS.getOrDefault(variant, "NOTE");
            String title = node.path("attrs").path("title").asText("");
            List<String> parts = new ArrayList<>();
            if (!title.isBlank()) {
                parts.add("**" + escape(title, false) + "**");
            }
            parts.addAll(blocks(node.path("content")));
            String body = String.join("\n\n", parts);
            String marked = body.isEmpty()
                    ? "\\[!" + alert + "\\]"
                    : "\\[!" + alert + "\\] " + body;
            return quote(marked);
        }

        private String columns(JsonNode node) {
            String outer = ":".repeat(3 + containerDepth(node));
            StringBuilder out = new StringBuilder(outer).append("columns");
            for (JsonNode column : node.path("content")) {
                String inner = ":".repeat(3 + containerDepth(column));
                out.append('\n').append(inner).append("column");
                double width = column.path("attrs").path("width").asDouble(0);
                if (width > 0 && width < 100) {
                    out.append("{width=").append(trimNumber(width)).append('}');
                }
                String body = String.join("\n\n", blocks(column.path("content")));
                if (!body.isEmpty()) {
                    out.append('\n').append(body);
                }
                out.append('\n').append(inner);
            }
            return out.append('\n').append(outer).toString();
        }

        /** 컨테이너 마커는 안쪽이 짧아야 중첩이 성립한다 — 안쪽 층수만큼 바깥을 길게 만든다. */
        private int containerDepth(JsonNode node) {
            int deepest = 0;
            for (JsonNode child : node.path("content")) {
                String type = child.path("type").asText("");
                int depth = containerDepth(child);
                if ("columns".equals(type) || "column".equals(type)) {
                    depth += 1;
                }
                deepest = Math.max(deepest, depth);
            }
            return deepest;
        }

        private String image(JsonNode node) {
            String alt = node.path("attrs").path("alt").asText("");
            String target = mediaTarget(node);
            return "![" + escapeLinkText(alt) + "](" + target + ")";
        }

        private String attachment(JsonNode node) {
            String target = mediaTarget(node);
            String label = node.path("attrs").path("label").asText("");
            String text = label.isBlank() ? target.replaceFirst("^attachment:", "") : label;
            return "[" + escapeLinkText(text) + "](" + target + ")";
        }

        /**
         * 자산 참조는 M1에서 `attachment:{파일명}`으로 남는다 — 파일 본체는 MEDIA_COPY가 아직
         * 옮기지 않으므로, 링크가 깨진 채로라도 무엇을 가리키던 참조인지가 본문에 남아야 한다.
         */
        private String mediaTarget(JsonNode node) {
            String mediaId = node.path("attrs").path("mediaId").asText("");
            JsonNode asset = assetsByMediaId.get(mediaId);
            if (asset == null) {
                issue(MEDIA_UNRESOLVED, mediaId.isBlank() ? "media:unknown" : "media:" + mediaId);
                return "attachment:" + (mediaId.isBlank() ? "unknown" : mediaId);
            }
            String external = asset.path("sourceExternalId").asText("");
            if (external.startsWith("http://") || external.startsWith("https://")) {
                return external;
            }
            return "attachment:" + asset.path("filename").asText("unknown");
        }

        /**
         * 실체화하지 못한 원본 요소. 본문에서 지우지 않고 "여기에 무엇이 있었는지"를 남긴다 —
         * 읽는 사람이 원본을 찾아갈 수 있어야 한다(기획 P4, S4).
         */
        private String opaque(JsonNode node) {
            JsonNode sourceRef = node.path("sourceRef");
            String name = sourceRef.path("sourceType").asText("unknown");
            String path = sourceRef.path("path").asText("");
            issue(MACRO_OPAQUE, path.isBlank() ? "macro:" + name : path);
            String tail = path.isBlank() ? "" : " (원본: " + escape(path, false) + ")";
            return quote("\\[!WARNING\\] 원본 매크로 `" + name + "`는 이관되지 않았습니다" + tail);
        }

        /* ── 표 ────────────────────────────────────────────────── */

        /**
         * GFM 표. 병합 셀은 `lib/tableSpans.ts`의 마커 문법(`<<`·`^^`)으로 편다 — 행마다 셀 수가
         * 같아야 파이프 구조가 깨지지 않고, GFM만 아는 렌더러에서도 표가 살아 있다.
         */
        private String table(JsonNode node) {
            List<List<JsonNode>> rows = new ArrayList<>();
            for (JsonNode row : node.path("content")) {
                List<JsonNode> cells = new ArrayList<>();
                for (JsonNode cell : row.path("content")) {
                    cells.add(cell);
                }
                rows.add(cells);
            }
            if (rows.isEmpty()) {
                return "";
            }
            List<List<String>> grid = expandSpans(rows);
            int columns = grid.stream().mapToInt(List::size).max().orElse(0);
            if (columns == 0) {
                return "";
            }

            boolean headerFirst = rows.get(0).stream()
                    .allMatch(cell -> "tableHeader".equals(cell.path("type").asText("")));
            List<String> lines = new ArrayList<>();
            if (headerFirst) {
                lines.add(row(grid.get(0), columns));
            } else {
                // GFM 표에는 헤더가 반드시 있다. 원본 첫 행이 데이터면 빈 헤더를 끼워 넣는다 —
                // 헤더로 승격시키면 없던 강조가 생기고 첫 행 데이터가 사라진 것처럼 보인다.
                lines.add(row(List.of(), columns));
            }
            lines.add("|" + " --- |".repeat(columns));
            for (int index = headerFirst ? 1 : 0; index < grid.size(); index++) {
                lines.add(row(grid.get(index), columns));
            }
            return String.join("\n", lines);
        }

        private String row(List<String> cells, int columns) {
            StringBuilder out = new StringBuilder("|");
            for (int index = 0; index < columns; index++) {
                out.append(' ').append(index < cells.size() ? cells.get(index) : "").append(" |");
            }
            return out.toString();
        }

        /** 소유 셀만 있는 행 목록을 마커가 채워진 완전 그리드로 편다(tableSpans.expandSpanGrid와 같은 규칙). */
        private List<List<String>> expandSpans(List<List<JsonNode>> rows) {
            List<List<String>> grid = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                grid.add(new ArrayList<>());
            }
            for (int r = 0; r < rows.size(); r++) {
                int column = 0;
                for (JsonNode cell : rows.get(r)) {
                    List<String> current = grid.get(r);
                    while (column < current.size() && current.get(column) != null) {
                        column++;
                    }
                    int colSpan = Math.max(cell.path("attrs").path("colSpan").asInt(1), 1);
                    int rowSpan = Math.max(cell.path("attrs").path("rowSpan").asInt(1), 1);
                    if (r + rowSpan > rows.size()) {
                        // 원본이 표 밖까지 뻗은 rowspan을 준다. 그리드를 늘리면 없는 행이 생기므로
                        // 여기서 잘라내고 보고한다 — 표가 깨지는 것보다 병합 하나를 잃는 편이 낫다.
                        issue(TABLE_SPAN_DROPPED, "table:row" + r + ":col" + column);
                        rowSpan = rows.size() - r;
                    }
                    set(grid, r, column, cellText(cell));
                    for (int dc = 1; dc < colSpan; dc++) {
                        set(grid, r, column + dc, COLSPAN_MARKER);
                    }
                    for (int dr = 1; dr < rowSpan; dr++) {
                        for (int dc = 0; dc < colSpan; dc++) {
                            set(grid, r + dr, column + dc, ROWSPAN_MARKER);
                        }
                    }
                    column += colSpan;
                }
            }
            for (List<String> row : grid) {
                row.replaceAll(value -> value == null ? "" : value);
            }
            return grid;
        }

        private void set(List<List<String>> grid, int row, int column, String value) {
            List<String> target = grid.get(row);
            while (target.size() <= column) {
                target.add(null);
            }
            target.set(column, value);
        }

        /** 셀 안의 블록은 한 줄로 눕힌다 — 파이프 표는 셀 안에 줄바꿈을 담을 수 없다. */
        private String cellText(JsonNode cell) {
            String flattened = String.join(" ", blocks(cell.path("content")))
                    .replace("\n", " ")
                    .replaceAll(" {2,}", " ")
                    .trim();
            return flattened.replace("|", "\\|");
        }

        /* ── 인라인 ─────────────────────────────────────────────── */

        private String inline(JsonNode content, boolean lineStart) {
            StringBuilder out = new StringBuilder();
            boolean first = true;
            for (JsonNode node : content) {
                out.append(inlineNode(node, lineStart && first));
                first = false;
            }
            return out.toString();
        }

        private String inlineNode(JsonNode node, boolean lineStart) {
            String type = node.path("type").asText("");
            return switch (type) {
                case "text" -> marked(node, lineStart);
                // 역슬래시 줄바꿈. 줄 끝 공백 두 개도 GFM 문법이지만 tiptap-markdown은 역슬래시로
                // 직렬화하므로, 공백으로 쓰면 편집기를 한 번 지나는 순간 전부 바뀐다.
                case "hardBreak" -> "\\\n";
                case "pageLink" -> pageLink(node);
                case "mention" -> mention(node);
                case "image" -> image(node);
                case "attachment" -> attachment(node);
                case "opaque" -> inlineOpaque(node);
                default -> inline(node.path("content"), lineStart);
            };
        }

        /**
         * 마크는 안쪽부터 code → 굵게 → 기울임 → 취소선 → 색 → 링크 순으로 감싼다.
         * 코드 마크가 가장 안쪽인 이유는 백틱 안의 별표·대괄호가 문법이 아니기 때문이다.
         */
        private String marked(JsonNode node, boolean lineStart) {
            String text = node.path("text").asText("");
            if (text.isEmpty()) {
                return "";
            }
            boolean code = hasMark(node, "code");
            String out = code ? "`" + text + "`" : escape(text, lineStart);
            if (hasMark(node, "bold")) {
                out = "**" + out + "**";
            }
            if (hasMark(node, "italic")) {
                out = "*" + out + "*";
            }
            if (hasMark(node, "strike")) {
                out = "~~" + out + "~~";
            }
            if (hasMark(node, "underline")) {
                // 우리 방언에 밑줄 문법이 없다. 링크와 시각적으로 구분되지 않아 도입하지 않았다.
                issue(MARK_DROPPED, "mark:underline");
            }
            out = colored(node, out, "textColor", "c", TEXT_COLORS);
            out = colored(node, out, "highlight", "bg", BG_COLORS);
            JsonNode link = mark(node, "link");
            if (link != null) {
                String href = link.path("attrs").path("href").asText("");
                if (!href.isBlank()) {
                    out = "[" + out + "](" + href + ")";
                }
            }
            return out;
        }

        /** `:c[내용]{.red}` / `:bg[내용]{.yellow}` — 팔레트 밖 색은 임의 hex를 만들지 않고 뗀다. */
        private String colored(JsonNode node, String text, String markType, String directive,
                               Set<String> palette) {
            JsonNode found = mark(node, markType);
            if (found == null) {
                return text;
            }
            String color = found.path("attrs").path("color").asText("").toLowerCase(Locale.ROOT);
            if (!palette.contains(color)) {
                issue(MARK_DROPPED, "mark:" + markType + ":" + (color.isBlank() ? "unknown" : color));
                return text;
            }
            return ":" + directive + "[" + text + "]{." + color + "}";
        }

        /**
         * 우리 위키링크는 제목 기준이라(W21-2) 같은 스페이스로 옮겨진 문서는 M1에서도 그대로 열린다.
         * 다른 스페이스는 열 곳이 없으므로 원본 URL을 남기고 보고한다.
         */
        private String pageLink(JsonNode node) {
            JsonNode attrs = node.path("attrs");
            JsonNode target = attrs.path("target");
            String label = attrs.path("label").asText("");
            String anchor = target.path("anchor").asText("");
            String href = target.path("href").asText("");
            String external = target.path("externalObjectId").asText("");

            if (external.isBlank()) {
                // 앵커만 있는 문서 내 링크. 제목을 모르니 표준 링크로 남긴다.
                String text = label.isBlank() ? href : label;
                return href.isBlank() ? escape(text, false)
                        : "[" + escapeLinkText(text) + "](" + href + ")";
            }
            int separator = external.indexOf(':');
            String spaceKey = separator < 0 ? "" : external.substring(0, separator);
            String title = separator < 0 ? external : external.substring(separator + 1);
            boolean sameSpace = spaceKey.isBlank()
                    || (context.sourceSpaceKey() != null && spaceKey.equalsIgnoreCase(context.sourceSpaceKey()));
            if (!sameSpace) {
                issue(LINK_EXTERNAL_SPACE, "page:" + external);
                String text = label.isBlank() ? title : label;
                return "[" + escapeLinkText(text) + "](" + externalPageUrl(spaceKey, title) + ")";
            }
            if (!anchor.isBlank()) {
                issue(LINK_ANCHOR_DROPPED, "page:" + external + "#" + anchor);
            }
            // `[[제목]]`은 wikiLink 노드의 직렬화기가 이스케이프 없이 그대로 쓰는 형태다.
            return "[[" + title + "]]";
        }

        private String externalPageUrl(String spaceKey, String title) {
            String base = context.sourceBaseUrl() == null ? "" : context.sourceBaseUrl();
            return base + "/display/" + URLEncoder.encode(spaceKey, StandardCharsets.UTF_8)
                    + "/" + URLEncoder.encode(title, StandardCharsets.UTF_8);
        }

        /**
         * 멘션은 표시 이름 텍스트로만 남긴다. `[@이름](user:id)`로 쓰려면 원본 사용자를 우리 계정
         * id로 대조해야 하는데, 그 대조는 M1 범위 밖이다(기획 P2·§2 제외).
         */
        private String mention(JsonNode node) {
            JsonNode attrs = node.path("attrs");
            String name = firstNonBlank(attrs.path("label").asText(""), attrs.path("name").asText(""),
                    attrs.path("text").asText(""), node.path("text").asText(""));
            return name.isBlank() ? "" : escape("@" + name, false);
        }

        private String inlineOpaque(JsonNode node) {
            JsonNode sourceRef = node.path("sourceRef");
            String name = sourceRef.path("sourceType").asText("unknown");
            String path = sourceRef.path("path").asText("");
            issue(MACRO_OPAQUE, path.isBlank() ? "macro:" + name : path);
            return "`" + name + "`";
        }

        /* ── 도구 ──────────────────────────────────────────────── */

        private void collectPlainText(JsonNode content, StringBuilder out) {
            for (JsonNode node : content) {
                if (node.has("text")) {
                    out.append(node.path("text").asText(""));
                }
                if (node.has("content")) {
                    collectPlainText(node.path("content"), out);
                }
            }
        }

        private void issue(String code, String sourcePath) {
            issues.add(MigrationStageIssue.warning(code,
                    sourcePath == null || sourcePath.isBlank() ? "unknown" : sourcePath));
        }

        private static JsonNode mark(JsonNode node, String type) {
            for (JsonNode mark : node.path("marks")) {
                if (type.equals(mark.path("type").asText(""))) {
                    return mark;
                }
            }
            return null;
        }

        private static boolean hasMark(JsonNode node, String type) {
            return mark(node, type) != null;
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private static JsonNode singleton(JsonNode node) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(node);
        }

        private static String trimNumber(double value) {
            return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        }
    }

    /** `> ` 인용 접두. 빈 줄은 `>`만 남긴다 — tiptap-markdown이 같은 형태로 쓴다. */
    static String quote(String body) {
        if (body.isEmpty()) {
            return ">";
        }
        StringBuilder out = new StringBuilder();
        String[] lines = body.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                out.append('\n');
            }
            out.append(lines[index].isEmpty() ? ">" : "> " + lines[index]);
        }
        return out.toString();
    }

    /** 목록 항목의 첫 줄에 마커를, 나머지 줄에 들여쓰기를 붙인다. */
    static String prefixFirstLine(String body, String marker, String indent) {
        String[] lines = body.isEmpty() ? new String[]{""} : body.split("\n", -1);
        StringBuilder out = new StringBuilder(marker).append(lines[0]);
        for (int index = 1; index < lines.length; index++) {
            out.append('\n');
            if (!lines[index].isEmpty()) {
                out.append(indent);
            }
            out.append(lines[index]);
        }
        return out.toString();
    }

    /**
     * prosemirror-markdown의 `esc`와 같은 규칙.
     *
     * 우리가 정한 규칙이 아니라 wiki-front 직렬화기가 하는 그대로다 — 다르게 쓰면 편집기에 한 번
     * 들어갔다 나온 문서가 이스케이프만 바뀐 채 통째로 diff에 뜬다.
     */
    static String escape(String value, boolean lineStart) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '`' || ch == '*' || ch == '\\' || ch == '~' || ch == '[' || ch == ']') {
                out.append('\\').append(ch);
                continue;
            }
            if (ch == '_') {
                boolean intraWord = index > 0 && index + 1 < value.length()
                        && isWordChar(value.charAt(index - 1)) && isWordChar(value.charAt(index + 1));
                if (!intraWord) {
                    out.append('\\');
                }
                out.append(ch);
                continue;
            }
            out.append(ch);
        }
        String escaped = out.toString();
        if (!lineStart) {
            return escaped;
        }
        // 줄 첫 글자가 블록 문법으로 읽히면 문단이 목록·제목으로 둔갑한다.
        escaped = escaped.replaceFirst("^([#\\-*+>])", "\\\\$1");
        return escaped.replaceFirst("^(\\s*)(\\d+)\\.", "$1$2\\\\.");
    }

    /** 링크 라벨 안에서는 대괄호만 문제가 된다 — 나머지 문법은 라벨 안에서도 그대로 산다. */
    static String escapeLinkText(String value) {
        return escape(value, false);
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }
}
