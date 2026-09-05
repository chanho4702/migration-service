package com.platform.migrationservice.migration.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.migrationservice.migration.normalization.MigrationNormalizationIssue;

import java.util.List;

public record ConfluenceNormalizationResult(
        JsonNode documentIr,
        List<MigrationNormalizationIssue> issues) {

    public ConfluenceNormalizationResult {
        issues = List.copyOf(issues);
    }
}
