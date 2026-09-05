package com.platform.migrationservice.migration.notion;

import java.util.Objects;

/**
 * 원본 값이나 본문을 반사하지 않는 Notion snapshot 경계 오류다.
 */
public final class NotionNormalizationException extends IllegalArgumentException {

    private final NotionNormalizationCode code;
    private final String path;

    public NotionNormalizationException(NotionNormalizationCode code, String path) {
        super("Notion normalization failed: " + Objects.requireNonNull(code) + " at " + safePath(path));
        this.code = code;
        this.path = safePath(path);
    }

    public NotionNormalizationCode getCode() {
        return code;
    }

    public String getPath() {
        return path;
    }

    private static String safePath(String path) {
        return path == null || path.isBlank() ? "/" : path;
    }
}
