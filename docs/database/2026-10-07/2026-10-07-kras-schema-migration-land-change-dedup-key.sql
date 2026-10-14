-- 2026-10-07 마이그레이션: kras.land_change_event에 자연키 유일 제약 추가
--
-- 근거: docs/superpowers/specs/2026-10-07/2026-10-07-kras-incremental-sync-design.md §5
--   - 현재 유일 제약은 UNIQUE(source_item_id,record_no)뿐이라, 같은 기간을 다시 조회하면
--     (재시도·겹친 윈도우) 같은 이동 사건이 매번 새 행으로 들어간다.
--   - org_cd + land_mov_no + hndl_ymd를 사건의 자연키로 가정해 제약을 추가한다.
--
-- 확인 필요(V-1, 설계 문서 11절): land_mov_no가 기관 내에서 hndl_ymd와 함께 실제로 유일한지는
--   운영 응답으로 아직 확인하지 못했다. 가정이 틀리면(예: land_mov_no가 재사용되거나 같은 사건
--   안에서도 변할 수 있다면) 이 제약을 걷어내고 다른 키로 다시 잡아야 한다 — 그래서 코드(
--   KrasLandChangeReflectionService)는 이 제약에 의존하지 않고, 멱등적으로 짜여 있다
--   (같은 이벤트를 두 번 반영해도 최종 상태가 같다).
--
-- 영향: land_mov_no 또는 hndl_ymd가 NULL인 행은 PostgreSQL 규칙상 서로 다른 값으로 취급되어
--   제약에 걸리지 않는다(기존 데이터에 영향 없음). land_change는 아직 서비스 ID가 없어 실제
--   수집 건이 없으므로(설계 문서 3절) 기존 행과 충돌할 가능성도 없다.
BEGIN;

ALTER TABLE kras.land_change_event
    ADD CONSTRAINT land_change_event_natural_key UNIQUE (org_cd, land_mov_no, hndl_ymd);

COMMENT ON CONSTRAINT land_change_event_natural_key ON kras.land_change_event IS
    '사건 자연키(가정, V-1 확인 전). 재시도·겹친 기간 재조회 시 중복 적재를 막는다.';

COMMIT;
