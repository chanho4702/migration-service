package com.platform.migrationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 이관 엔진 서비스 — 컨플루언스 설치형·노션 원본을 읽어 위키 import API로 밀어 넣는다.
 *
 * 위키 도메인을 직접 만지지 않는다: 문서·첨부·댓글·제한은 전부
 * {@link com.platform.migrationservice.wiki.WikiImportClient}를 거친다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MigrationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationServiceApplication.class, args);
    }
}
