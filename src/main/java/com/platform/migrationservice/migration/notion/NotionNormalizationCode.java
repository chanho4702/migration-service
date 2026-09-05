package com.platform.migrationservice.migration.notion;

public enum NotionNormalizationCode {
    INVALID_SNAPSHOT,
    UNSUPPORTED_SNAPSHOT_VERSION,
    UNSUPPORTED_NOTION_API_VERSION,
    INCOMPLETE_PAGINATION,
    MISSING_BLOCK_CHILDREN,
    PAGE_TITLE_MISSING
}
