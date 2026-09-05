package com.platform.migrationservice.migration.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.migrationservice.migration.normalization.MigrationNormalizationIssue;

import java.util.List;

public record NotionNormalizationResult(
        JsonNode documentIr,
        List<MigrationNormalizationIssue> issues) {

    public NotionNormalizationResult {
        issues = List.copyOf(issues);
    }
}
