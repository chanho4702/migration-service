# migration-service

컨플루언스 설치형(Server/DC)·노션 원본을 읽어 우리 위키로 옮기는 이관 엔진. Spring Boot 4 ·
Java 24 · PostgreSQL(`migrationdb`) · 포트 `9140`.

위키에서 떼어 낸 서비스다(설계 `wiki-front/docs/superpowers/specs/2026-09-05-migration-service-split-design.md`).
엔진은 원본을 읽고 우리 형식으로 눕히는 일만 하고, 실제 쓰기는 전부 위키의 내부 import
API(`/internal/wiki/import`)를 거친다 — 이 서비스의 DB에는 위키 테이블도 위키 id를 가리키는
FK도 없다.

## 파이프라인

```
발견(discover) → EXTRACT → NORMALIZE → MEDIA_COPY → RESOLVE → VERIFY → DONE
                                                                    ↘ 잡 마감 뒤 링크 정리 pass
```

| 단계 | 하는 일 |
|---|---|
| EXTRACT | 원본에서 페이지 스냅샷·댓글·지난 버전을 받아 `migration_payload`에 둔다 |
| NORMALIZE | storage XHTML → Document IR(v1 스키마 검증) |
| MEDIA_COPY | 첨부 바이트를 받아 **이 서비스 디스크**에 스테이징하고, 자산을 반영해 IR을 다시 만든다 |
| RESOLVE | IR → 마크다운 → 위키 import API로 문서·첨부·제한·댓글 |
| VERIFY | `GET /pages/{id}`로 제목·종류·본문 길이·라벨 수를 원본과 대조 |
| 링크 정리 | 잡이 끝난 뒤 임시 스킴 `dc-page:`를 실제 주소로 잇는다("이관 링크 정리" 리비전) |

## API

`/api/migration/**` — 게이트웨이가 JWT를 요구하고, 서비스가 org gRPC로 다시 판정한다.
계약은 위키에 있던 `/api/wiki/migrations/**`와 같고 접두사만 바뀌었다.

| 메서드 | 경로 | 권한 |
|---|---|---|
| POST | `/api/migration/confluence-dc/probe` | 전역 관리자 |
| GET | `/api/migration` | 전역 관리자 |
| POST | `/api/migration` | 대상 스페이스 ADMIN |
| POST | `/api/migration/{jobId}/items` | 대상 스페이스 ADMIN |
| POST | `/api/migration/{jobId}/discover` | 대상 스페이스 ADMIN |
| POST | `/api/migration/{jobId}/start`, `/cancel` | 대상 스페이스 ADMIN |
| GET | `/api/migration/{jobId}` | 대상 스페이스 ADMIN |
| GET | `/api/migration/{jobId}/items?status=&stage=&page=` | 대상 스페이스 ADMIN |
| GET | `/api/migration/{jobId}/report` | 대상 스페이스 ADMIN |

오류는 `{"error": 메시지}`(common-starter). org-service가 닿지 않으면 503이고, 그 밖의 권한
실패는 fail-closed(403)다.

## 위키 import API 호출 표

엔진이 위키에 하는 요청 전부. 모든 요청에 `X-Internal-Token`(공유 비밀)과
`X-Actor-Id`(잡 요청자)가 붙는다.

| 자바 메서드 | HTTP | 언제 |
|---|---|---|
| `createPage` | `POST /pages` | RESOLVE — 새 문서(지난 버전 1..k + 현재본 k+1) |
| `updatePage` | `PUT /pages/{id}` | RESOLVE — 원본이 바뀐 재이관(새 리비전 1건) |
| `replaceContent` | `PUT /pages/{id}/content` (`bumpVersion:false`) | 첨부 URL 정리(버전 불변) |
| `rewriteContent` | `PUT /pages/{id}/content` (`bumpVersion:true`) | 잡 마감 뒤 링크 정리 |
| `reorder` | `PUT /pages/{id}/order` | 원본에서 형제 순서만 바뀐 재이관 |
| `uploadAttachment` | `POST /pages/{id}/attachments` (multipart, 스트리밍) | RESOLVE — 스테이징한 첨부 |
| `createComment` | `POST /pages/{id}/comments` | RESOLVE — 원본 댓글·답글 |
| `commentExists` | `GET /comments/{id}` | 재실행 때 "사람이 지운 댓글" 판정 |
| `replaceRestrictions` | `PUT /pages/{id}/restrictions` | RESOLVE — 보기·편집 제한(fail-closed 판정은 엔진이 끝냈다) |
| `getPage` | `GET /pages/{id}` | VERIFY 대조, 대상 문서 생존 확인 |
| `findPagesByTitle` | `GET /spaces/{id}/pages?title=` | 제목 기반 링크 해석, 위키링크 모호 판정 |
| `getSpace` | `GET /spaces/{id}` | 잡 생성 때 대상 스페이스 존재 확인 |

