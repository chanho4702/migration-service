package com.platform.migrationservice.migration.ir;

import java.util.Objects;

/**
 * 원본 문서나 사용자 본문을 포함하지 않는 안전한 Document IR 검증 오류다.
 */
public final class DocumentIrValidationException extends IllegalArgumentException {

    private final DocumentIrValidationCode code;
    private final String path;

    public DocumentIrValidationException(DocumentIrValidationCode code, String path) {
        super("Document IR validation failed: " + Objects.requireNonNull(code) + " at " + safePath(path));
        this.code = code;
        this.path = safePath(path);
    }

    public DocumentIrValidationCode getCode() {
        return code;
    }

    public String getPath() {
        return path;
    }

    private static String safePath(String path) {
        return path == null || path.isBlank() ? "/" : path;
    }
}
