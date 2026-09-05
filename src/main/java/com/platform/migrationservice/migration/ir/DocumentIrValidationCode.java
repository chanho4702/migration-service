package com.platform.migrationservice.migration.ir;

/**
 * Importer와 migration worker가 공통으로 처리할 수 있는 Document IR 검증 실패 코드다.
 */
public enum DocumentIrValidationCode {
    INVALID_JSON,
    REQUIRED_FIELD_MISSING,
    INVALID_TYPE,
    INVALID_VALUE,
    ADDITIONAL_PROPERTY_FORBIDDEN,
    UNSUPPORTED_SCHEMA_VERSION,
    UNSUPPORTED_PROVIDER,
    UNSUPPORTED_BLOCK_TYPE,
    DUPLICATE_MEDIA_ID,
    DUPLICATE_BLOCK_ID,
    UNDECLARED_MEDIA_ID,
    EMBEDDED_MEDIA_LOCATION_FORBIDDEN,
    PAGE_LINK_TARGET_MISSING
}
