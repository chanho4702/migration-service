package com.platform.migrationservice.migration.confluence.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * MEDIA_COPY가 받아 둔 첨부 바이트의 목록(`migration_payload(MEDIA_MANIFEST)`).
 *
 * 이 목록이 존재하는 이유는 **단계 사이에 파일을 두 번 받지 않기 위해서**다. MEDIA_COPY는 대상
 * 페이지가 없는 시점에 돌기 때문에 위키에 올릴 수 없고, 그렇다고 RESOLVE에서 다시 받으면
 * 재실행마다 원본을 통째로 다시 긁는다. 그래서 바이트는 여기서 한 번만 받아 이 서비스 디스크에
 * 두고, RESOLVE는 그 파일을 위키로 스트리밍하기만 한다.
 *
 * **위키 저장소 좌표는 담지 않는다.** 예전에는 버킷·키·백엔드를 적어 두고 위키가 그 객체를 그대로
 * 가리키게 했는데, 그러면 이관 엔진이 위키의 저장소 배치를 알아야 한다 — 서비스를 가른 이유가
 * 사라진다. 지금 담는 것은 파일명·형식·크기·checksum과 우리 디스크 위치뿐이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationMediaManifest(int version, List<Entry> files) {

    /** 지금 쓰는 형식 번호. 바꿀 일이 생기면 올리고 읽는 쪽에서 갈라 본다. */
    public static final int VERSION = 2;

    public MigrationMediaManifest {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static MigrationMediaManifest of(List<Entry> files) {
        return new MigrationMediaManifest(VERSION, files);
    }

    public static MigrationMediaManifest empty() {
        return new MigrationMediaManifest(VERSION, List.of());
    }

    /**
     * @param checksum      내용의 SHA-256. 스테이징 파일 이름이자 위키가 "같은 파일"을 판정하는 키다.
     * @param sourceVersion 원본 첨부의 버전. 파일명과 함께 재다운로드를 건너뛸 판정 키가 된다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String filename, String contentType, long size, String checksum,
                        int sourceVersion) {

        /** 같은 파일의 같은 원본 버전인가 — 재다운로드를 건너뛸 판정 키다. */
        public String stagingKey() {
            return filename + "@" + sourceVersion;
        }
    }
}
