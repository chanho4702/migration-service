package com.platform.migrationservice.migration.confluence.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * MEDIA_COPY가 원본에서 받은 첨부 바이트를 위키로 올리기 전까지 두는 자리.
 *
 * 왜 이 서비스의 디스크인가: 단계 순서가 MEDIA_COPY → RESOLVE라 파일을 받는 시점에는 대상
 * 페이지가 아직 없다. 위키에 올리려면 페이지가 있어야 하고, 그렇다고 RESOLVE에서 다시 받으면
 * 재시도마다 남의 서버를 통째로 다시 긁는다. 그래서 여기 한 번만 받아 두고, RESOLVE가 그 파일을
 * 위키로 스트리밍한다.
 *
 * 파일 이름은 내용의 SHA-256이다 — 같은 바이트를 두 번 받아도 자리를 두 벌 쓰지 않고, 재실행이
 * "이미 받아 뒀다"를 파일 존재만으로 판정할 수 있다.
 */
@Component
@Slf4j
public class MigrationMediaStaging {

    private final Path root;

    public MigrationMediaStaging(
            @Value("${platform.migration.staging-dir:./data/migration-staging}") String stagingDir) {
        this.root = Path.of(stagingDir);
    }

    /**
     * 바이트를 받아 둔다.
     *
     * @return 저장 위치·형식·지문
     */
    public StagedFile stage(byte[] content) {
        try {
            String contentType;
            try (InputStream probe = new ByteArrayInputStream(content)) {
                contentType = MigrationMediaTypes.detect(probe);
            }
            String checksum = sha256(content);
            Files.createDirectories(root);
            Path target = root.resolve(checksum + ".bin");
            if (!Files.exists(target)) {
                // 같은 이름의 임시 파일로 먼저 쓰고 옮긴다 — 반쯤 쓰인 파일을 다음 시도가
                // "이미 받아 뒀다"로 착각하면 첨부가 조용히 깨진다.
                Path temporary = root.resolve(checksum + ".part");
                Files.write(temporary, content);
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return new StagedFile(target, contentType, checksum, content.length);
        } catch (IOException exception) {
            throw new UncheckedIOException("이관 첨부 스테이징 실패", exception);
        }
    }

    /** 스테이징 파일이 아직 있는가. 없으면 다시 받아야 한다. */
    public Optional<Path> locate(String checksum) {
        if (checksum == null || checksum.isBlank()) {
            return Optional.empty();
        }
        Path target = root.resolve(checksum + ".bin");
        return Files.exists(target) ? Optional.of(target) : Optional.empty();
    }

    /**
     * 위키로 올리고 난 파일을 치운다. 실패해도 조용히 넘어간다 — 고아 파일 한 개보다 이관을
     * 되돌리는 쪽이 훨씬 비싸다.
     */
    public void discard(String checksum) {
        locate(checksum).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                log.warn("스테이징 파일을 지우지 못했다 — 고아로 남긴다: {}", path.getFileName(), exception);
            }
        });
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }

    /** 받아 둔 파일 하나 — 아직 어떤 문서에도 붙지 않았다. */
    public record StagedFile(Path path, String contentType, String checksum, long sizeBytes) {
    }
}
