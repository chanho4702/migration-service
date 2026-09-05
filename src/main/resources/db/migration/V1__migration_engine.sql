-- 이관 엔진의 원장 전체. wiki-backend의 V6·V7·V34·V35·V36에 흩어져 있던 migration_* 테이블을
-- 한 벌로 다시 쓴 것이다(엔진 분리, 설계 §3).
--
-- 위키 쪽 판과 다른 점은 하나뿐이고, 그게 서비스를 가른 이유다: **위키 테이블을 참조하지 않는다.**
-- 예전에는 target_space_id → space(id), target_page_id → page(id), target_comment_id →
-- page_comment(id)가 전부 FK였다. 이제 그 값들은 다른 서비스의 id이므로 DB가 지켜 줄 수 없다 —
-- 대상이 지워졌는지는 이관 엔진이 위키 import API로 조회해 판정한다(RESOLVE·VERIFY의 존재 확인).
-- 그 대가로 고아 매핑이 남을 수 있는데, 그것은 조회로 걸러지고 다음 이관이 새로 만든다.
--
-- page.imported_author_name / imported_source_url은 여기 없다. 그 컬럼은 위키 문서의 속성이고
-- 위키가 소유한다 — 엔진은 값을 실어 보낼 뿐이다.

CREATE TABLE migration_job (
    id                  BIGSERIAL PRIMARY KEY,
    provider            VARCHAR(32)  NOT NULL,
    source_instance_id  VARCHAR(255) NOT NULL CHECK (source_instance_id <> ''),
    -- 위키의 스페이스 id다. FK를 걸 수 없으므로 존재 확인은 잡 생성 때 위키에 물어서 한다.
    target_space_id     BIGINT,
    requested_by        BIGINT       NOT NULL,
    mode                VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lock_version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_migration_job_provider
        CHECK (provider IN ('NOTION', 'CONFLUENCE_DC')),
    CONSTRAINT chk_migration_job_mode
        CHECK (mode IN ('DRY_RUN', 'IMPORT')),
    CONSTRAINT chk_migration_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_migration_job_timestamps
        CHECK ((status = 'PENDING' AND started_at IS NULL AND completed_at IS NULL)
            OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
            OR (status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                AND started_at IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX idx_migration_job_poll
    ON migration_job (status, created_at, id);

-- 원본 접속 정보. 잡마다 한 행이다.
CREATE TABLE migration_source (
    job_id             BIGINT       PRIMARY KEY REFERENCES migration_job (id) ON DELETE CASCADE,
    base_url           VARCHAR(512) NOT NULL CHECK (base_url <> ''),
    space_key          VARCHAR(255) NOT NULL CHECK (space_key <> ''),
    -- 평문 저장이다. 지금은 DB 접근 통제가 유일한 보호막이고, 암호화 키 관리(어디에 두고 어떻게
    -- 교체하는가)는 후속 ADR에서 정한다. 어떤 응답 DTO에도 이 값을 싣지 않는다(기획 P8).
    auth_token         TEXT         NOT NULL CHECK (auth_token <> ''),
    discovered_count   INTEGER      NOT NULL DEFAULT 0 CHECK (discovered_count >= 0),
    discovered_at      TIMESTAMPTZ,
    source_space_name  VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lock_version       BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE migration_item (
    id                  BIGSERIAL PRIMARY KEY,
    job_id              BIGINT        NOT NULL REFERENCES migration_job (id) ON DELETE CASCADE,
    source_key          VARCHAR(64)   NOT NULL CHECK (source_key ~ '^[a-f0-9]{64}$'),
    external_object_id  VARCHAR(512)  NOT NULL CHECK (external_object_id <> ''),
    source_version      VARCHAR(100),
    source_checksum     VARCHAR(64)   NOT NULL CHECK (source_checksum ~ '^[a-f0-9]{64}$'),
    payload_ref         VARCHAR(1024) NOT NULL CHECK (payload_ref <> ''),
    stage               VARCHAR(16)   NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    retry_count         INTEGER       NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    next_attempt_at     TIMESTAMPTZ,
    -- 위키의 페이지 id. FK 없음(다른 서비스) — 살아 있는지는 import API 조회로 본다.
    target_page_id      BIGINT,
    last_error_code     VARCHAR(128),
    dead_lettered_at    TIMESTAMPTZ,
    -- 같은 부모 아래에서 원본이 정한 순서. NULL은 "원본 순서를 모른다"이고, 그때는 발견 순서를 쓴다.
    sibling_order       INTEGER,
    -- 다중 노드 worker의 점유. 노드가 죽으면 lease가 만료돼 다른 노드가 같은 item을 다시 집는다.
    claimed_by          VARCHAR(64),
    claim_token         VARCHAR(36),
    lease_expires_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_migration_item_source UNIQUE (job_id, source_key),
    CONSTRAINT uk_migration_item_job_id UNIQUE (job_id, id),
    CONSTRAINT chk_migration_item_stage
        CHECK (stage IN ('EXTRACT', 'NORMALIZE', 'MEDIA_COPY', 'RESOLVE', 'VERIFY', 'DONE')),
    CONSTRAINT chk_migration_item_status
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'COMPLETED', 'DEAD_LETTER')),
    CONSTRAINT chk_migration_item_done
        CHECK ((stage = 'DONE') = (status = 'COMPLETED')),
    CONSTRAINT chk_migration_item_retry
        CHECK ((status = 'RETRY_WAIT') = (next_attempt_at IS NOT NULL)),
    CONSTRAINT chk_migration_item_dead_letter
        CHECK ((status = 'DEAD_LETTER') = (dead_lettered_at IS NOT NULL)
            AND (status <> 'DEAD_LETTER' OR last_error_code IS NOT NULL)),
    -- RUNNING이면 소유자·점유 토큰·만료가 반드시 함께 있다. 셋 중 하나만 빠져도 회수가 성립하지 않는다.
    CONSTRAINT chk_migration_item_lease
        CHECK ((status = 'RUNNING')
            = (claimed_by IS NOT NULL AND claimed_by <> ''
                AND claim_token IS NOT NULL AND claim_token <> ''
                AND lease_expires_at IS NOT NULL))
);
CREATE INDEX idx_migration_item_poll
    ON migration_item (job_id, status, next_attempt_at, id);
-- 만료된 lease 회수는 status/만료 시각으로만 훑는다(job 범위와 무관한 전역 스윕).
CREATE INDEX idx_migration_item_lease
    ON migration_item (status, lease_expires_at);

CREATE TABLE migration_issue (
    id                BIGSERIAL PRIMARY KEY,
    job_id            BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    issue_key         VARCHAR(64)   NOT NULL CHECK (issue_key ~ '^[a-f0-9]{64}$'),
    severity          VARCHAR(16)   NOT NULL,
    code              VARCHAR(128)  NOT NULL CHECK (code <> ''),
    source_path       VARCHAR(1024) NOT NULL CHECK (source_path <> ''),
    occurrence_count  INTEGER       NOT NULL DEFAULT 1 CHECK (occurrence_count > 0),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_migration_issue_key UNIQUE (item_id, issue_key),
    CONSTRAINT fk_migration_issue_item
        FOREIGN KEY (job_id, item_id) REFERENCES migration_item (job_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_migration_issue_severity
        CHECK (severity IN ('INFO', 'WARNING', 'ERROR'))
);
CREATE INDEX idx_migration_issue_job
    ON migration_issue (job_id, severity, id);

-- 단계 산출물. 한 item당 종류별로 최대 한 행이고, 재실행하면 같은 행을 덮어쓴다 —
-- 이력을 쌓으면 500페이지 스페이스를 두 번만 돌려도 본문이 세 벌씩 눌러앉는다.
--
-- MEDIA_MANIFEST는 받아 둔 첨부의 목록이다. 위키 저장소 좌표는 담지 않는다(엔진 분리) —
-- 파일명·형식·크기·checksum과 우리 스테이징 디렉터리의 위치만 있고, RESOLVE가 그 파일을
-- 위키로 스트리밍한다.
CREATE TABLE migration_payload (
    id          BIGSERIAL   PRIMARY KEY,
    item_id     BIGINT      NOT NULL REFERENCES migration_item (id) ON DELETE CASCADE,
    kind        VARCHAR(16) NOT NULL,
    body        TEXT        NOT NULL,
    -- VARCHAR(64)다. CHAR(64)로 두면 Postgres가 bpchar로 잡아 ddl-auto=validate가 부팅을 거부한다.
    checksum    VARCHAR(64) NOT NULL CHECK (checksum ~ '^[a-f0-9]{64}$'),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_migration_payload UNIQUE (item_id, kind),
    CONSTRAINT chk_migration_payload_kind
        CHECK (kind IN ('SNAPSHOT', 'IR', 'MARKDOWN', 'MEDIA_MANIFEST', 'COMMENTS', 'HISTORY'))
);

-- 원본 객체 → 위키 대상의 원장. source_key는 provider + instance + external ID의 SHA-256이다.
-- 길이가 긴 외부 ID를 그대로 unique index에 넣어 btree row size 제한을 넘기지 않으면서
-- 재실행 멱등성을 유지한다.
--
-- target_comment_id가 따로 있는 이유: 댓글 행은 target_page_id를 쓸 수 없다. 위키 쪽에서 그
-- 컬럼은 페이지 id였고(지금은 FK가 없지만 의미는 그대로다), 링크 정리 pass가 target_page_id가
-- NULL인 행을 건너뛰므로 댓글 행이 그 순회에 섞이지 않는다.
CREATE TABLE migration_object_map (
    id                  BIGSERIAL PRIMARY KEY,
    source_key          VARCHAR(64)  NOT NULL UNIQUE CHECK (source_key ~ '^[a-f0-9]{64}$'),
    provider            VARCHAR(32)  NOT NULL,
    source_instance_id  VARCHAR(255) NOT NULL CHECK (source_instance_id <> ''),
    external_object_id  VARCHAR(512) NOT NULL CHECK (external_object_id <> ''),
    source_version      VARCHAR(100),
    source_checksum     VARCHAR(64)  NOT NULL CHECK (source_checksum ~ '^[a-f0-9]{64}$'),
    target_page_id      BIGINT,
    target_comment_id   BIGINT,
    last_job_id         BIGINT REFERENCES migration_job (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lock_version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_migration_object_provider
        CHECK (provider IN ('NOTION', 'CONFLUENCE_DC'))
);
-- 제목으로 찾은 문서가 우리가 옮긴 것인지 되짚을 때 쓴다(링크 앵커 대조).
CREATE INDEX idx_migration_object_map_target_page
    ON migration_object_map (target_page_id);
