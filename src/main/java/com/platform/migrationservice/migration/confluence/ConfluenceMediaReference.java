package com.platform.migrationservice.migration.confluence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ConfluenceMediaReference {

    private ConfluenceMediaReference() {
    }

    public static String attachment(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename is required");
        }
        return "attachment:" + filename;
    }

    public static String externalUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "url:" + HexFormat.of().formatHex(digest.digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
