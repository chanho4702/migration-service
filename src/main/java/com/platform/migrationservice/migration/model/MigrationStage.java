package com.platform.migrationservice.migration.model;

public enum MigrationStage {
    EXTRACT,
    NORMALIZE,
    MEDIA_COPY,
    RESOLVE,
    VERIFY,
    DONE
}
