-- 2026-10-15 마이그레이션: PNU 단위 API 전체·보정 적재 대기열 kras.pnu_work
--
-- 한 행 = 기관 × 데이터셋 × PNU (× 건물번호). 대기열이면서 "마지막으로 언제 받았나" 기록을 겸한다.
-- kras.sync_work는 item_id NOT NULL(한 수집 건의 하위 작업 전용)이라 쓰지 않는다.
-- 설계: docs/superpowers/specs/2026-10-15/2026-10-15-kras-pnu-api-full-incremental-design.md §4
-- 이미 있으면 건너뛴다 — 여러 번 실행해도 안전하다.

CREATE TABLE IF NOT EXISTS kras.pnu_work (
    org_cd          kras.org_code NOT NULL,
    dataset_code    varchar(80)   NOT NULL REFERENCES kras.sync_dataset(dataset_code),
    pnu             kras.pnu_code NOT NULL,
    extra_key       varchar(64)   NOT NULL DEFAULT '',
    extra_params    jsonb         NOT NULL DEFAULT '{}',
    reason          varchar(10)   NOT NULL CHECK (reason IN ('CHANGE','CHILD','FULL','ANNUAL','ROTATE')),
    priority        smallint      GENERATED ALWAYS AS (CASE reason WHEN 'CHANGE' THEN 0 WHEN 'CHILD' THEN 1
                                  WHEN 'FULL' THEN 2 WHEN 'ANNUAL' THEN 3 ELSE 4 END) STORED,
    status          varchar(10)   NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','RETRY','DONE','FAILED','SKIPPED')),
    attempt_count   integer       NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz   NOT NULL DEFAULT now(),
    lease_until     timestamptz,
    pending_item_id bigint REFERENCES kras.sync_item(item_id),
    queued_at       timestamptz   NOT NULL DEFAULT now(),
    last_success_at timestamptz,
    last_item_id    bigint REFERENCES kras.sync_item(item_id),
    last_error      text,
    PRIMARY KEY (org_cd, dataset_code, pnu, extra_key),
    CHECK (org_cd = substring(pnu FROM 1 FOR 5))
);
CREATE INDEX IF NOT EXISTS ix_pnu_work_due ON kras.pnu_work (org_cd, priority, queued_at)
    WHERE status IN ('PENDING','RETRY');
CREATE INDEX IF NOT EXISTS ix_pnu_work_fresh ON kras.pnu_work (org_cd, dataset_code, last_success_at);
COMMENT ON TABLE kras.pnu_work IS 'PNU 단위 API 적재 대기열 + 최신성 기록. lease_until=처리 중 표시, pending_item_id=수집 후 반영 전.';
COMMENT ON COLUMN kras.pnu_work.last_error IS '마스킹된 오류 요약만. 응답 원문(개인정보) 금지.';