`authorId`(댓글은 `authorId`, 재이관은 `editorId`)의 **유무**가 곧 "원본 작성자를 우리 계정으로
대조했는가"다. 못 찾았으면 비워 보내고, 위키가 `X-Actor-Id`를 작성자로 눕히면서 원본 이름을
"이관됨 · {이름}" 표시로 남긴다.

## DB

Flyway `V1__migration_engine.sql` 하나다. 위키에 있던 V6·V7·V34·V35·V36의 `migration_*`을 한 벌로
다시 쓴 것이고, 다른 점은 **위키 테이블을 참조하지 않는다**는 것뿐이다.

| 테이블 | 무엇 |
|---|---|
| `migration_job` | 잡의 수명주기(provider·대상 스페이스·모드·상태) |
| `migration_source` | 원본 접속 정보. `auth_token`은 **평문**이다(DB 접근 통제가 유일한 보호막) |
| `migration_item` | 원본 객체 하나의 단계·재시도·worker lease·형제 순서 |
| `migration_issue` | 손실·경고(코드 + 위치만, 원본 본문은 넣지 않는다) |
| `migration_payload` | 단계 산출물 — `SNAPSHOT`/`IR`/`MARKDOWN`/`MEDIA_MANIFEST`/`COMMENTS`/`HISTORY` |
| `migration_object_map` | 원본 객체 → 위키 문서·댓글의 이관 원장(재실행 멱등의 근거) |

`target_space_id`·`target_page_id`·`target_comment_id`는 다른 서비스의 id라 FK가 없다. 대상이
지워졌는지는 import API 조회로 판정하고, 남은 고아 매핑은 다음 이관이 새로 만든다.

## 환경변수

| 이름 | 기본값 | 뜻 |
|---|---|---|
| `MIGRATION_DB_URL` | `jdbc:postgresql://localhost:5433/migrationdb` | DB |
| `MIGRATION_DB_USERNAME` / `MIGRATION_DB_PASSWORD` | `keycloak` / `keycloak` | DB 계정 |
| `WIKI_BACKEND_URI` | `http://localhost:9110` | 위키 **내부망** 주소. 게이트웨이·nginx를 거치지 않는다 |
| `WIKI_INTERNAL_TOKEN` | (빈 값) | 위키 내부 API 공유 비밀. wiki-backend와 **같은 값**이어야 한다 |
| `WIKI_IMPORT_CONNECT_TIMEOUT` / `WIKI_IMPORT_REQUEST_TIMEOUT` | `PT10S` / `PT60S` | 요청 타임아웃(워커 lease 5분보다 짧게) |
| `MIGRATION_STAGING_DIR` | `./data/migration-staging` | 첨부 스테이징. 컨테이너에서는 볼륨을 붙인다 |
| `ORG_GRPC_HOST` / `ORG_GRPC_PORT` | `localhost` / `9131` | org-service gRPC |
| `AUTH_JWKS_URI` | `http://localhost:9000/.well-known/jwks.json` | JWT 검증 |
| `PLATFORM_ISSUER` / `PLATFORM_AUDIENCE` | `http://localhost:9000` / `platform-api` | JWT issuer·audience |
| `EUREKA_URI` | `http://localhost:8761/eureka` | 서비스 등록 |
| `MIGRATION_WORKER_ENABLED` | `true` | 워커 스케줄러 on/off |
| `MIGRATION_DC_PAGE_SIZE` | `100` | DC 목록 한 묶음(상한 200) |
| `MIGRATION_MAX_ATTACHMENT_BYTES` | `104857600` | 첨부 상한. 파일을 통째로 메모리에 담는다 |
| `MIGRATION_HISTORY_VERSIONS` | `10` | 함께 옮길 지난 버전 수(0이면 현재본만) |
| `MIGRATION_MAX_HISTORY_VERSION_BYTES` | `2097152` | 지난 버전 본문 하나의 상한 |

