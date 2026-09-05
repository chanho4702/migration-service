package com.platform.migrationservice.migration.normalization;

/**
 * provider의 임시 URL을 영속 object storage로 복사한 뒤 normalizer에 전달하는 media metadata다.
 */
public record ResolvedMigrationAsset(
        String mediaId,
        String sourceExternalId,
        String filename,
        String contentType,
        long sizeBytes,
        String checksum,
        DocumentIrAssetRole role) {
}
