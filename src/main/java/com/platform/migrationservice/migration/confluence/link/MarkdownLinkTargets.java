package com.platform.migrationservice.migration.confluence.link;

import java.util.function.UnaryOperator;

/**
 * 마크다운 본문의 링크·이미지 **대상만** 갈아끼운다. 텍스트와 구조는 한 글자도 건드리지 않는다.
 *
 * 마크다운을 다시 파싱해 다시 쓰지 않는 이유: 우리 방언은 편집기 왕복의 고정점이라(§1.6) 왕복
 * 한 번이 대괄호 이스케이프나 목록 들여쓰기를 바꾸면 이관 diff가 통째로 뒤집힌다. 대상 문자열만
 * 바꾸는 편이 안전하다.
 *
 * 코드는 건너뛴다 — 펜스 블록과 인라인 코드 안의 `](...)`는 링크가 아니라 예제 코드다.
 */
public final class MarkdownLinkTargets {

    private MarkdownLinkTargets() {
    }

    /**
     * `](대상)` 꼴을 찾아 rewriter가 돌려주는 값으로 바꾼다. rewriter가 null이나 같은 값을 주면
     * 그 자리는 원문 그대로 둔다.
     */
    public static String rewrite(String markdown, UnaryOperator<String> rewriter) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }
        StringBuilder out = new StringBuilder(markdown.length());
        boolean inFence = false;
        for (String line : markdown.split("\n", -1)) {
            String fenceMarker = fenceMarkerOf(line);
            if (fenceMarker != null) {
                inFence = !inFence;
                appendLine(out, line);
                continue;
            }
            appendLine(out, inFence ? line : rewriteLine(line, rewriter));
        }
        // 줄마다 줄바꿈을 붙였으니 마지막 하나를 되돌린다 — 원문의 끝 개행 여부가 보존된다.
        return out.substring(0, out.length() - 1);
    }

    private static void appendLine(StringBuilder out, String line) {
        out.append(line).append('\n');
    }

    /** 펜스 시작·끝 줄인가. 들여쓴 펜스도 우리 writer가 목록 안에서 쓰므로 trim해서 본다. */
    private static String fenceMarkerOf(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("```")) {
            return "```";
        }
        if (trimmed.startsWith("~~~")) {
            return "~~~";
        }
        return null;
    }

    private static String rewriteLine(String line, UnaryOperator<String> rewriter) {
        StringBuilder out = new StringBuilder(line.length());
        int index = 0;
        while (index < line.length()) {
            char current = line.charAt(index);
            if (current == '\\' && index + 1 < line.length()) {
                out.append(current).append(line.charAt(index + 1));
                index += 2;
                continue;
            }
            if (current == '`') {
                int run = backtickRun(line, index);
                int close = closingBacktickRun(line, index + run, run);
                int end = close < 0 ? line.length() : close + run;
                out.append(line, index, end);
                index = end;
                continue;
            }
            if (current == ']' && index + 1 < line.length() && line.charAt(index + 1) == '(') {
                int close = matchingParen(line, index + 2);
                if (close > 0) {
                    String target = line.substring(index + 2, close);
                    String replacement = rewriter.apply(target);
                    out.append("](").append(replacement == null ? target : replacement).append(')');
                    index = close + 1;
                    continue;
                }
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private static int backtickRun(String line, int from) {
        int index = from;
        while (index < line.length() && line.charAt(index) == '`') {
            index++;
        }
        return index - from;
    }

    /** 같은 길이의 백틱 묶음이 닫는 자리. 없으면 -1(닫히지 않은 백틱은 코드가 아니다). */
    private static int closingBacktickRun(String line, int from, int length) {
        for (int index = from; index < line.length(); index++) {
            if (line.charAt(index) != '`') {
                continue;
            }
            int run = backtickRun(line, index);
            if (run == length) {
                return index;
            }
            index += run - 1;
        }
        return -1;
    }

    /** 대상 문자열이 끝나는 `)`의 위치. 안에 든 괄호 쌍은 세어 넘긴다. 못 닫으면 -1. */
    private static int matchingParen(String line, int from) {
        int depth = 0;
        for (int index = from; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '\\') {
                index++;
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                if (depth == 0) {
                    return index;
                }
                depth--;
            }
        }
        return -1;
    }
}
