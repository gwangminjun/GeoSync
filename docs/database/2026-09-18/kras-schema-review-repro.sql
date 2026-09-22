-- 2026-09-16 검토(F1~F4) 회귀 재현 스크립트 — 2차 갱신본.
--
-- 최초 버전은 kras-schema-review.md 작성 시점의 kras-schema-create.sql을 대상으로
-- F1~F4가 "예외 없이 끝나면 재현됨(버그 있음)"이라고 판정했다. 이후 같은 파일에
-- guard_business_row(F3), guard_api_response/guard_api_bundle(F1),
-- guard_spatial_release/spatial_release_expected(F2), record_no NOT NULL(F4) 등의
-- 트리거·제약이 추가됐다. 그래서 이 버전은 판정을 뒤집는다:
--   "각 블록이 예외로 막히면 수정됨(NOTICE로 표시)"
--   "예외 없이 원래 시퀀스가 끝까지 성공하면 회귀(REGRESSION 예외로 중단)"
--
-- 범위: 문서화된 4개 재현 시나리오가 다시 통과하지 않는지만 확인하는 회귀 테스트다.
-- 계약 검증→bundle 게시, 카탈로그→release 게시가 끝까지 성공하는 정상 경로(golden path)
-- 테스트는 다루지 않는다 — 필요하면 별도 스크립트로 작성한다.
--
-- 신규 임시 DB에 kras-schema-create.sql 적용 후 실행. 운영 DB에서 실행하지 말 것.
-- 전부 ROLLBACK되나 identity sequence 값은 소비된다.
BEGIN;
DO $review$
DECLARE
    ra bigint; rb bigint; ia bigint; ib bigint; ig bigint; il bigint; iz1 bigint; iz2 bigint;
    response_id_ bigint; bundle_id_ bigint; release_id_ bigint;
    p text := '4687010200100010000';   -- 정상 셋업용 PNU (org 46870)
    p2 text := '4687010200100020000';  -- F3 교차 기관 시도 전용 PNU (p와 충돌 방지)
