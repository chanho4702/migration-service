package com.platform.migrationservice.wiki;

import java.util.Locale;
import java.util.Optional;

/**
 * 위키의 라벨 정규화 규칙을 그대로 따른다 — 앞뒤 공백 제거, 소문자, 내부 공백은 하이픈, 64자 상한.
 *
 * 왜 복제하는가: VERIFY가 "원본 라벨 몇 개가 실제로 붙었는가"를 세려면 위키가 몇 개로 접었는지를
 * 알아야 한다. 대소문자만 다른 두 라벨은 위키에서 한 개가 된다 — 여기서 접지 않으면 멀쩡한
 * 이관마다 VERIFY_LABEL_MISMATCH가 뜬다. 규칙이 갈라지지 않도록 위키 쪽이 바뀌면 여기도 바꾼다.
 */
public final class MigrationLabels {

    public static final int MAX_LENGTH = 64;

    private MigrationLabels() {
    }

    /** 정규화한 라벨. 빈 값이거나 상한을 넘으면 빈 결과 — 위키가 거부할 라벨이다. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
