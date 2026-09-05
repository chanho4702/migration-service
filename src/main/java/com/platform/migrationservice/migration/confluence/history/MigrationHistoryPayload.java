package com.platform.migrationservice.migration.confluence.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * EXTRACT가 받아 둔 원본의 지난 버전 본문(`migration_payload(HISTORY)`). 오래된 것부터 담는다.
 *
 * 리비전은 페이지가 있어야 매달 수 있어 RESOLVE까지 들고 가야 하고, 버전 하나가 원본 왕복
 * 한 번이라 재시도가 그 왕복을 되풀이하면 안 된다 — 그래서 payload에 둔다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationHistoryPayload(int version, List<Entry> revisions) {

    /** 지금 쓰는 형식 번호. 바꿀 일이 생기면 올리고 읽는 쪽에서 갈라 본다. */
    public static final int VERSION = 1;

    public MigrationHistoryPayload {
        revisions = revisions == null ? List.of() : List.copyOf(revisions);
    }

    public static MigrationHistoryPayload of(List<Entry> revisions) {
        return new MigrationHistoryPayload(VERSION, revisions);
    }

    public static MigrationHistoryPayload empty() {
        return new MigrationHistoryPayload(VERSION, List.of());
    }

    /**
     * @param number     원본 버전 번호. 우리 리비전 번호는 1..k로 다시 매기므로 참고값이다
     * @param when       그 버전이 저장된 시각(ISO-8601)
     * @param editorName 그 버전을 저장한 사람의 원본 표시 이름 — V28 편집자 이름 스냅샷으로 남는다
     * @param message    원본의 변경 요약. 우리 리비전의 change_note가 된다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(int number, String when, String editorName, String message, String title,
                        String markdown) {
    }
}
