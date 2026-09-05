package com.platform.migrationservice.migration.worker;

/**
 * stage 실패. rate limit·일시적 네트워크 오류처럼 다시 시도할 수 있는 실패만 retryable이고,
 * 나머지는 즉시 dead letter로 보낸다. 메시지에 원본 본문을 넣지 않는다.
 */
public class MigrationStageException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public MigrationStageException(String code, boolean retryable) {
        this(code, retryable, null);
    }

    public MigrationStageException(String code, boolean retryable, Throwable cause) {
        super(code, cause);
        if (code == null || code.isBlank() || code.length() > 128) {
            throw new IllegalArgumentException("code is invalid");
        }
        this.code = code;
        this.retryable = retryable;
    }

    public static MigrationStageException retryable(String code) {
        return new MigrationStageException(code, true);
    }

    public static MigrationStageException permanent(String code) {
        return new MigrationStageException(code, false);
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
