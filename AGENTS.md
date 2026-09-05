# AGENTS.md — migration-service

실제 서비스 규약은 `./CLAUDE.md`가 정본이다. 작업 전에 전체를 읽고 따른다.

이 리포에 적용되는 플랫폼 확정 결정(루트 `CLAUDE.md` "확정 설계 결정" 절 참고):

- 리소스 서버 공통 규약은 `common-starter`(현재 `0.16.0`) — JWT 검증(JWKS + issuer/audience),
  `roles`→`ROLE_*` 변환, `{"error": 메시지}` 오류 계약, 공용 예외(`com.platform.common.error.*`)를
  서비스에 다시 복제하지 않는다. `SecurityFilterChain`(경로 정책)만 이 서비스가 가진다.
- 공유 아티팩트 버전은 하나다 — wiki·alm·board와 같은 `common-proto`/`common-starter` 발행본을 본다.
- 로그는 stdout JSON만 출력한다(docker 프로필: `logging.structured.format.console: ecs`). Alloy →
  Loki → Grafana가 수집한다. 앱에서 Loki 직결(loki4j) 금지.
- 스키마 변경은 Flyway로만 한다. `spring.jpa.hibernate.ddl-auto: validate` — 엔티티가 스키마를
  만들게 하지 않는다.
- DB는 PostgreSQL 통일(`migrationdb`), 서비스 간 이벤트는 Redis Streams(Kafka 아님) — 단 이
  서비스는 Redis를 쓰지 않는다. 위키로 가는 쓰기는 전부 동기 HTTP다.

이 서비스만의 되돌리지 말 것:

- **위키 도메인을 직접 만지지 않는다.** 문서·첨부·댓글·제한은 `/internal/wiki/import`를 거친다.
  성능을 이유로 위키 DB나 저장소를 직접 읽고 쓰자는 제안은 서비스를 가른 이유를 되돌리는 것이다.
- **위키 id에는 FK가 없다**(V1). `target_space_id`·`target_page_id`·`target_comment_id`는 다른
  서비스의 id이고, 살아 있는지는 import API 조회로 판정한다.
- **실패를 빈 결과로 삼키지 않는다.** 닿지 못한 것은 재시도, 거부당한 것은 데드레터, 대조 못 한
  주체는 fail-closed다.