워커 세부(`platform.migration.worker.*`): `lease`(PT5M) · `retry-backoff`(PT30S)~`retry-backoff-max`(PT30M) ·
`max-attempts`(5) · `batch-size`(25).
DC 세부(`platform.migration.dc.*`): `connect-timeout`(PT10S) · `read-timeout`(PT60S) · `max-pages`(5000) ·
`child-page-size`(200) · `comment-page-size`(100).

## 배선 (X3에서 한다)

- **gateway**: `/api/migration/**` → `lb://migration-service`(JWT 필수). `/internal/**`은 라우팅하지 않는다.
- **nginx**: `/api/` 정규식이 이미 게이트웨이로 보낸다 — 변경 없음.
- **compose**: `migration-service` + `migration-db-init`(agent-service 온보딩 절차 그대로),
  `MIGRATION_STAGING_DIR` 볼륨, 위 env. wiki-backend에도 같은 `WIKI_INTERNAL_TOKEN`을 넣는다.
- **deploy.yml**: 서비스 목록에 `migration-service` 추가. GHCR `ghcr.io/chanho4702/migration-service`.
- **wiki-front**: 스토어 어댑터 base path만 `/api/migration`으로.

## 컨플루언스 설치형(Server/DC) 이관 — 운영 가이드

M1~M3로 파이프라인이 실제로 돈다. 관리 화면은 wiki-front `/admin/migrations`(전역 관리자).
⚠️ 실기 DC로 실측하기 전이라 지원 버전을 보장하지 않는다 — 기본 가정은 7.19 LTS~9.x,
개인 액세스 토큰(PAT, 7.9+).

**준비(운영자)**

1. 원본 DC에서 이관 계정으로 PAT를 발급한다(스페이스 보기 + 첨부 다운로드 권한). 토큰은 잡 생성
   요청 본문에만 들어가고 응답·화면·로그 어디에도 다시 나오지 않지만, DB `migration_source.auth_token`
   에는 **평문**으로 저장된다 — DB 접근 통제로 보호하고, 이관이 끝나면 원본 쪽에서 토큰을 폐기한다.
2. migration-service가 원본 DC로 **아웃바운드 HTTPS**를 열 수 있어야 한다. 요청은 `base_url` +
   고정 경로만 조합하고 원본 응답의 `_links`·리다이렉트를 따라가지 않는다.
3. `WIKI_INTERNAL_TOKEN`을 wiki-backend와 같은 값으로 넣는다. 위키 쪽에서 비어 있으면
   `/internal/**`이 전면 차단되고 이관은 한 건도 쓰지 못한다.
4. 워커를 켠다: `MIGRATION_WORKER_ENABLED=true`. 여러 노드가 켜져 있으면 lease로 나눠 갖는다.
5. 첨부 저장소 용량: 원본 스페이스 첨부 합계만큼. dry-run 보고서의 `ATTACHMENT_PLANNED` 합계로
   미리 본다. 스테이징 디렉터리에도 같은 크기가 잠시 쌓인다.

**절차(관리자, 화면 기준)**

1. `/admin/migrations` → 새 잡: DC URL·스페이스 키·PAT 입력 → **연결 확인**(스페이스 이름·페이지 수).
   대상은 **빈 스페이스**를 권장한다.
2. 모드 **dry-run**으로 잡 생성 → **발견**(페이지·블로그를 조상 깊이순으로 등록) → **시작**.
   보고서에서 미지원 매크로(`MACRO_OPAQUE`)·미매핑 사용자(`AUTHOR_UNMAPPED`,
   `RESTRICTION_PRINCIPAL_UNMAPPED`)·첨부 계획을 확인한다. dry-run은 원본을 내려받지도, 문서를
   만들지도 않는다.
