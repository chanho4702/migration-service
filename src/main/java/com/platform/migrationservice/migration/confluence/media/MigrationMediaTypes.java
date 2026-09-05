package com.platform.migrationservice.migration.confluence.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 받아 둔 첨부의 형식을 바이트 앞머리로 판정한다.
 *
 * 원본이 알려 주는 Content-Type을 믿지 않는 이유: DC는 파일명 확장자로 되짚은 값을 주는 일이
 * 잦고, 그 값이 틀리면 본문의 이미지가 내려받기 링크로 눕는다. 위키 쪽 판정과 같은 표를 쓴다.
 */
public final class MigrationMediaTypes {

    public static final String OCTET_STREAM = "application/octet-stream";

    private MigrationMediaTypes() {
    }

    public static String detect(InputStream input) throws IOException {
        byte[] header = input.readNBytes(16);
        if (startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return "image/png";
        }
        if (startsWith(header, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (startsWith(header, ascii("GIF87a")) || startsWith(header, ascii("GIF89a"))) {
            return "image/gif";
        }
        if (header.length >= 12
                && Arrays.equals(Arrays.copyOfRange(header, 0, 4), ascii("RIFF"))
                && Arrays.equals(Arrays.copyOfRange(header, 8, 12), ascii("WEBP"))) {
            return "image/webp";
        }
        if (startsWith(header, ascii("%PDF-"))) {
            return "application/pdf";
        }
        return OCTET_STREAM;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
