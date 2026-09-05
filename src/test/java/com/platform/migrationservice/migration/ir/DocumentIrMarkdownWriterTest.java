package com.platform.migrationservice.migration.ir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.migrationservice.migration.worker.MigrationStageIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IR → 마크다운 골든 테스트.
 *
 * 골든 파일(`*.md`)은 백엔드만의 기대값이 아니라 **프론트와 공유하는 계약**이다. 같은 파일을
 * wiki-front의 `editor/markdown.test.ts`가 왕복(parse→serialize) 케이스로 읽어, 우리가 쓴 문자열이
 * 편집기를 한 번 통과해도 그대로인지 확인한다. 한쪽만 고치면 이관된 문서가 처음 편집되는 순간
 * 전체가 diff로 바뀐다.
 *
 * 골든을 다시 만들려면 `-Dgolden.write=true`로 돌린다. 바뀐 결과를 눈으로 확인하고 커밋하라는
 * 뜻이지, 실패할 때마다 눌러 통과시키라는 뜻이 아니다.
 */
class DocumentIrMarkdownWriterTest {

    private static final String FIXTURES = "src/test/resources/fixtures/migration/confluence/golden/";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocumentIrMarkdownWriter writer = new DocumentIrMarkdownWriter();
    private final DocumentIrValidator validator = new DocumentIrValidator(objectMapper);

    @ParameterizedTest
    @ValueSource(strings = {"basic-formatting", "table-panel-columns", "opaque-media-links"})
    void 골든_마크다운과_일치한다(String name) throws Exception {
        JsonNode ir = readIr(name);
        // 골든 입력이 IR 계약을 벗어나면 테스트가 통과해도 의미가 없다 — 실제 파이프라인은
        // 검증을 통과한 IR만 이 writer에 넘긴다.
        validator.validate(ir);

        String markdown = writer.write(ir, new DocumentIrMarkdownContext("ENG", "https://wiki.example.com"))
                .markdown();
        if (Boolean.getBoolean("golden.write")) {
            Files.writeString(Path.of(FIXTURES + name + ".md"), markdown, StandardCharsets.UTF_8);
        }
        assertThat(markdown).isEqualTo(Files.readString(Path.of(FIXTURES + name + ".md"),
                StandardCharsets.UTF_8));
    }

    /** 에디터(StarterKit)가 1~3단계만 안다 — `####`를 쓰면 편집 한 번에 제목이 문단으로 눕는다. */
    @Test
    void 제목_깊이는_세_단계에서_멈춘다() throws Exception {
        String markdown = write("basic-formatting");

        assertThat(markdown).contains("# 기본 서식");
        assertThat(markdown).contains("### 다섯 번째 깊이");
    }

    @Test
    void 표는_병합_셀을_마커로_펴서_행마다_셀_수가_같다() throws Exception {
        String markdown = write("table-panel-columns");

        List<String> rows = markdown.lines().filter(line -> line.startsWith("|")).toList();
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row ->
                assertThat(row.chars().filter(ch -> ch == '|').count()).isEqualTo(4));
        // 열 병합 마커는 엔티티 형태(`&lt;&lt;`)가 왕복의 고정점이다 — tableSpanBridge.test.ts와 같은 규약.
        assertThat(markdown).contains("&lt;&lt;").contains("^^");
    }

    @Test
    void 패널은_GitHub_alert_문법으로_남고_대괄호는_이스케이프된다() throws Exception {
        String markdown = write("table-panel-columns");

        // 이스케이프 형태가 tiptap-markdown 왕복의 고정점이다(markdown.test.ts의 alert 케이스).
        assertThat(markdown).contains("> \\[!WARNING\\] **운영 주의**");
        assertThat(markdown).contains("> \\[!TIP\\] 미리 점검하면 빠릅니다.");
    }

    @Test
    void 컬럼은_바깥_마커가_더_길다() throws Exception {
        String markdown = write("table-panel-columns");

        assertThat(markdown).contains("::::columns");
        assertThat(markdown).contains(":::column\n왼쪽 절차\n:::");
        assertThat(markdown).contains(":::column{width=30}");
    }

    @Test
    void 같은_스페이스_문서는_위키링크로_다른_스페이스는_원본_URL로_남는다() throws Exception {
        DocumentIrMarkdownResult result = render("opaque-media-links");

        assertThat(result.markdown()).contains("[[장애 대응 절차]]");
        assertThat(result.markdown())
                .contains("[운영 규정](https://wiki.example.com/display/OPS/%EC%9A%B4%EC%98%81+%EA%B7%9C%EC%A0%95)");
        assertThat(codes(result)).contains(DocumentIrMarkdownWriter.LINK_EXTERNAL_SPACE);
    }

    @Test
    void 팔레트_밖_색과_밑줄은_마크를_떼고_보고한다() throws Exception {
        DocumentIrMarkdownResult result = render("opaque-media-links");

        assertThat(result.markdown()).contains(":c[중요]{.red}");
        assertThat(result.markdown()).contains(":bg[강조]{.yellow}");
        // 마크만 사라지고 글자는 남는다 — 본문에서 내용이 없어지는 것이 최악의 손실이다.
        assertThat(result.markdown()).contains("밑줄").contains("팔레트밖");
        assertThat(codes(result)).contains(DocumentIrMarkdownWriter.MARK_DROPPED);
    }

    @Test
    void 미지원_매크로는_경고_패널로_남고_원본_경로를_보고한다() throws Exception {
        DocumentIrMarkdownResult result = render("opaque-media-links");

        assertThat(result.markdown())
                .contains("> \\[!WARNING\\] 원본 매크로 `ac:structured-macro[jira]`는 이관되지 않았습니다");
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo(DocumentIrMarkdownWriter.MACRO_OPAQUE);
                    assertThat(issue.sourcePath()).isEqualTo("/storage/ac:structured-macro[3]");
                });
    }

    @Test
    void 첨부와_이미지는_attachment_참조로_남는다() throws Exception {
        String markdown = write("opaque-media-links");

        assertThat(markdown).contains("![서비스 토폴로지](attachment:topology.png)");
        assertThat(markdown).contains("[운영 런북](attachment:runbook.pdf)");
    }

    @Test
    void 태스크_목록은_항목_사이에_빈_줄을_둔다() throws Exception {
        String markdown = write("basic-formatting");

        assertThat(markdown).contains("- [ ] 복구 테스트 실행\n\n- [x] 롤백 계획 검토");
    }

    private String write(String name) throws Exception {
        return render(name).markdown();
    }

    private DocumentIrMarkdownResult render(String name) throws Exception {
        return writer.write(readIr(name),
                new DocumentIrMarkdownContext("ENG", "https://wiki.example.com"));
    }

    private List<String> codes(DocumentIrMarkdownResult result) {
        return result.issues().stream().map(MigrationStageIssue::code).toList();
    }

    private JsonNode readIr(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/migration/confluence/golden/" + name + ".ir.json")) {
            return objectMapper.readTree(input);
        }
    }
}