3. 같은 원본으로 모드 **import** 잡을 만들어 발견 → 시작. 중단되면 같은 잡을 다시 시작하면 이어서 한다.
4. 완료 뒤 보고서의 `LINK_UNRESOLVED`·`ATTACHMENT_*`·`VERIFY_*`·데드레터를 확인한다. 데드레터
   (`DC_NOT_FOUND`, `WIKI_IMPORT_*` 등)는 원인을 고친 뒤 **재발견 → 재시작**하면 그 항목만 다시
   처리된다(완료 항목은 같은 checksum이면 건너뛴다).

**재실행·멱등 규칙**: 같은 원본 페이지(id+version)는 한 번만 만든다. 원본이 바뀌어 checksum이
달라지면 제목·본문·라벨을 갱신하고 새 리비전("컨플루언스 재이관 v{n}")을 남긴다. 첨부는 같은
checksum이면 위키가 `UNCHANGED`로 답해 버전을 쌓지 않고, 댓글·이력은 최초 이관에만 만든다.

**옮겨지는 것 / 아닌 것**

| 옮겨진다 | 옮겨지지 않는다(보고서에 남음) |
|---|---|
| 페이지 트리·형제 순서·제목·본문(storage XHTML → IR → 마크다운)·라벨·생성/수정 시각 | 애드온 매크로 본문(Jira·Draw.io 등, `opaque` 패널로 자리만) |
| 첨부 최신본(이미지는 본문에서 인라인, 그 외는 링크) | 첨부의 지난 버전, 상한 초과 파일(`ATTACHMENT_TOO_LARGE`) |
| 페이지·블로그 댓글(원본 작성자 이름·시각), 답글은 1단계로 | 인라인 댓글의 앵커(페이지 댓글로 강등 + 원문 인용) |
| 지난 버전 N개(기본 10)를 리비전으로, 편집자 이름·변경 요약 | 스페이스 권한(그룹·역할) — 보고서 요약만 |
| 페이지 제한(보기/편집) — **미매핑 주체는 요청자 단독 제한**(fail-closed) | 사용자 계정 자체(org `LookupMembers` 이름·이메일 대조만) |
| 같은 스페이스 안 페이지 링크(제목 기반 `[[제목]]` + ID 기반 재작성) | 다른 스페이스·외부 사이트 링크(원본 URL 유지) |

**원본 부하**: 페이지당 본문 1회 + 첨부 수 + 댓글 묶음 수 + 지난 버전 N회를 호출한다.
500페이지·10버전이면 5,000회가 더 붙는다 — 운영 중인 DC라면 `MIGRATION_HISTORY_VERSIONS`를
낮추거나 업무 외 시간에 돌린다. 429/5xx는 지수 백오프로 재시도한다.

**위키 부하**: 문서당 쓰기 1~2회 + 첨부 수 + 댓글 수 + 존재 확인 조회 몇 회다. 조회는 대상 문서가
아직 살아 있는지 보는 것이라 뺄 수 없다 — 그것을 믿고 건너뛰면 지워진 문서에 첨부를 붙이려다
데드레터가 난다.

## 빌드

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-24"
.\gradlew.bat test --no-daemon
.\gradlew.bat bootJar --no-daemon    # build/libs/app.jar → Dockerfile이 복사
```

GitHub Packages(`com.platform:common-proto`·`common-starter` `0.16.0`) 인증은 `GITHUB_TOKEN` env
또는 `~/.gradle/gradle.properties`의 `gpr.token`.

## 구조

```
src/main/java/com/platform/migrationservice/
├─ config/         SecurityConfig(경로 정책 + org gRPC 채널), JwtPrincipal
├─ permission/     org gRPC 권한 클라이언트(30초 캐시, 불능 시 503)
├─ wiki/           WikiImportApi 계약 + JDK HttpClient 구현, 라벨 정규화
└─ migration/      엔진 — 잡 REST·worker·DC 클라이언트·정규화기·IR·단계 handler
   ├─ confluence/  DC 추출·정규화·핸들러 5종·첨부·댓글·제한·링크
   ├─ ir/          Document IR 검증기와 마크다운 writer
   ├─ notion/      노션 스냅샷 정규화기(현재 미주입 — 라이브 추출기는 범위 밖)
   └─ worker/      점유(lease)·재시도·데드레터·잡 마감
```
