package com.platform.migrationservice.migration.confluence;

import java.util.Objects;

public final class ConfluenceNormalizationException extends IllegalArgumentException {

    private final ConfluenceNormalizationCode code;
    private final String path;

    public ConfluenceNormalizationException(ConfluenceNormalizationCode code, String path) {
        super("Confluence normalization failed: " + Objects.requireNonNull(code) + " at " + safePath(path));
        this.code = code;
        this.path = safePath(path);
    }

    public ConfluenceNormalizationCode getCode() {
        return code;
    }

    public String getPath() {
        return path;
    }

    private static String safePath(String path) {
        return path == null || path.isBlank() ? "/" : path;
    }
}
