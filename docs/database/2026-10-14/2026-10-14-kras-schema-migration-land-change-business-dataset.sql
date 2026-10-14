-- 2026-10-14 마이그레이션: land_change가 kras.parcel / kras.land_basic 현재 상태를 갱신할 수 있게 허용
--
-- 배경: 토지이동 변동내역(land_change) 승격 직후 KrasLandChangeReflectionService가 이벤트를
--   필지 현재 상태(parcel, land_basic)에 반영한다. guard_business_row 트리거는
--   business_dataset에 (테이블, 데이터셋) 쌍이 없으면 'Dataset % cannot populate %'로 막는다.
-- 설계: docs/superpowers/specs/2026-10-14/2026-10-14-kras-incremental-sync-remaining-design.md §4.4 (P-1)
-- 이미 있으면 건너뛴다 — 여러 번 실행해도 안전하다.

INSERT INTO kras.business_dataset(table_name, dataset_code) VALUES
    ('parcel', 'land_change'),
    ('land_basic', 'land_change')
ON CONFLICT DO NOTHING;
