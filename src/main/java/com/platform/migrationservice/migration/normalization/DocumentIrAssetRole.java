package com.platform.migrationservice.migration.normalization;

public enum DocumentIrAssetRole {
    INLINE("inline"),
    ATTACHMENT("attachment"),
    SOURCE("source"),
    EXPORT("export");

    private final String documentIrValue;

    DocumentIrAssetRole(String documentIrValue) {
        this.documentIrValue = documentIrValue;
    }

    public String documentIrValue() {
        return documentIrValue;
    }
}