BEGIN
    INSERT INTO kras.sync_run(org_cd,job_kind,period_start,period_end_exclusive)
      VALUES ('46870','DAILY','2026-09-01','2026-09-02') RETURNING run_id INTO ra;
    INSERT INTO kras.sync_run(org_cd,job_kind,period_start,period_end_exclusive)
      VALUES ('27170','DAILY','2026-09-01','2026-09-02') RETURNING run_id INTO rb;
    INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive,status)
      VALUES (ra,'46870','land_info','ALL','2026-09-01','2026-09-02','FAILED') RETURNING item_id INTO ia;
    INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive)
      VALUES (rb,'27170','land_info','ALL','2026-09-01','2026-09-02') RETURNING item_id INTO ib;
    -- 정상(같은 기관, 실패 아닌) item. F1/F4가 참조할 parcel(p)을 합법적으로 만들기 위해 필요.
    -- 동시에 guard_business_row가 "정상" 같은-기관 삽입까지 오탐으로 막지 않는지도 확인한다.
    INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive,attempt_no)
      VALUES (ra,'46870','land_info','ALL','2026-09-01','2026-09-02',2) RETURNING item_id INTO ig;
    INSERT INTO kras.parcel(pnu,org_cd,adm_sect_cd,land_loc_cd,ledg_gbn,bobn,bubn,source_item_id)
      VALUES(p,'46870','46870','10200','1','0001','0000',ig);
    RAISE NOTICE 'setup ok: legitimate same-org parcel insert still succeeds (guard_business_row does not false-positive)';
    -- F4 전용 item: land_change_event가 실제로 매핑되는 dataset_code='land_change', 같은 기관,
    -- 실패 아닌 상태. ia(FAILED)를 그대로 쓰면 guard_business_row의 상태 체크가 먼저 걸려서
    -- record_no NOT NULL 수정 자체를 검증하지 못하고 다른 이유로 막힌 것처럼 보인다.
    INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive)
      VALUES (ra,'46870','land_change','ALL','2026-09-01','2026-09-02') RETURNING item_id INTO il;

    -- F3: 필지의 기관과 source_item의 기관이 달라도 통과 → 이제 guard_business_row가 막아야 한다.
    BEGIN
      INSERT INTO kras.parcel(pnu,org_cd,adm_sect_cd,land_loc_cd,ledg_gbn,bobn,bubn,source_item_id)
        VALUES(p2,'46870','46870','10200','1','0002','0000',ib);
      INSERT INTO kras.land_basic(pnu,parea,source_item_id) VALUES(p2,123,ib);
      RAISE EXCEPTION 'REGRESSION: F3 still reproducible (cross-org business row accepted)';
    EXCEPTION WHEN OTHERS THEN
      IF SQLERRM LIKE 'REGRESSION:%' THEN RAISE; END IF;
      RAISE NOTICE 'F3 fixed: %', SQLERRM;
    END;

    -- F1: XML이 well-formed이면 오류 응답도 COLLECTED로 저장·게시 가능
    --     → 이제 guard_api_response(계약 미검증/데이터셋 미검증/성공코드 불일치 등)가 막아야 한다.
    BEGIN
      INSERT INTO kras.api_response(source_item_id,org_cd,source_system,service_code,pnu,request_key,
        contract_version,dataset_code,response_xml,source_result_code,data_state,response_hash)
        VALUES(ia,'46870','KRAS','KRAS000002',p,repeat('a',64),'1','land_info',
        '<RESPONSE><HEADER><CODE>9999</CODE></HEADER><BODY/></RESPONSE>',
        '9999','COLLECTED',repeat('b',64)) RETURNING response_id INTO response_id_;
      INSERT INTO kras.api_bundle(org_cd,pnu,bundle_kind,status,is_complete)
        VALUES('46870',p,'LAND','PUBLISHED',true) RETURNING bundle_id INTO bundle_id_;
      INSERT INTO kras.api_bundle_member(bundle_id,org_cd,pnu,request_key,response_id)
        VALUES(bundle_id_,'46870',p,repeat('a',64),response_id_);
      INSERT INTO kras.api_publication(org_cd,pnu,bundle_kind,bundle_id)
        VALUES('46870',p,'LAND',bundle_id_);
      RAISE EXCEPTION 'REGRESSION: F1 still reproducible (failing response reached publication)';
    EXCEPTION WHEN OTHERS THEN
      IF SQLERRM LIKE 'REGRESSION:%' THEN RAISE; END IF;
      RAISE NOTICE 'F1 fixed: %', SQLERRM;
    END;

    -- F2: 한 release의 성공 레이어만 노출되고 실패 레이어는 조용히 빠짐
    --     → release는 DRAFT로만 생성되고 validate_spatial_release를 통과해야 PUBLISHED로
    --       전환되므로 직접 삽입은 막혀야 한다(그 이전에 item을 INSERT로 바로 SUCCESS
    --       처리하는 것 자체도 guard_item_transition이 막는다).
    BEGIN
      INSERT INTO kras.spatial_layer(layer_code,dataset_code,source_epsg)
        VALUES('REVIEW_A','usezone_file',5186),('REVIEW_B','usezone_file',5186);
      INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive,status,is_complete)
        VALUES(ra,'46870','usezone_file','LAYER:REVIEW_A','2026-09-01','2026-09-02','SUCCESS',true) RETURNING item_id INTO iz1;
      INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive,status,is_complete)
        VALUES(ra,'46870','usezone_file','LAYER:REVIEW_B','2026-09-01','2026-09-02','FAILED',false) RETURNING item_id INTO iz2;
      INSERT INTO kras.spatial_feature(item_id,feature_no,org_cd,layer_code,pnu,geom)
        VALUES(iz1,1,'46870','REVIEW_A',p,ST_GeomFromText('MULTIPOLYGON(((0 0,0 1,1 1,1 0,0 0)))',5186)),
              (iz2,1,'46870','REVIEW_B',p,ST_GeomFromText('MULTIPOLYGON(((0 0,0 1,1 1,1 0,0 0)))',5186));
      INSERT INTO kras.usezone_feature(item_id,feature_no,org_cd,layer_code,pnu,geom)
        SELECT item_id,feature_no,org_cd,layer_code,pnu,geom FROM kras.spatial_feature WHERE item_id IN(iz1,iz2);
      INSERT INTO kras.spatial_release(org_cd,status) VALUES('46870','PUBLISHED') RETURNING release_id INTO release_id_;
      INSERT INTO kras.spatial_release_member(release_id,org_cd,layer_code,item_id)
        VALUES(release_id_,'46870','REVIEW_A',iz1),(release_id_,'46870','REVIEW_B',iz2);
      INSERT INTO kras.spatial_publication(org_cd,release_id) VALUES('46870',release_id_);
      RAISE EXCEPTION 'REGRESSION: F2 still reproducible (partial release published)';
    EXCEPTION WHEN OTHERS THEN
      IF SQLERRM LIKE 'REGRESSION:%' THEN RAISE; END IF;
      RAISE NOTICE 'F2 fixed: %', SQLERRM;
    END;

    -- F4: 원본행 식별자가 NULL이면 동일 event를 무제한 중복 적재할 수 있음
    --     → record_no가 NOT NULL이 되어 애초에 행번호 없이는 삽입 자체가 안 돼야 한다.
    --     (il은 land_change 데이터셋·같은 기관·정상 상태라 guard_business_row는 통과하고
    --      record_no NOT NULL 자체에서 막히는지를 격리해서 본다.)
    BEGIN
      INSERT INTO kras.land_change_event(org_cd,land_mov_no,source_item_id,payload,payload_hash)
        VALUES('46870','REVIEW_DUP',il,'{}',repeat('c',64)),('46870','REVIEW_DUP',il,'{}',repeat('c',64));
      RAISE EXCEPTION 'REGRESSION: F4 still reproducible (duplicate NULL-record_no rows accepted)';
    EXCEPTION WHEN OTHERS THEN
      IF SQLERRM LIKE 'REGRESSION:%' THEN RAISE; END IF;
      RAISE NOTICE 'F4 fixed: %', SQLERRM;
    END;

    RAISE NOTICE 'All four 2026-09-16 review findings (F1-F4) are blocked by the current schema.';
END
$review$;
ROLLBACK;
