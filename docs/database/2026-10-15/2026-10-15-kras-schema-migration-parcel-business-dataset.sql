-- 2026-10-15 마이그레이션: shr_ymb·own_rgt_hist·collective_building이 kras.parcel을 갱신할 수 있게 허용
--
-- 배경: 세 매퍼는 응답의 필지 식별자로 kras.parcel을 자연키 UPSERT하도록 선언돼 있는데(설계 §6.2),
--   business_dataset에 (parcel, 데이터셋) 짝이 없어 guard_business_row가 'Dataset % cannot populate %'로 반영을 막았다.
--   PNU 대기열 실서버 확인(mock 게이트웨이)에서 발견, 매퍼·권한 대조 테스트 KrasMapperBusinessGrantDbIntegrationTest로 고정.
-- 이미 있으면 건너뛴다 — 여러 번 실행해도 안전하다.

INSERT INTO kras.business_dataset(table_name, dataset_code) VALUES
    ('parcel', 'shr_ymb'),
    ('parcel', 'own_rgt_hist'),
    ('parcel', 'collective_building')
ON CONFLICT DO NOTHING;
