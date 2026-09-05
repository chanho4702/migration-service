# migration-service 작업 규약

루트 `C:/MSA_TEMPLATE/CLAUDE.md`를 먼저 따른다.

## 이 서비스는 무엇인가

컨플루언스 설치형(Server/DC)·노션 원본을 읽어 우리 위키로 옮기는 **이관 엔진**이다. 위키에서
떼어 낸 이유는 하나다 — 이관은 제품이 아니라 도구이고, 위키가 그것을 품고 있으면 "위키를 쓰는
사람"이 이관 코드까지 배포하게 된다(설계 §0, `wiki-front/docs/superpowers/specs/2026-09-05-migration-service-split-design.md`).

**위키 도메인을 직접 만지지 않는다.** 문서·리비전·첨부·댓글·제한은 전부 위키의 내부 import
API(`/internal/wiki/import`)를 거친다. 이 서비스의 DB에는 위키 테이블도, 위키 id를 가리키는 FK도
없다. 대상이 아직 살아 있는지는 그때그때 import API로 조회해 판정한다.

## 포트

- 운영: `9140` (REST)
- dev 오프셋(+10000): `19140`

## 경계면

| 상대 | 방식 | 무엇 |
|---|---|---|
| wiki-backend | HTTP `/internal/wiki/import/**`, `X-Internal-Token` + `X-Actor-Id` | 문서·첨부·댓글·제한 쓰기, 검증 조회 |
| org-service | gRPC `CheckPermission`·`ListUserGrants`·`LookupMembers`·`LookupTeams` | 권한 판정, 원본 주체 대조 |
| 원본 컨플루언스 DC | HTTPS(아웃바운드) | 페이지·첨부·댓글·이력 추출 |
| wiki-front `/admin/migrations` | 게이트웨이 `/api/migration/**` | 관리 화면 |

계약의 정본은 설계 문서 §2이고, 예시는 wiki-backend의
`src/test/resources/fixtures/import-api/*.json`이다. **필드를 바꾸려면 그 픽스처와 이 리포의
`FakeWikiImportServer`를 함께 바꾼다** — 한쪽만 고치면 계약이 조용히 갈라진다.

## 장애 전파 (지키지 않으면 데이터가 조용히 빠진다)

- 위키에 닿지 못했다(연결 실패·타임아웃·5xx·429) → **재시도 가능한 단계 실패**(`WIKI_IMPORT_UNAVAILABLE`).
- 위키가 거부했다(4xx) → 비재시도 실패(`WIKI_IMPORT_REJECTED` / `WIKI_IMPORT_NOT_FOUND`).
- org가 닿지 않으면 권한 판정은 **503**, 주체 대조는 재시도 실패(`ORG_LOOKUP_UNAVAILABLE`).
- 그 밖의 org 실패는 **fail-closed** — 대조 못 한 제한 주체는 공개가 아니라 잡 요청자 단독이다(ADR-W14-07).
- 실패를 빈 결과로 삼키지 않는다. 삼키면 문서가 빠진 잡이 `COMPLETED`로 끝난다.

## 규칙 몇 가지

- **SSRF**: 원본이든 위키든, 응답이 알려 준 URL이나 리다이렉트를 따라가지 않는다. 주소는 설정된
  base + 고정 경로로만 만든다(`WikiImportProperties.resolve`, `ConfluenceDcClient`).
- **토큰은 로그에 남기지 않는다.** 원본 PAT도 내부 토큰도 응답 DTO에 싣지 않는다.
- **알림을 발행하지 않는다.** 500페이지를 옮기면서 구독자에게 500통을 보내지 않는다 — import API가
  검색 색인 이벤트만 쏘도록 되어 있고, 그 정책은 위키 쪽에 있다.
- 스키마 변경은 Flyway로만. `ddl-auto: validate`다.

## 빌드/테스트

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-24"
.\gradlew.bat test --no-daemon
.\gradlew.bat bootJar --no-daemon
```

GitHub Packages(`com.platform:common-proto`·`common-starter`) 인증은 `GITHUB_TOKEN` env 또는
`~/.gradle/gradle.properties`의 `gpr.token`.

테스트는 가짜 원본 DC(`FakeConfluenceDcServer`)와 가짜 위키(`FakeWikiImportServer`) **둘 다 실제
HTTP 서버**로 띄운다 — 인터페이스를 목으로 갈아 끼우면 JSON 필드 이름·multipart 조립·상태 코드
해석이 전부 검증을 빠져나간다. `FlywaySchemaValidationTest`는 Docker가 필요하고, 없으면
`--tests '!*FlywaySchemaValidationTest'`로 제외한다.
