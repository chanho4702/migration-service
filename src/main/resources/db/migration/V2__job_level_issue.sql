-- 잡 단위 손실 기록 — 어느 항목에도 매달 수 없는 실패를 남길 자리.
--
-- 링크 정리 pass는 잡이 끝난 뒤 커밋 밖에서 돈다. 거기서 난 실패는 지금까지 로그로만 남았고,
-- 보고서에는 "완료"만 보였다 — 옮기기는 끝났는데 문서 사이 링크가 원본 사이트로 튕기는 상태를
-- 관리자가 알 길이 없었다. 그 실패를 issue로 남기려면 item_id 없는 행이 필요하다.
--
-- unique 제약이 둘로 갈리는 이유: PostgreSQL은 NULL을 서로 다른 값으로 보므로
-- uk_migration_issue_key(item_id, issue_key)가 item_id IS NULL 행에는 걸리지 않는다.
-- 그대로 두면 재시도마다 같은 실패가 새 행으로 쌓인다.
ALTER TABLE migration_issue ALTER COLUMN item_id DROP NOT NULL;

CREATE UNIQUE INDEX uk_migration_issue_job_key
    ON migration_issue (job_id, issue_key) WHERE item_id IS NULL;

-- 기존 cascade는 (job_id, item_id) → migration_item을 탄다. item_id가 NULL이면 그 FK가
-- 아무것도 강제하지 않으므로, 잡을 지웠을 때 잡 단위 issue만 고아로 남는다.
ALTER TABLE migration_issue
    ADD CONSTRAINT fk_migration_issue_job
        FOREIGN KEY (job_id) REFERENCES migration_job (id) ON DELETE CASCADE;
