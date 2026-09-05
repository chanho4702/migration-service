package com.platform.migrationservice.migration.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class MigrationSourceKey {

    private MigrationSourceKey() {
    }

    static String item(String externalObjectId) {
        return sha256(requireText(externalObjectId, "externalObjectId", 512));
    }

    static String object(MigrationProvider provider, String sourceInstanceId, String externalObjectId) {
        String providerValue = require(provider, "provider").name();
        String instanceValue = requireText(sourceInstanceId, "sourceInstanceId", 255);
        String objectValue = requireText(externalObjectId, "externalObjectId", 512);
        String identity = component(providerValue) + component(instanceValue) + component(objectValue);
        return sha256(identity);
    }

    static String issue(String code, String sourcePath) {
        String codeValue = requireText(code, "code", 128);
        String pathValue = requireText(sourcePath, "sourcePath", 1024);
        return sha256(component(codeValue) + component(pathValue));
    }

    static String requireChecksum(String checksum) {
        String value = requireText(checksum, "sourceChecksum", 64);
        if (!value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("sourceChecksum must be lowercase SHA-256");
        }
        return value;
    }

    static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String component(String value) {
        return value.length() + ":" + value;
    }
}
