-- 2026-09-16 ???: ?? ??/???/?? FK/??? ???. ?? SQL? ??.
-- 108 tables, 6 views. ?? ??? 102/5 ??? ?? ? ??.
-- KRAS/KOREPS 초기 구축 DDL (2026-09-16)
-- 대상: PostgreSQL 16 + 기존 설치된 PostGIS. 실행 방법은 kras-schema-create.md 참조.
-- 최초 1회 실행용. 기존 객체가 있으면 실패하며 전체 트랜잭션을 롤백한다.
-- 실제 기관/계정/비밀번호/스케줄은 포함하지 않는다.
BEGIN;
CREATE SCHEMA IF NOT EXISTS kras;
DO $setup$
DECLARE extension_schema text;
BEGIN
    SELECT n.nspname INTO extension_schema
    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
    WHERE e.extname = 'postgis';
    IF extension_schema IS NULL THEN
        RAISE EXCEPTION 'PostGIS must be installed before this script';
    END IF;
    PERFORM set_config('search_path', 'pg_catalog,kras,' || quote_ident(extension_schema), true);
END
$setup$;

CREATE DOMAIN kras.org_code AS varchar(5) CHECK (VALUE ~ '^[0-9]{5}$');
CREATE DOMAIN kras.pnu_code AS varchar(19) CHECK (VALUE ~ '^[0-9]{19}$');
CREATE DOMAIN kras.sha256 AS varchar(64) CHECK (VALUE ~ '^[0-9a-f]{64}$');

CREATE TABLE kras.sync_dataset (
    dataset_code varchar(80) PRIMARY KEY,
    source_system varchar(20) NOT NULL,
    service_code varchar(30),
    document_section text,
    collection_mode varchar(10) NOT NULL CHECK (collection_mode IN ('FULL','CHANGE','DETAIL','FILE')),
    contract_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED' CHECK (contract_status IN ('UNVERIFIED','VERIFIED')),
    parser_version text NOT NULL DEFAULT '1',
    completeness_policy jsonb NOT NULL DEFAULT '{}',
    delete_policy varchar(30) NOT NULL DEFAULT 'NO_DELETE',
    max_query_days integer CHECK (max_query_days > 0),
    enabled boolean NOT NULL DEFAULT false,
    CHECK (NOT enabled OR contract_status = 'VERIFIED')
);

COMMENT ON TABLE kras.sync_dataset IS '수집 데이터셋. 실제 계약 확인 전 비활성.';

CREATE TABLE kras.sync_policy (
    policy_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_cd kras.org_code NOT NULL,
    dataset_code varchar(80) NOT NULL REFERENCES kras.sync_dataset(dataset_code),
    job_kind varchar(10) NOT NULL CHECK (job_kind IN ('DAILY','MONTHLY','MANUAL')),
    cron varchar(100),
    zone_id text NOT NULL DEFAULT 'Asia/Seoul',
    overlap_days integer NOT NULL DEFAULT 3 CHECK (overlap_days >= 0),
    enabled boolean NOT NULL DEFAULT false,
    UNIQUE(org_cd,dataset_code,job_kind),
    UNIQUE(policy_id,org_cd),
    CHECK (job_kind = 'MANUAL' OR cron IS NOT NULL)
);

COMMENT ON TABLE kras.sync_policy IS '기관·데이터셋별 일별/월별 실행정책. 실행기는 별도 구현.';

CREATE TABLE kras.sync_run (
    run_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_id bigint,
    org_cd kras.org_code NOT NULL,
    job_kind varchar(10) NOT NULL CHECK (job_kind IN ('DAILY','MONTHLY','MANUAL')),
    triggered_by text NOT NULL DEFAULT 'MANUAL',
    period_start date NOT NULL,
    period_end_exclusive date NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','COLLECTING','VALIDATING','READY','APPLYING','SUCCESS','FAILED','BLOCKED','INTERRUPTED')),
    error_summary text,
    legacy_execution_log_id bigint,
    UNIQUE(run_id,org_cd),
    FOREIGN KEY(policy_id,org_cd) REFERENCES kras.sync_policy(policy_id,org_cd),
    CHECK(period_start < period_end_exclusive),
    CHECK(ended_at IS NULL OR ended_at >= started_at)
);

COMMENT ON TABLE kras.sync_run IS '배치 실행. 기존 실행로그 ID는 외부 스키마에 대한 논리 참조.';

CREATE TABLE kras.sync_item (
    item_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id bigint NOT NULL,
    org_cd kras.org_code NOT NULL,
    dataset_code varchar(80) NOT NULL REFERENCES kras.sync_dataset(dataset_code),
    scope_key varchar(256) NOT NULL CHECK (scope_key <> ''),
    request_key kras.sha256,
    request_params jsonb NOT NULL DEFAULT '{}',
    window_start date NOT NULL,
    window_end_exclusive date NOT NULL,
    attempt_no integer NOT NULL DEFAULT 1 CHECK(attempt_no > 0),
    requested_at timestamptz,
    source_as_of timestamptz,
    heartbeat_at timestamptz,
    status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','COLLECTING','VALIDATING','READY','APPLYING','SUCCESS','FAILED','BLOCKED','INTERRUPTED')),
    rows_received bigint NOT NULL DEFAULT 0 CHECK(rows_received >= 0),
    rows_valid bigint NOT NULL DEFAULT 0 CHECK(rows_valid >= 0),
    rows_rejected bigint NOT NULL DEFAULT 0 CHECK(rows_rejected >= 0),
    rows_applied bigint NOT NULL DEFAULT 0 CHECK(rows_applied >= 0),
    is_complete boolean NOT NULL DEFAULT false,
    error_code text,
    error_message text,
    FOREIGN KEY(run_id,org_cd) REFERENCES kras.sync_run(run_id,org_cd),
    UNIQUE(run_id,dataset_code,scope_key,window_start,window_end_exclusive,attempt_no),
    UNIQUE(item_id,org_cd),
    UNIQUE(item_id,org_cd,dataset_code,scope_key),
    CHECK(window_start < window_end_exclusive),
    CHECK(status <> 'SUCCESS' OR (is_complete AND rows_rejected = 0))
);

COMMENT ON TABLE kras.sync_item IS '수집/검증/반영 단위. org_cd는 기관 일치 복합 FK를 위해 반복 저장.';

CREATE TABLE kras.sync_file (
    file_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    file_type text NOT NULL,
    storage_uri text NOT NULL,
    sha256 kras.sha256 NOT NULL,
    byte_size bigint NOT NULL CHECK(byte_size >= 0),
    charset text,
    delimiter_code integer CHECK(delimiter_code BETWEEN 0 AND 127),
    source_epsg integer,
    target_epsg integer,
    layer_code text,
    received_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE kras.sync_file IS '실행별 불변 원본 파일. ASCII 11 구분자는 delimiter_code=11.';

CREATE TABLE kras.sync_record (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    record_no bigint NOT NULL CHECK(record_no > 0),
    parent_record_no bigint,
    payload jsonb NOT NULL,
    source_key jsonb,
    source_location text,
    payload_hash kras.sha256 NOT NULL,
    parser_version text NOT NULL,
    validation_status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK(validation_status IN ('PENDING','VALID','REJECTED')),
    PRIMARY KEY(item_id,record_no)
);

COMMENT ON TABLE kras.sync_record IS '원본 반복 행. 미확정 소유권 변동 응답도 이 테이블에 보존.';

CREATE TABLE kras.sync_reject (
    reject_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    record_no bigint,
    field_name text,
    error_code text NOT NULL,
    source_location text,
    status varchar(10) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED','IGNORED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE kras.sync_reject IS '파싱 오류. 개인정보 원문은 복제하지 않고 원본 위치로 추적.';

CREATE TABLE kras.sync_work (
    work_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    target_dataset varchar(80) NOT NULL REFERENCES kras.sync_dataset(dataset_code),
    entity_key varchar(512) NOT NULL CHECK(entity_key <> ''),
    request_params jsonb NOT NULL DEFAULT '{}',
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','RETRY','SUCCESS','FAILED','BLOCKED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
    next_retry_at timestamptz NOT NULL DEFAULT now(),
    lease_expires_at timestamptz,
    worker_id text,
    last_error text,
    UNIQUE(item_id,target_dataset,entity_key)
);

COMMENT ON TABLE kras.sync_work IS '필지/건물 재조회 큐. 같은 수집 항목 내 대상은 멱등 등록.';

CREATE TABLE kras.sync_checkpoint (
    org_cd kras.org_code NOT NULL,
    dataset_code varchar(80) NOT NULL REFERENCES kras.sync_dataset(dataset_code),
    scope_key varchar(256) NOT NULL CHECK(scope_key <> ''),
    collected_through_exclusive date,
    applied_through_exclusive date,
    last_item_id bigint,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(org_cd,dataset_code,scope_key),
    FOREIGN KEY(last_item_id,org_cd,dataset_code,scope_key) REFERENCES kras.sync_item(item_id,org_cd,dataset_code,scope_key),
    CHECK(applied_through_exclusive IS NULL OR (collected_through_exclusive IS NOT NULL AND applied_through_exclusive <= collected_through_exclusive))
);

COMMENT ON TABLE kras.sync_checkpoint IS '연속 성공 구간의 수집/반영 체크포인트. 연속성은 실행기에서 확인.';

CREATE TABLE kras.sync_publication (
    org_cd kras.org_code NOT NULL,
    dataset_code varchar(80) NOT NULL,
    scope_key varchar(256) NOT NULL,
    item_id bigint NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now(),
    source_as_of timestamptz,
    publication_no bigint NOT NULL CHECK(publication_no > 0),
    PRIMARY KEY(org_cd,dataset_code,scope_key),
    FOREIGN KEY(item_id,org_cd,dataset_code,scope_key) REFERENCES kras.sync_item(item_id,org_cd,dataset_code,scope_key)
);

COMMENT ON TABLE kras.sync_publication IS '기관·데이터셋·범위별 활성 게시 버전.';

CREATE INDEX ix_sync_run_1 ON kras.sync_run  (org_cd,started_at DESC);

CREATE INDEX ix_sync_item_1 ON kras.sync_item  (run_id,status);

CREATE INDEX ix_sync_item_2 ON kras.sync_item  (dataset_code,scope_key,window_start);

CREATE INDEX ix_sync_file_1 ON kras.sync_file  (sha256);

CREATE INDEX ix_sync_work_pending ON kras.sync_work(next_retry_at,work_id) WHERE status IN ('PENDING','RETRY');

CREATE TABLE kras.parcel (
    pnu kras.pnu_code PRIMARY KEY,
    org_cd kras.org_code NOT NULL,
    adm_sect_cd kras.org_code NOT NULL,
    land_loc_cd varchar(5) NOT NULL CHECK(land_loc_cd ~ '^[0-9]{5}$'),
    ledg_gbn varchar(1) NOT NULL CHECK(ledg_gbn ~ '^[0-9]$'),
    bobn varchar(4) NOT NULL CHECK(bobn ~ '^[0-9]{4}$'),
    bubn varchar(4) NOT NULL CHECK(bubn ~ '^[0-9]{4}$'),
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(adm_sect_cd,land_loc_cd,ledg_gbn,bobn,bubn),
    CHECK(pnu = adm_sect_cd || land_loc_cd || ledg_gbn || bobn || bubn),
    CHECK(org_cd = adm_sect_cd),
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED')),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.parcel IS '필지 마스터. 최초 수집 item을 먼저 생성하고 등록.';

CREATE INDEX ix_parcel_1 ON kras.parcel  (source_item_id);

CREATE INDEX ix_parcel_2 ON kras.parcel  (last_seen_item_id);

CREATE INDEX ix_parcel_3 ON kras.parcel  (org_cd,record_status);

CREATE TABLE kras.land_basic (
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu) PRIMARY KEY,
    jimok varchar(2),
    parea numeric(13,2),
    own_gbn varchar(2),
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_basic IS '토지기본 TXT 8항목 중 속성. 필지 식별항목은 parcel 참조.';

CREATE INDEX ix_land_basic_1 ON kras.land_basic  (source_item_id);

CREATE INDEX ix_land_basic_2 ON kras.land_basic  (last_seen_item_id);

CREATE TABLE kras.land_register (
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu) PRIMARY KEY,
    jimok varchar(2),
    jimok_nm varchar(150),
    parea numeric(13,2),
    grd varchar(3),
    grd_ymd date,
    land_mov_rsn_cd varchar(2),
    land_mov_rsn_cd_nm varchar(150),
    land_mov_ymd date,
    ledg_cntrst_cnf_gbn varchar(1),
    biz_act_ntc_gbn varchar(1),
    map_gbn varchar(6),
    land_last_hist_odrno varchar(2),
    own_rgt_last_hist_odrno varchar(4),
    scale varchar(2),
    scale_nm varchar(150),
    doho varchar(3),
    jiga_base_mon varchar(7),
    pann_jiga numeric(12,0),
    last_jibn varchar(8),
    last_bu varchar(4),
    lastbobn varchar(4),
    lastbubn varchar(4),
    land_mov_chrg_man_id varchar(20),
    own_rgt_chg_chrg_man_id varchar(20),
    bldg_gbn_no varchar(28),
    land_move_rell_jibn varchar(4000),
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_register IS 'HWP 1절 토지표시·기타정보. 기본 TXT와 독립 갱신.';

CREATE INDEX ix_land_register_1 ON kras.land_register  (source_item_id);

CREATE INDEX ix_land_register_2 ON kras.land_register  (last_seen_item_id);

CREATE TABLE kras.land_owner (
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu) PRIMARY KEY,
    owner_nm varchar(150),
    dregno varchar(13),
    own_gbn varchar(2),
    own_gbn_nm varchar(150),
    shr_cnt integer,
    owner_addr varchar(450),
    own_rgt_chg_rsn_cd varchar(2),
    own_rgt_chg_rsn_cd_nm varchar(150),
    owndymd date,
    availability varchar(20) NOT NULL DEFAULT 'UNKNOWN' CHECK(availability IN ('PROVIDED','MASKED','NOT_REQUESTED','NOT_AUTHORIZED','UNKNOWN')),
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_owner IS 'HWP 1절 소유내역. 미제공과 삭제 구별.';

CREATE INDEX ix_land_owner_1 ON kras.land_owner  (source_item_id);

CREATE INDEX ix_land_owner_2 ON kras.land_owner  (last_seen_item_id);

CREATE TABLE kras.land_share (
    share_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    shr_seqno text,
    own_rgt_chg_rsn_cd text,
    own_rgt_chg_rsn_nm text,
    owner_regno text,
    owner_nm text,
    owner_addr text,
    own_rgt_jibun text,
    own_gbn text,
    own_gbn_nm text,
    own_rgt_chg_ymd date,
    own_rgt_chg_del_ymd date,
    parea numeric,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_share IS '공유인. 공유순번의 유일성은 운영 계약 확인 후 추가.';

CREATE INDEX ix_land_share_1 ON kras.land_share  (pnu);

CREATE INDEX ix_land_share_2 ON kras.land_share  (source_item_id);

CREATE INDEX ix_land_share_3 ON kras.land_share  (last_seen_item_id);

CREATE TABLE kras.land_presence (
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu) PRIMARY KEY,
    real_gbn text,
    sect_loc_cd text,
    adm_sect_nm text,
    sect_loc_nm text,
    map_gbn text,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_presence IS '토지·건물 존재 조회 결과.';

CREATE INDEX ix_land_presence_1 ON kras.land_presence  (source_item_id);

CREATE INDEX ix_land_presence_2 ON kras.land_presence  (last_seen_item_id);

CREATE TABLE kras.land_price (
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    base_month date NOT NULL CHECK(EXTRACT(DAY FROM base_month)=1),
    pann_jiga numeric(12,0),
    PRIMARY KEY(pnu,base_month),
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_price IS '대장에 포함된 공시지가. KOREPS 목록 및 전체 TXT와 출처 분리.';

CREATE INDEX ix_land_price_1 ON kras.land_price  (pnu);

CREATE INDEX ix_land_price_2 ON kras.land_price  (source_item_id);

CREATE INDEX ix_land_price_3 ON kras.land_price  (last_seen_item_id);

CREATE TABLE kras.collective_building (
    collective_building_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    cbldg_seqno varchar(4),
    cbldg_nm varchar(150),
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.collective_building IS 'HWP 대지권 집합건물. 건축물대장 건물과 구별.';

CREATE INDEX ix_collective_building_1 ON kras.collective_building  (pnu);

CREATE INDEX ix_collective_building_2 ON kras.collective_building  (source_item_id);

CREATE INDEX ix_collective_building_3 ON kras.collective_building  (last_seen_item_id);

CREATE TABLE kras.collective_unit (
    unit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    collective_building_id bigint NOT NULL REFERENCES kras.collective_building(collective_building_id),
    dong varchar(150),
    flr varchar(150),
    ho varchar(150),
    sil varchar(150),
    cbldg_nm varchar(150),
    shr_cnt bigint,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.collective_unit IS '대지권 전유부. 동층호실은 확인 전 UNIQUE 강제하지 않음.';

CREATE INDEX ix_collective_unit_1 ON kras.collective_unit  (collective_building_id);

CREATE INDEX ix_collective_unit_2 ON kras.collective_unit  (source_item_id);

CREATE INDEX ix_collective_unit_3 ON kras.collective_unit  (last_seen_item_id);

CREATE TABLE kras.land_right (
    land_right_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    unit_id bigint NOT NULL REFERENCES kras.collective_unit(unit_id),
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    land_rgt_jibun_rate varchar(150),
    shr_cnt bigint,
    reljibn varchar(12),
    closure_gbn varchar(1) NOT NULL CHECK(closure_gbn IN ('0','1','9')),
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_right IS '대지권 반복행. 전유부+폐쇄조회범위로 전체 교체.';

CREATE INDEX ix_land_right_1 ON kras.land_right  (unit_id);

CREATE INDEX ix_land_right_2 ON kras.land_right  (pnu);

CREATE INDEX ix_land_right_3 ON kras.land_right  (source_item_id);

CREATE INDEX ix_land_right_4 ON kras.land_right  (last_seen_item_id);

CREATE TABLE kras.unit_ownership_history (
    history_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    unit_id bigint NOT NULL REFERENCES kras.collective_unit(unit_id),
    source_service text NOT NULL,
    own_rgt_hist_odrno varchar(4),
    own_rgt_chg_rsn_cd text,
    own_rgt_chg_rsn_nm text,
    own_rgt_jibun text,
    owner_regno text,
    owner_nm text,
    owner_addr text,
    own_gbn text,
    own_gbn_nm text,
    chrg_man_id text,
    closure_gbn text,
    own_rgt_chg_ymd date,
    del_ymd date,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}'
);

COMMENT ON TABLE kras.unit_ownership_history IS '대지권 소유연혁. 서비스별 별도 집합.';

CREATE INDEX ix_unit_ownership_history_1 ON kras.unit_ownership_history  (unit_id);

CREATE INDEX ix_unit_ownership_history_2 ON kras.unit_ownership_history  (source_item_id);

CREATE TABLE kras.land_movement_history (
    history_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    land_mov_hist_odrno text,
    land_hist_odrno text,
    jimok text,
    jimok_nm text,
    land_mov_rsn_cd text,
    land_mov_rsn_cd_nm text,
    scale text,
    scale_nm text,
    own_gbn text,
    doho text,
    land_mov_chrg_man_id text,
    owner_addr text,
    owner_nm text,
    dymd date,
    del_ymd date,
    land_mov_del_ymd date,
    parea numeric,
    shr_cnt bigint,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}'
);

COMMENT ON TABLE kras.land_movement_history IS '토지이동연혁. 순번은 문자열.';

CREATE INDEX ix_land_movement_history_1 ON kras.land_movement_history  (pnu);

CREATE INDEX ix_land_movement_history_2 ON kras.land_movement_history  (source_item_id);

CREATE TABLE kras.land_movement_relation (
    history_id bigint NOT NULL REFERENCES kras.land_movement_history(history_id),
    relation_no bigint NOT NULL CHECK(relation_no > 0),
    jibun text,
    related_pnu kras.pnu_code REFERENCES kras.parcel(pnu),
    PRIMARY KEY(history_id,relation_no),
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}'
);

COMMENT ON TABLE kras.land_movement_relation IS '토지이동 관련지번 반복.';

CREATE INDEX ix_land_movement_relation_1 ON kras.land_movement_relation  (history_id);

CREATE INDEX ix_land_movement_relation_2 ON kras.land_movement_relation  (related_pnu);

CREATE INDEX ix_land_movement_relation_3 ON kras.land_movement_relation  (source_item_id);

CREATE TABLE kras.land_ownership_history (
    history_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    own_rgt_chg_hist_odrno text,
    dodrno text,
    own_rgt_chg_rsn_cd text,
    own_rgt_chg_rsn_cd_nm text,
    dregno text,
    owner_nm text,
    own_gbn text,
    own_gbn_nm text,
    own_rgt_chg_chrg_man_id text,
    dymd date,
    shr_cnt bigint,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}'
);

COMMENT ON TABLE kras.land_ownership_history IS '토지 소유권연혁.';

CREATE INDEX ix_land_ownership_history_1 ON kras.land_ownership_history  (pnu);

CREATE INDEX ix_land_ownership_history_2 ON kras.land_ownership_history  (source_item_id);

CREATE TABLE kras.land_change_event (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_cd kras.org_code NOT NULL,
    land_mov_no text,
    land_mov_nm text,
    land_mov_item text,
    bf_land_loc_cd text,
    bf_ledg_gbn text,
    bf_bobn text,
    bf_bubn text,
    bf_jimok text,
    af_land_loc_cd text,
    af_ledg_gbn text,
    af_bobn text,
    af_bubn text,
    af_jimok text,
    land_mov_rsn_cd text,
    land_mov_rsn_nm text,
    bf_parea numeric,
    af_parea numeric,
    before_pnu kras.pnu_code,
    after_pnu kras.pnu_code,
    adj_ymd date,
    hndl_ymd date,
    payload_hash kras.sha256,
    payload jsonb NOT NULL,
    UNIQUE(source_item_id,record_no),
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}'
);

COMMENT ON TABLE kras.land_change_event IS '토지변동 원본. 전후 PNU는 아직 마스터 미수집일 수 있어 FK 없음.';

CREATE INDEX ix_land_change_event_1 ON kras.land_change_event  (source_item_id);

CREATE INDEX ix_land_change_event_2 ON kras.land_change_event  (org_cd,hndl_ymd);

CREATE INDEX ix_land_change_event_3 ON kras.land_change_event  (land_mov_no);

CREATE INDEX ix_land_change_event_4 ON kras.land_change_event  (before_pnu);

CREATE INDEX ix_land_change_event_5 ON kras.land_change_event  (after_pnu);

CREATE INDEX ix_land_movement_history_3 ON kras.land_movement_history  (pnu,dymd);

CREATE INDEX ix_land_ownership_history_3 ON kras.land_ownership_history  (pnu,dymd);

CREATE INDEX ix_unit_ownership_history_3 ON kras.unit_ownership_history  (unit_id,own_rgt_chg_ymd);

CREATE TABLE kras.integrated_building (
    building_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_cd kras.org_code NOT NULL,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    land_loc_nm text,
    jibn text,
    ufid text,
    bldg_nm text,
    dong text,
    bldg_gbn_no text,
    pnu_org text,
    vio_bldg_yn text,
    stru_cd text,
    stru_nm text,
    main_use_cd text,
    main_use_nm text,
    main_sub_gbn text,
    main_sub_gbn_nm text,
    bndr_info_src_cd text,
    bndr_info_src_nm text,
    attr_info_src_cd text,
    attr_info_src_nm text,
    km_name text,
    km_name_src text,
    km_name_src_nm text,
    permi_num text,
    use_apr_num text,
    etc_cd text,
    etc_cd_nm text,
    ch_jibun text,
    ch_jibun_nm text,
    mat_cd text,
    s_mat text,
    s_mat_nm text,
    bu_mat_gb_cd text,
    bu_mat_gb_nm text,
    larea numeric,
    barea numeric,
    garea numeric,
    blr numeric,
    fsi numeric,
    hgt numeric,
    uflr bigint,
    bflr bigint,
    bld_cnt bigint,
    ais_cnt bigint,
    sub_info_cnt bigint,
    use_aprv_ymd date,
    regist_day date,
    nem_date date,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED')),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.integrated_building IS 'HWP 13절 건물통합정보. UFID 후보키는 일반 인덱스만 생성.';

CREATE INDEX ix_integrated_building_1 ON kras.integrated_building  (pnu);

CREATE INDEX ix_integrated_building_2 ON kras.integrated_building  (source_item_id);

CREATE INDEX ix_integrated_building_3 ON kras.integrated_building  (last_seen_item_id);

CREATE INDEX ix_integrated_building_4 ON kras.integrated_building  (org_cd,ufid);

CREATE TABLE kras.building_parcel (
    relation_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    building_id bigint NOT NULL REFERENCES kras.integrated_building(building_id),
    pnu kras.pnu_code REFERENCES kras.parcel(pnu),
    relation_type varchar(10) NOT NULL CHECK(relation_type IN ('MAIN','RELATED')),
    rel_jibun text,
    relation_no bigint,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_parcel IS '통합건물 관련 필지. 미확인 지번은 문자열 보존.';

CREATE INDEX ix_building_parcel_1 ON kras.building_parcel  (building_id);

CREATE INDEX ix_building_parcel_2 ON kras.building_parcel  (pnu);

CREATE INDEX ix_building_parcel_3 ON kras.building_parcel  (source_item_id);

CREATE INDEX ix_building_parcel_4 ON kras.building_parcel  (last_seen_item_id);

CREATE TABLE kras.building_image (
    image_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    file_id bigint NOT NULL REFERENCES kras.sync_file(file_id),
    width integer,
    height integer,
    scale text,
    request_key kras.sha256 NOT NULL,
    mime_type text,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_image IS 'HWP 건물통합도면 파일 참조.';

CREATE INDEX ix_building_image_1 ON kras.building_image  (pnu);

CREATE INDEX ix_building_image_2 ON kras.building_image  (file_id);

CREATE INDEX ix_building_image_3 ON kras.building_image  (source_item_id);

CREATE INDEX ix_building_image_4 ON kras.building_image  (last_seen_item_id);

CREATE TABLE kras.building_register (
    register_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_cd kras.org_code NOT NULL,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    bldg_gbn_no text,
    bldg_kind_cd text,
    bldg_kind_nm text,
    bldg_nm text,
    dong_nm text,
    bmap_yn text,
    garea numeric,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED')),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.building_register IS 'KRAS000102 건축물 동 목록.';

CREATE INDEX ix_building_register_1 ON kras.building_register  (pnu);

CREATE INDEX ix_building_register_2 ON kras.building_register  (source_item_id);

CREATE INDEX ix_building_register_3 ON kras.building_register  (last_seen_item_id);

CREATE INDEX ix_building_register_4 ON kras.building_register  (org_cd,pnu,bldg_gbn_no);

CREATE TABLE kras.building_summary (
    summary_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    register_id bigint REFERENCES kras.building_register(register_id),
    bldg_gbn_no text,
    bldg_nm text,
    main_use_nm text,
    lega_yn text,
    vio_bldg_yn text,
    larea numeric,
    barea numeric,
    garea numeric,
    blr numeric,
    fsi numeric,
    fsi_calc_garea numeric,
    land_cnt bigint,
    tot_main_bldg_cnt bigint,
    sub_bldg_cnt bigint,
    tot_fmly_cnt bigint,
    tot_hehd_cnt bigint,
    tot_ho_cnt bigint,
    tot_park_cnt bigint,
    perm_ymd date,
    bgcons_ymd date,
    use_aprv_ymd date,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_summary IS 'KRAS000017 총괄표제부.';

CREATE INDEX ix_building_summary_1 ON kras.building_summary  (pnu);

CREATE INDEX ix_building_summary_2 ON kras.building_summary  (register_id);

CREATE INDEX ix_building_summary_3 ON kras.building_summary  (source_item_id);

CREATE INDEX ix_building_summary_4 ON kras.building_summary  (last_seen_item_id);

CREATE TABLE kras.building_title (
    title_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    register_id bigint NOT NULL REFERENCES kras.building_register(register_id),
    source_service text NOT NULL,
    bldg_gbn_no text,
    bldg_kind_cd text,
    bldg_nm text,
    dong text,
    main_sub_gbn_nm text,
    main_sub_seqno text,
    main_use_nm text,
    stru_nm text,
    roof_nm text,
    lega_yn text,
    vio_bldg_yn text,
    larea numeric,
    barea numeric,
    garea numeric,
    land_cnt bigint,
    fmly_cnt bigint,
    hehd_cnt bigint,
    ho_cnt bigint,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_title IS 'KRAS000014/015 일반·집합 표제부.';

CREATE INDEX ix_building_title_1 ON kras.building_title  (register_id);

CREATE INDEX ix_building_title_2 ON kras.building_title  (source_item_id);

CREATE INDEX ix_building_title_3 ON kras.building_title  (last_seen_item_id);

CREATE TABLE kras.building_floor (
    floor_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title_id bigint NOT NULL REFERENCES kras.building_title(title_id),
    flr_gbn_cd text,
    flr text,
    main_use_nm text,
    stru_nm text,
    btm_area numeric,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_floor IS '표제부 층별 현황.';

CREATE INDEX ix_building_floor_1 ON kras.building_floor  (title_id);

CREATE INDEX ix_building_floor_2 ON kras.building_floor  (source_item_id);

CREATE INDEX ix_building_floor_3 ON kras.building_floor  (last_seen_item_id);

CREATE TABLE kras.building_title_owner (
    owner_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title_id bigint NOT NULL REFERENCES kras.building_title(title_id),
    owner_nm text,
    dregno text,
    own_gbn_nm text,
    jibun_desc text,
    detl_addr text,
    last_yn text,
    adj_ymd date,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_title_owner IS '표제부 소유자 반복.';

CREATE INDEX ix_building_title_owner_1 ON kras.building_title_owner  (title_id);

CREATE INDEX ix_building_title_owner_2 ON kras.building_title_owner  (source_item_id);

CREATE INDEX ix_building_title_owner_3 ON kras.building_title_owner  (last_seen_item_id);

CREATE TABLE kras.building_title_change (
    change_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title_id bigint NOT NULL REFERENCES kras.building_title(title_id),
    chg_rsn_nm text,
    chg_cntn text,
    chg_ymd date,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}'
);

COMMENT ON TABLE kras.building_title_change IS '표제부 변경내역.';

CREATE INDEX ix_building_title_change_1 ON kras.building_title_change  (title_id);

CREATE INDEX ix_building_title_change_2 ON kras.building_title_change  (source_item_id);

CREATE TABLE kras.building_unit (
    building_unit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    register_id bigint REFERENCES kras.building_register(register_id),
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    bldg_gbn_no text,
    parent_bno text,
    bldg_kind_cd text,
    dong_nm text,
    flr_ho_nm text,
    bmap_yn text,
    garea numeric,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_unit IS 'KRAS000103 건축물 호 목록.';

CREATE INDEX ix_building_unit_1 ON kras.building_unit  (register_id);

CREATE INDEX ix_building_unit_2 ON kras.building_unit  (pnu);

CREATE INDEX ix_building_unit_3 ON kras.building_unit  (source_item_id);

CREATE INDEX ix_building_unit_4 ON kras.building_unit  (last_seen_item_id);

CREATE TABLE kras.building_exclusive (
    exclusive_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    building_unit_id bigint REFERENCES kras.building_unit(building_unit_id),
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    request_key kras.sha256 NOT NULL,
    bldg_gbn_no text,
    upper_bldg_no text,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_exclusive IS 'KRAS000016 전유부.';

CREATE INDEX ix_building_exclusive_1 ON kras.building_exclusive  (building_unit_id);

CREATE INDEX ix_building_exclusive_2 ON kras.building_exclusive  (pnu);

CREATE INDEX ix_building_exclusive_3 ON kras.building_exclusive  (source_item_id);

CREATE INDEX ix_building_exclusive_4 ON kras.building_exclusive  (last_seen_item_id);

CREATE TABLE kras.building_exclusive_area (
    area_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exclusive_id bigint NOT NULL REFERENCES kras.building_exclusive(exclusive_id),
    expos_comm_gbn_nm text,
    flr text,
    flr_no text,
    main_sub_gbn_nm text,
    main_use_nm text,
    stru_nm text,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_exclusive_area IS '전유/공용 면적 등 상세. 규격 미확정 속성은 extra_attributes 보존.';

CREATE INDEX ix_building_exclusive_area_1 ON kras.building_exclusive_area  (exclusive_id);

CREATE INDEX ix_building_exclusive_area_2 ON kras.building_exclusive_area  (source_item_id);

CREATE INDEX ix_building_exclusive_area_3 ON kras.building_exclusive_area  (last_seen_item_id);

CREATE TABLE kras.building_exclusive_owner (
    owner_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exclusive_id bigint NOT NULL REFERENCES kras.building_exclusive(exclusive_id),
    owner_nm text,
    dregno text,
    own_gbn_nm text,
    jibun_desc text,
    detl_addr text,
    chg_rsn_nm text,
    last_yn text,
    chg_ymd date,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_exclusive_owner IS '전유부 소유자.';

CREATE INDEX ix_building_exclusive_owner_1 ON kras.building_exclusive_owner  (exclusive_id);

CREATE INDEX ix_building_exclusive_owner_2 ON kras.building_exclusive_owner  (source_item_id);

CREATE INDEX ix_building_exclusive_owner_3 ON kras.building_exclusive_owner  (last_seen_item_id);

CREATE TABLE kras.building_exclusive_price (
    price_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exclusive_id bigint NOT NULL REFERENCES kras.building_exclusive(exclusive_id),
    base_ymd date,
    house_prc numeric,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.building_exclusive_price IS '전유부 가격.';

CREATE INDEX ix_building_exclusive_price_1 ON kras.building_exclusive_price  (exclusive_id);

CREATE INDEX ix_building_exclusive_price_2 ON kras.building_exclusive_price  (source_item_id);

CREATE INDEX ix_building_exclusive_price_3 ON kras.building_exclusive_price  (last_seen_item_id);

CREATE TABLE kras.koreps_land_price (
    price_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    base_year text,
    base_mon text,
    jibun text,
    jiga_jibn text,
    pann_jiga numeric,
    pann_ymd date,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.koreps_land_price IS 'KOREPS00011 지가 목록. 대장 공시지가와 출처 분리.';

CREATE INDEX ix_koreps_land_price_1 ON kras.koreps_land_price  (pnu);

CREATE INDEX ix_koreps_land_price_2 ON kras.koreps_land_price  (source_item_id);

CREATE INDEX ix_koreps_land_price_3 ON kras.koreps_land_price  (last_seen_item_id);

CREATE TABLE kras.house_price (
    price_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    base_year text,
    stdmt text,
    dong_no text,
    land_area numeric,
    land_calc_area numeric,
    bldg_area numeric,
    bldg_calc_area numeric,
    indi_house_prc numeric,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.house_price IS 'KOREPS00033 주택가격.';

CREATE INDEX ix_house_price_1 ON kras.house_price  (pnu);

CREATE INDEX ix_house_price_2 ON kras.house_price  (source_item_id);

CREATE INDEX ix_house_price_3 ON kras.house_price  (last_seen_item_id);

CREATE TABLE kras.final_land_price (
    price_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    base_year text,
    stdmt text,
    jiga numeric,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.final_land_price IS 'KOREPS00034 결정지가.';

CREATE INDEX ix_final_land_price_1 ON kras.final_land_price  (pnu);

CREATE INDEX ix_final_land_price_2 ON kras.final_land_price  (source_item_id);

CREATE INDEX ix_final_land_price_3 ON kras.final_land_price  (last_seen_item_id);

CREATE TABLE kras.read_land_price (
    price_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    cald_stdmt text,
    seqno text,
    jimok text,
    land_loc_addr text,
    decn_jiga numeric,
    read_jiga numeric,
    py_jiga numeric,
    parea numeric,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.read_land_price IS 'KOREPS00035 열람지가.';

CREATE INDEX ix_read_land_price_1 ON kras.read_land_price  (pnu);

CREATE INDEX ix_read_land_price_2 ON kras.read_land_price  (source_item_id);

CREATE INDEX ix_read_land_price_3 ON kras.read_land_price  (last_seen_item_id);

CREATE TABLE kras.land_attribute (
    attribute_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    land_seqno text,
    sgg_cd text,
    land_loc_cd text,
    ledg_gbn text,
    bobn text,
    bubn text,
    land_loc_nm text,
    jimok text,
    jimok_nm text,
    own_gbn text,
    land_use text,
    geo_form text,
    geo_hl text,
    road_side text,
    spfc1 text,
    pann_year text,
    stdmt text,
    land_mov_rsn_cd text,
    parea numeric,
    spfc1_area numeric,
    pnilp numeric,
    calc_jiga numeric,
    py_jiga numeric,
    land_mov_ymd date,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_attribute IS 'KOREPS00047 토지특성.';

CREATE INDEX ix_land_attribute_1 ON kras.land_attribute  (pnu);

CREATE INDEX ix_land_attribute_2 ON kras.land_attribute  (source_item_id);

CREATE INDEX ix_land_attribute_3 ON kras.land_attribute  (last_seen_item_id);

CREATE TABLE kras.land_use_attribute (
    attribute_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    ctype text,
    divno text,
    gubun text,
    lawnm text,
    seq text,
    ucode text,
    uname text,
    unm text,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_use_attribute IS 'KRAS000025 토지이용계획 속성.';

CREATE INDEX ix_land_use_attribute_1 ON kras.land_use_attribute  (pnu);

CREATE INDEX ix_land_use_attribute_2 ON kras.land_use_attribute  (source_item_id);

CREATE INDEX ix_land_use_attribute_3 ON kras.land_use_attribute  (last_seen_item_id);

CREATE TABLE kras.land_use_zone (
    zone_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    use_zone_zone_cd text,
    use_zone_zone_cd_nm text,
    cflt_yn text,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_use_zone IS 'KRAS000027 용도지역 적용정보. 공간 교차 결과와 구별.';

CREATE INDEX ix_land_use_zone_1 ON kras.land_use_zone  (pnu);

CREATE INDEX ix_land_use_zone_2 ON kras.land_use_zone  (source_item_id);

CREATE INDEX ix_land_use_zone_3 ON kras.land_use_zone  (last_seen_item_id);

CREATE TABLE kras.land_use_plan (
    plan_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    request_key kras.sha256 NOT NULL,
    land_loc_nm text,
    jibn text,
    jimok text,
    jimok_nm text,
    jiga_ym text,
    scale text,
    iss_no text,
    iss_scale text,
    adm_sect_head text,
    parea numeric,
    jiga numeric,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_use_plan IS 'KRAS000026 토지이용계획. 옵션별 응답.';

CREATE INDEX ix_land_use_plan_1 ON kras.land_use_plan  (pnu);

CREATE INDEX ix_land_use_plan_2 ON kras.land_use_plan  (source_item_id);

CREATE INDEX ix_land_use_plan_3 ON kras.land_use_plan  (last_seen_item_id);

CREATE TABLE kras.land_use_restriction (
    restriction_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id bigint NOT NULL REFERENCES kras.land_use_plan(plan_id),
    seqno text,
    ucode text,
    law_full_cd text,
    law_level text,
    law_contents text,
    uselaw_a text,
    uselaw_b text,
    use_restrict text,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_use_restriction IS '토지이용 제한·법령 반복.';

CREATE INDEX ix_land_use_restriction_1 ON kras.land_use_restriction  (plan_id);

CREATE INDEX ix_land_use_restriction_2 ON kras.land_use_restriction  (source_item_id);

CREATE INDEX ix_land_use_restriction_3 ON kras.land_use_restriction  (last_seen_item_id);

CREATE TABLE kras.land_use_plan_asset (
    asset_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id bigint NOT NULL REFERENCES kras.land_use_plan(plan_id),
    file_id bigint REFERENCES kras.sync_file(file_id),
    asset_kind text,
    mime_type text,
    text text,
    scale text,
    width integer,
    height integer,
    legend_width integer,
    legend_height integer,
    source_item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    observed_at timestamptz NOT NULL DEFAULT now(),
    row_hash kras.sha256,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    record_no bigint CHECK(record_no > 0),
    parent_record_no bigint,
    extra_attributes jsonb NOT NULL DEFAULT '{}',
    field_presence jsonb NOT NULL DEFAULT '{}',
    last_seen_item_id bigint REFERENCES kras.sync_item(item_id),
    record_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(record_status IN ('ACTIVE','SOURCE_CLOSED','NOT_OBSERVED'))
);

COMMENT ON TABLE kras.land_use_plan_asset IS '지도·범례 파일 또는 텍스트.';

CREATE INDEX ix_land_use_plan_asset_1 ON kras.land_use_plan_asset  (plan_id);

CREATE INDEX ix_land_use_plan_asset_2 ON kras.land_use_plan_asset  (file_id);

CREATE INDEX ix_land_use_plan_asset_3 ON kras.land_use_plan_asset  (source_item_id);

CREATE INDEX ix_land_use_plan_asset_4 ON kras.land_use_plan_asset  (last_seen_item_id);

CREATE TABLE kras.api_response (
    response_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_item_id bigint NOT NULL,
    org_cd kras.org_code NOT NULL,
    source_system varchar(20) NOT NULL,
    service_code varchar(30) NOT NULL,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    bno text NOT NULL DEFAULT '',
    normalized_params jsonb NOT NULL DEFAULT '{}',
    request_key kras.sha256 NOT NULL,
    contract_version text NOT NULL,
    response_xml text NOT NULL CHECK(xml_is_well_formed_document(response_xml)),
    source_result_code text NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now(),
    source_as_of timestamptz,
    data_state varchar(20) NOT NULL CHECK(data_state IN ('COLLECTED','CONFIRMED_EMPTY')),
    response_hash kras.sha256 NOT NULL,
    FOREIGN KEY(source_item_id,org_cd) REFERENCES kras.sync_item(item_id,org_cd),
    UNIQUE(source_item_id,request_key),
    UNIQUE(response_id,org_cd,pnu,request_key),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.api_response IS '게시 가능한 정상 XML 응답 버전. request_key는 정규화 요청의 SHA-256.';

CREATE TABLE kras.api_bundle (
    bundle_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_cd kras.org_code NOT NULL,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    bundle_kind varchar(80) NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','READY','PUBLISHED')),
    is_complete boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE(bundle_id,org_cd,pnu),
    UNIQUE(bundle_id,org_cd,pnu,bundle_kind),
    CHECK(status = 'DRAFT' OR is_complete),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.api_bundle IS '필지별 조합 API 응답 버전 집합.';

CREATE TABLE kras.api_bundle_member (
    bundle_id bigint NOT NULL,
    org_cd kras.org_code NOT NULL,
    pnu kras.pnu_code NOT NULL,
    request_key kras.sha256 NOT NULL,
    response_id bigint NOT NULL,
    PRIMARY KEY(bundle_id,request_key),
    FOREIGN KEY(bundle_id,org_cd,pnu) REFERENCES kras.api_bundle(bundle_id,org_cd,pnu),
    FOREIGN KEY(response_id,org_cd,pnu,request_key) REFERENCES kras.api_response(response_id,org_cd,pnu,request_key),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.api_bundle_member IS '같은 기관·PNU·요청키의 응답만 bundle에 포함.';

CREATE TABLE kras.api_publication (
    org_cd kras.org_code NOT NULL,
    pnu kras.pnu_code NOT NULL,
    bundle_kind varchar(80) NOT NULL,
    bundle_id bigint NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(org_cd,pnu,bundle_kind),
    FOREIGN KEY(bundle_id,org_cd,pnu,bundle_kind) REFERENCES kras.api_bundle(bundle_id,org_cd,pnu,bundle_kind),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.api_publication IS '필지별 활성 bundle 포인터.';

CREATE TABLE kras.api_coverage (
    org_cd kras.org_code NOT NULL,
    request_key kras.sha256 NOT NULL,
    source_system varchar(20) NOT NULL,
    service_code varchar(30) NOT NULL,
    pnu kras.pnu_code NOT NULL REFERENCES kras.parcel(pnu),
    bno text NOT NULL DEFAULT '',
    normalized_params jsonb NOT NULL DEFAULT '{}',
    status varchar(20) NOT NULL DEFAULT 'UNCOLLECTED' CHECK(status IN ('UNCOLLECTED','COLLECTED','CONFIRMED_EMPTY','FAILED')),
    last_success_at timestamptz,
    next_collect_at timestamptz,
    active_response_id bigint,
    PRIMARY KEY(org_cd,request_key),
    FOREIGN KEY(active_response_id,org_cd,pnu,request_key) REFERENCES kras.api_response(response_id,org_cd,pnu,request_key),
    CHECK(status NOT IN ('COLLECTED','CONFIRMED_EMPTY') OR (active_response_id IS NOT NULL AND last_success_at IS NOT NULL)),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.api_coverage IS '요청별 수집 커버리지. 조회 응답은 bundle publication을 기준으로 선택.';

CREATE INDEX ix_api_response_1 ON kras.api_response  (org_cd,service_code,pnu,bno,observed_at);

CREATE INDEX ix_api_coverage_1 ON kras.api_coverage  (org_cd,pnu,service_code,bno);

CREATE TABLE kras.spatial_layer (
    layer_code varchar(80) PRIMARY KEY,
    dataset_code varchar(80) NOT NULL REFERENCES kras.sync_dataset(dataset_code),
    layer_name text,
    source_epsg integer,
    target_epsg integer NOT NULL DEFAULT 5186 CHECK(target_epsg = 5186),
    geometry_type text,
    metadata jsonb NOT NULL DEFAULT '{}',
    source_item_id bigint REFERENCES kras.sync_item(item_id),
    observed_at timestamptz
);

COMMENT ON TABLE kras.spatial_layer IS '레이어 계약. 원천 CRS 미확인 상태는 수집기에서 게시 차단.';

CREATE TABLE kras.usezone_code (
    code_version text NOT NULL,
    theme_code text NOT NULL,
    theme_name text,
    source_item_id bigint REFERENCES kras.sync_item(item_id),
    attributes jsonb NOT NULL DEFAULT '{}',
    PRIMARY KEY(code_version,theme_code)
);

COMMENT ON TABLE kras.usezone_code IS '용도지역 코드명 버전. 실제 코드자료는 별도 수집.';

CREATE TABLE kras.spatial_feature (
    item_id bigint NOT NULL,
    feature_no bigint NOT NULL CHECK(feature_no > 0),
    org_cd kras.org_code NOT NULL,
    layer_code varchar(80) NOT NULL REFERENCES kras.spatial_layer(layer_code),
    source_feature_id text,
    pnu kras.pnu_code,
    properties jsonb NOT NULL DEFAULT '{}',
    geom geometry(Geometry,5186) NOT NULL,
    PRIMARY KEY(item_id,feature_no),
    UNIQUE(item_id,feature_no,org_cd,layer_code),
    FOREIGN KEY(item_id,org_cd) REFERENCES kras.sync_item(item_id,org_cd),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.spatial_feature IS 'SHAPE 원본 도형. 도형 PNU는 상세 미수집을 허용하므로 FK 없음.';

CREATE TABLE kras.cadastral_feature (
    feature_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id bigint NOT NULL,
    feature_no bigint NOT NULL,
    org_cd kras.org_code NOT NULL,
    layer_code varchar(80) NOT NULL,
    source_uid text,
    uid integer GENERATED ALWAYS AS IDENTITY,
    pnu kras.pnu_code,
    jibun varchar(100),
    bchk varchar(1),
    geom geometry(MultiPolygon,5186) NOT NULL,
    UNIQUE(uid),
    UNIQUE(item_id,feature_no),
    FOREIGN KEY(item_id,feature_no,org_cd,layer_code) REFERENCES kras.spatial_feature(item_id,feature_no,org_cd,layer_code),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.cadastral_feature IS '게시용 도형 투영. uid는 기존 int4 소비자용이며 버전 간 동일성 보장 없음.';

CREATE INDEX ix_cadastral_feature_1 ON kras.cadastral_feature  (org_cd,item_id);

CREATE INDEX ix_cadastral_feature_2 ON kras.cadastral_feature USING gist (geom);

CREATE INDEX ix_cadastral_feature_3 ON kras.cadastral_feature  (pnu);

CREATE TABLE kras.usezone_feature (
    feature_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id bigint NOT NULL,
    feature_no bigint NOT NULL,
    org_cd kras.org_code NOT NULL,
    layer_code varchar(80) NOT NULL,
    source_uid text,
    uid integer GENERATED ALWAYS AS IDENTITY,
    pnu kras.pnu_code,
    mnum varchar(33),
    remark varchar(100),
    alias varchar(100),
    theme_code varchar(6),
    theme_name varchar(100),
    code_version text,
    geom geometry(MultiPolygon,5186) NOT NULL,
    UNIQUE(uid),
    UNIQUE(item_id,feature_no),
    FOREIGN KEY(item_id,feature_no,org_cd,layer_code) REFERENCES kras.spatial_feature(item_id,feature_no,org_cd,layer_code),
    CHECK(org_cd = substring(pnu FROM 1 FOR 5))
);

COMMENT ON TABLE kras.usezone_feature IS '게시용 도형 투영. uid는 기존 int4 소비자용이며 버전 간 동일성 보장 없음.';

CREATE INDEX ix_usezone_feature_1 ON kras.usezone_feature  (org_cd,item_id);

CREATE INDEX ix_usezone_feature_2 ON kras.usezone_feature USING gist (geom);

CREATE INDEX ix_usezone_feature_3 ON kras.usezone_feature  (pnu);

CREATE INDEX ix_usezone_feature_4 ON kras.usezone_feature  (layer_code,theme_code);

CREATE INDEX ix_spatial_feature_1 ON kras.spatial_feature USING gist (geom);

CREATE INDEX ix_spatial_feature_2 ON kras.spatial_feature  (org_cd,layer_code,item_id);

CREATE TABLE kras.spatial_release (
    release_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_cd kras.org_code NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','READY','PUBLISHED')),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(release_id,org_cd)
);

COMMENT ON TABLE kras.spatial_release IS '기관 전체 용도지역 레이어 게시 집합.';

CREATE TABLE kras.spatial_release_member (
    release_id bigint NOT NULL,
    org_cd kras.org_code NOT NULL,
    layer_code varchar(80) NOT NULL REFERENCES kras.spatial_layer(layer_code),
    item_id bigint NOT NULL,
    PRIMARY KEY(release_id,layer_code),
    FOREIGN KEY(release_id,org_cd) REFERENCES kras.spatial_release(release_id,org_cd),
    FOREIGN KEY(item_id,org_cd) REFERENCES kras.sync_item(item_id,org_cd)
);

COMMENT ON TABLE kras.spatial_release_member IS '레이어별 수집 버전. layer scope 일치는 게시 전 검증.';

CREATE TABLE kras.spatial_publication (
    org_cd kras.org_code PRIMARY KEY,
    release_id bigint NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(release_id,org_cd) REFERENCES kras.spatial_release(release_id,org_cd)
);

COMMENT ON TABLE kras.spatial_publication IS '기관별 활성 용도지역 release.';

-- 검증 전 데이터는 원본 sync_record에 보존. 타입 변환 성공 행만 stage_*에 저장.

CREATE TABLE kras.stage_parcel (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.parcel,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_parcel IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_parcel ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN org_cd DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN adm_sect_cd DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN land_loc_cd DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN ledg_gbn DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN bobn DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN bubn DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN first_seen_at DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_parcel ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_basic (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_basic,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_basic IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_basic ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN jimok DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN parea DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN own_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_basic ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_register (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_register,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_register IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_register ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN jimok DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN jimok_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN parea DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN grd DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN grd_ymd DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN land_mov_rsn_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN land_mov_rsn_cd_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN land_mov_ymd DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN ledg_cntrst_cnf_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN biz_act_ntc_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN map_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN land_last_hist_odrno DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN own_rgt_last_hist_odrno DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN scale DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN scale_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN doho DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN jiga_base_mon DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN pann_jiga DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN last_jibn DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN last_bu DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN lastbobn DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN lastbubn DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN land_mov_chrg_man_id DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN own_rgt_chg_chrg_man_id DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN bldg_gbn_no DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN land_move_rell_jibn DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_register ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_owner (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_owner,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_owner IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_owner ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN owner_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN dregno DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN own_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN own_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN shr_cnt DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN owner_addr DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN own_rgt_chg_rsn_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN own_rgt_chg_rsn_cd_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN owndymd DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN availability DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_owner ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_share (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_share,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_share IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_share ALTER COLUMN share_id DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN shr_seqno DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN own_rgt_chg_rsn_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN own_rgt_chg_rsn_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN owner_regno DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN owner_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN owner_addr DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN own_rgt_jibun DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN own_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN own_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN own_rgt_chg_ymd DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN own_rgt_chg_del_ymd DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN parea DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_share ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_presence (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_presence,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_presence IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_presence ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN real_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN sect_loc_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN adm_sect_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN sect_loc_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN map_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_presence ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_price (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_price,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_price IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_price ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN base_month DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN pann_jiga DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_price ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_collective_building (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.collective_building,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_collective_building IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_collective_building ALTER COLUMN collective_building_id DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN cbldg_seqno DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN cbldg_nm DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_collective_building ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_collective_unit (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.collective_unit,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_collective_unit IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_collective_unit ALTER COLUMN unit_id DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN collective_building_id DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN dong DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN flr DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN ho DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN sil DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN cbldg_nm DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN shr_cnt DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_collective_unit ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_right (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_right,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_right IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_right ALTER COLUMN land_right_id DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN unit_id DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN land_rgt_jibun_rate DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN shr_cnt DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN reljibn DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN closure_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_right ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_unit_ownership_history (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.unit_ownership_history,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_unit_ownership_history IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN history_id DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN unit_id DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN source_service DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN own_rgt_hist_odrno DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN own_rgt_chg_rsn_cd DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN own_rgt_chg_rsn_nm DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN own_rgt_jibun DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN owner_regno DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN owner_nm DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN owner_addr DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN own_gbn DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN own_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN chrg_man_id DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN closure_gbn DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN own_rgt_chg_ymd DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN del_ymd DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_unit_ownership_history ALTER COLUMN field_presence DROP NOT NULL;

CREATE TABLE kras.stage_land_movement_history (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_movement_history,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_movement_history IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN history_id DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN land_mov_hist_odrno DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN land_hist_odrno DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN jimok DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN jimok_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN land_mov_rsn_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN land_mov_rsn_cd_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN scale DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN scale_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN own_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN doho DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN land_mov_chrg_man_id DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN owner_addr DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN owner_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN dymd DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN del_ymd DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN land_mov_del_ymd DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN parea DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN shr_cnt DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_history ALTER COLUMN field_presence DROP NOT NULL;

CREATE TABLE kras.stage_land_movement_relation (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_movement_relation,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_movement_relation IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN history_id DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN relation_no DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN jibun DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN related_pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_movement_relation ALTER COLUMN field_presence DROP NOT NULL;

CREATE TABLE kras.stage_land_ownership_history (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_ownership_history,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_ownership_history IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN history_id DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN own_rgt_chg_hist_odrno DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN dodrno DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN own_rgt_chg_rsn_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN own_rgt_chg_rsn_cd_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN dregno DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN owner_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN own_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN own_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN own_rgt_chg_chrg_man_id DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN dymd DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN shr_cnt DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_ownership_history ALTER COLUMN field_presence DROP NOT NULL;

CREATE TABLE kras.stage_land_change_event (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_change_event,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_change_event IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_change_event ALTER COLUMN event_id DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN org_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN land_mov_no DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN land_mov_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN land_mov_item DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN bf_land_loc_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN bf_ledg_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN bf_bobn DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN bf_bubn DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN bf_jimok DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN af_land_loc_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN af_ledg_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN af_bobn DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN af_bubn DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN af_jimok DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN land_mov_rsn_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN land_mov_rsn_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN bf_parea DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN af_parea DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN before_pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN after_pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN adj_ymd DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN hndl_ymd DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN payload_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN payload DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_change_event ALTER COLUMN field_presence DROP NOT NULL;

CREATE TABLE kras.stage_integrated_building (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.integrated_building,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_integrated_building IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_integrated_building ALTER COLUMN building_id DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN org_cd DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN land_loc_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN jibn DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN ufid DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN bldg_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN dong DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN bldg_gbn_no DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN pnu_org DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN vio_bldg_yn DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN stru_cd DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN stru_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN main_use_cd DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN main_use_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN main_sub_gbn DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN main_sub_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN bndr_info_src_cd DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN bndr_info_src_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN attr_info_src_cd DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN attr_info_src_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN km_name DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN km_name_src DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN km_name_src_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN permi_num DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN use_apr_num DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN etc_cd DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN etc_cd_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN ch_jibun DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN ch_jibun_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN mat_cd DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN s_mat DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN s_mat_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN bu_mat_gb_cd DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN bu_mat_gb_nm DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN larea DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN barea DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN garea DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN blr DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN fsi DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN hgt DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN uflr DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN bflr DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN bld_cnt DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN ais_cnt DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN sub_info_cnt DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN use_aprv_ymd DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN regist_day DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN nem_date DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_integrated_building ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_parcel (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_parcel,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_parcel IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_parcel ALTER COLUMN relation_id DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN building_id DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN relation_type DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN rel_jibun DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN relation_no DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_parcel ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_image (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_image,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_image IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_image ALTER COLUMN image_id DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN file_id DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN width DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN height DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN scale DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN request_key DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN mime_type DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_image ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_register (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_register,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_register IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_register ALTER COLUMN register_id DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN org_cd DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN bldg_gbn_no DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN bldg_kind_cd DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN bldg_kind_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN bldg_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN dong_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN bmap_yn DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN garea DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_register ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_summary (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_summary,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_summary IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_summary ALTER COLUMN summary_id DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN register_id DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN bldg_gbn_no DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN bldg_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN main_use_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN lega_yn DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN vio_bldg_yn DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN larea DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN barea DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN garea DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN blr DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN fsi DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN fsi_calc_garea DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN land_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN tot_main_bldg_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN sub_bldg_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN tot_fmly_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN tot_hehd_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN tot_ho_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN tot_park_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN perm_ymd DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN bgcons_ymd DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN use_aprv_ymd DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_summary ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_title (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_title,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_title IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_title ALTER COLUMN title_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN register_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN source_service DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN bldg_gbn_no DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN bldg_kind_cd DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN bldg_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN dong DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN main_sub_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN main_sub_seqno DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN main_use_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN stru_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN roof_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN lega_yn DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN vio_bldg_yn DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN larea DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN barea DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN garea DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN land_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN fmly_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN hehd_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN ho_cnt DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_floor (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_floor,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_floor IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_floor ALTER COLUMN floor_id DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN title_id DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN flr_gbn_cd DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN flr DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN main_use_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN stru_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN btm_area DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_floor ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_title_owner (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_title_owner,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_title_owner IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN owner_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN title_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN owner_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN dregno DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN own_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN jibun_desc DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN detl_addr DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN last_yn DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN adj_ymd DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title_owner ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_title_change (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_title_change,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_title_change IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_title_change ALTER COLUMN change_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN title_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN chg_rsn_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN chg_cntn DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN chg_ymd DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_title_change ALTER COLUMN field_presence DROP NOT NULL;

CREATE TABLE kras.stage_building_unit (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_unit,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_unit IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_unit ALTER COLUMN building_unit_id DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN register_id DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN bldg_gbn_no DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN parent_bno DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN bldg_kind_cd DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN dong_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN flr_ho_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN bmap_yn DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN garea DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_unit ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_exclusive (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_exclusive,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_exclusive IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN exclusive_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN building_unit_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN request_key DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN bldg_gbn_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN upper_bldg_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_exclusive_area (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_exclusive_area,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_exclusive_area IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN area_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN exclusive_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN expos_comm_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN flr DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN flr_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN main_sub_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN main_use_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN stru_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_area ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_exclusive_owner (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_exclusive_owner,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_exclusive_owner IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN owner_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN exclusive_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN owner_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN dregno DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN own_gbn_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN jibun_desc DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN detl_addr DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN chg_rsn_nm DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN last_yn DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN chg_ymd DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_owner ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_building_exclusive_price (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.building_exclusive_price,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_building_exclusive_price IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN price_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN exclusive_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN base_ymd DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN house_prc DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_building_exclusive_price ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_koreps_land_price (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.koreps_land_price,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_koreps_land_price IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN price_id DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN base_year DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN base_mon DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN jibun DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN jiga_jibn DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN pann_jiga DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN pann_ymd DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_koreps_land_price ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_house_price (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.house_price,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_house_price IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_house_price ALTER COLUMN price_id DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN base_year DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN stdmt DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN dong_no DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN land_area DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN land_calc_area DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN bldg_area DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN bldg_calc_area DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN indi_house_prc DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_house_price ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_final_land_price (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.final_land_price,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_final_land_price IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_final_land_price ALTER COLUMN price_id DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN base_year DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN stdmt DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN jiga DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_final_land_price ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_read_land_price (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.read_land_price,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_read_land_price IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_read_land_price ALTER COLUMN price_id DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN cald_stdmt DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN seqno DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN jimok DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN land_loc_addr DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN decn_jiga DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN read_jiga DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN py_jiga DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN parea DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_read_land_price ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_attribute (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_attribute,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_attribute IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_attribute ALTER COLUMN attribute_id DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN land_seqno DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN sgg_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN land_loc_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN ledg_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN bobn DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN bubn DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN land_loc_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN jimok DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN jimok_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN own_gbn DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN land_use DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN geo_form DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN geo_hl DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN road_side DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN spfc1 DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN pann_year DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN stdmt DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN land_mov_rsn_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN parea DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN spfc1_area DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN pnilp DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN calc_jiga DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN py_jiga DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN land_mov_ymd DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_attribute ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_use_attribute (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_use_attribute,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_use_attribute IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN attribute_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN ctype DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN divno DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN gubun DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN lawnm DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN seq DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN ucode DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN uname DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN unm DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_attribute ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_use_zone (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_use_zone,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_use_zone IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN zone_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN use_zone_zone_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN use_zone_zone_cd_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN cflt_yn DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_zone ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_use_plan (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_use_plan,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_use_plan IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN plan_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN pnu DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN request_key DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN land_loc_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN jibn DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN jimok DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN jimok_nm DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN jiga_ym DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN scale DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN iss_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN iss_scale DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN adm_sect_head DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN parea DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN jiga DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_use_restriction (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_use_restriction,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_use_restriction IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN restriction_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN plan_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN seqno DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN ucode DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN law_full_cd DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN law_level DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN law_contents DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN uselaw_a DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN uselaw_b DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN use_restrict DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_restriction ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.stage_land_use_plan_asset (
    item_id bigint NOT NULL REFERENCES kras.sync_item(item_id),
    row_no bigint NOT NULL CHECK(row_no > 0),
    LIKE kras.land_use_plan_asset,
    PRIMARY KEY(item_id,row_no)
);

COMMENT ON TABLE kras.stage_land_use_plan_asset IS '검증 중 업무행. 원본 PK/FK/identity/default를 복사하지 않음; 업무 ID는 NULL 허용.';

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN asset_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN plan_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN file_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN asset_kind DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN mime_type DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN text DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN scale DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN width DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN height DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN legend_width DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN legend_height DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN source_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN observed_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN row_hash DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN updated_at DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN parent_record_no DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN extra_attributes DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN field_presence DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN last_seen_item_id DROP NOT NULL;

ALTER TABLE kras.stage_land_use_plan_asset ALTER COLUMN record_status DROP NOT NULL;

CREATE TABLE kras.land_price_file_row (
    item_id bigint NOT NULL,
    org_cd kras.org_code NOT NULL,
    row_no bigint NOT NULL CHECK(row_no > 0),
    land_cd kras.pnu_code NOT NULL,
    base_year varchar(4),
    base_mon varchar(2),
    jiga numeric(12,0),
    pyo_yn varchar(1),
    PRIMARY KEY(item_id,row_no),
    FOREIGN KEY(item_id,org_cd) REFERENCES kras.sync_item(item_id,org_cd)
);

COMMENT ON TABLE kras.land_price_file_row IS 'KRAS000039 기존 공시지가 전체 TXT 호환. pyo_yn은 파일에서만 채움.';

CREATE INDEX ix_land_price_file_row_1 ON kras.land_price_file_row  (org_cd,land_cd,base_year,base_mon);

INSERT INTO kras.sync_dataset(dataset_code,source_system,service_code,collection_mode,max_query_days) VALUES
    ('land_info','KRAS','KRAS000002','DETAIL',NULL),
    ('shr_ymb','KRAS','KRAS000003','DETAIL',NULL),
    ('land_mov_hist','KRAS','KRAS000006','DETAIL',NULL),
    ('own_rgt_hist','KRAS','KRAS000007','DETAIL',NULL),
    ('bldg_hds_info','KRAS','KRAS000014','DETAIL',NULL),
    ('cbldg_hds_info','KRAS','KRAS000015','DETAIL',NULL),
    ('cbldg_dfhs_info','KRAS','KRAS000016','DETAIL',NULL),
    ('bldg_ledg_gen_hds_info','KRAS','KRAS000017','DETAIL',NULL),
    ('land_use_plan_attr','KRAS','KRAS000025','DETAIL',NULL),
    ('land_use_plan_info','KRAS','KRAS000026','DETAIL',NULL),
    ('use_zone','KRAS','KRAS000027','DETAIL',NULL),
    ('land_bldg_check','KRAS','KRAS000101','DETAIL',NULL),
    ('bldg_dong_info','KRAS','KRAS000102','DETAIL',NULL),
    ('bldg_ho_info','KRAS','KRAS000103','DETAIL',NULL),
    ('land_jiga','KOREPS','KOREPS00011','DETAIL',NULL),
    ('house_info','KOREPS','KOREPS00033','DETAIL',NULL),
    ('fin_dec_jiga','KOREPS','KOREPS00034','DETAIL',NULL),
    ('read_dec_jiga','KOREPS','KOREPS00035','DETAIL',NULL),
    ('land_attr','KOREPS','KOREPS00047','DETAIL',NULL),
    ('land_basic_file','KRAS','KRAS000040','FULL',NULL),
    ('land_price_file','KRAS','KRAS000039','FULL',NULL),
    ('cadastral_file','KRAS','KRAS000038','FILE',NULL),
    ('usezone_file','KRAS','KRAS000038','FILE',NULL),
    ('layer_list','KRAS','KRAS000037','DETAIL',NULL),
    ('land_change','KRAS',NULL,'CHANGE',10),
    ('land_owner_change','KRAS',NULL,'CHANGE',10),
    ('unit_owner_change','KRAS',NULL,'CHANGE',NULL),
    ('collective_building','KRAS',NULL,'DETAIL',NULL),
    ('collective_unit','KRAS',NULL,'DETAIL',NULL),
    ('land_right','KRAS',NULL,'DETAIL',NULL),
    ('unit_ownership_history','KRAS',NULL,'DETAIL',NULL),
    ('integrated_building','KRAS',NULL,'DETAIL',NULL),
    ('building_image','KRAS',NULL,'DETAIL',NULL);


-- 조회 뷰는 게시 완료 버전만 제공한다. 기존 public/ods 객체는 변경하지 않는다.
CREATE VIEW kras.lp_pa_cbnd AS
SELECT f.uid, f.geom, f.jibun, f.bchk, f.pnu
FROM kras.cadastral_feature f
JOIN kras.sync_publication p ON p.item_id=f.item_id AND p.org_cd=f.org_cd
    AND p.dataset_code='cadastral_file' AND p.scope_key='LAYER:' || f.layer_code
JOIN kras.sync_item i ON i.item_id=f.item_id
WHERE i.status='SUCCESS' AND i.is_complete AND i.rows_rejected=0;

CREATE VIEW kras.lt_c_uzone AS
SELECT f.mnum, f.remark, f.alias, f.layer_code, f.theme_code, f.theme_name,
       f.org_cd, f.uid, f.geom
FROM kras.usezone_feature f
JOIN kras.spatial_publication p ON p.org_cd=f.org_cd
JOIN kras.spatial_release r ON r.release_id=p.release_id AND r.org_cd=p.org_cd
JOIN kras.spatial_release_member m ON m.release_id=r.release_id
    AND m.org_cd=f.org_cd AND m.layer_code=f.layer_code AND m.item_id=f.item_id
JOIN kras.sync_item i ON i.item_id=f.item_id
WHERE r.status='PUBLISHED' AND i.status='SUCCESS' AND i.is_complete AND i.rows_rejected=0;

CREATE VIEW kras.api_current_response AS
SELECT p.bundle_kind, p.bundle_id, r.*
FROM kras.api_publication p
JOIN kras.api_bundle b ON b.bundle_id=p.bundle_id AND b.org_cd=p.org_cd AND b.pnu=p.pnu
JOIN kras.api_bundle_member m ON m.bundle_id=b.bundle_id
JOIN kras.api_response r ON r.response_id=m.response_id
WHERE b.status='PUBLISHED' AND b.is_complete;

CREATE VIEW kras.land_frst_ledg AS
SELECT p.adm_sect_cd AS adm_sec_cd, p.land_loc_cd, p.ledg_gbn, p.bobn, p.bubn,
       b.jimok, b.parea, b.own_gbn AS owngbn, p.org_cd
FROM kras.land_basic b JOIN kras.parcel p ON p.pnu=b.pnu
WHERE b.record_status='ACTIVE' AND p.record_status='ACTIVE';

CREATE VIEW kras.anvm_jiga AS
SELECT f.land_cd, f.base_year, f.jiga, f.base_mon, f.pyo_yn, f.org_cd
FROM kras.land_price_file_row f
JOIN kras.sync_publication p ON p.item_id=f.item_id AND p.org_cd=f.org_cd
    AND p.dataset_code='land_price_file' AND p.scope_key='ALL'
JOIN kras.sync_item i ON i.item_id=f.item_id
WHERE i.status='SUCCESS' AND i.is_complete AND i.rows_rejected=0;

COMMENT ON VIEW kras.lp_pa_cbnd IS '연속지적 조회 전용. INSERT/DELETE 대상 아님.';
COMMENT ON VIEW kras.lt_c_uzone IS '용도지역 조회 전용. 모든 레이어 검증 후 release 게시.';
COMMENT ON VIEW kras.api_current_response IS 'bundle 고정 후 조회. 수집 지연 정책은 API 계층에서 적용.';
COMMENT ON VIEW kras.land_frst_ledg IS '토지기본정보 기존 컬럼 계약. 상세 대장과 구별.';
COMMENT ON VIEW kras.anvm_jiga IS '전체 공시지가 TXT 출처. jiga는 12자리로 확장, pyo_yn 추정 금지.';

-- ============================================================================
-- 2026-09-16 검토 반영. 이 파일은 신규 구축 DDL이며 운영 DB 마이그레이션이 아니다.
-- 게시 순서(API): contract 검증 → bundle(DRAFT) → api_bundle_request 기대목록
-- → seal_api_bundle → 응답/member 수집 → item SUCCESS → publish_api_bundle.
-- 공간: 정규화 layer_list 원본 → release(DRAFT)/expected → seal_spatial_release
-- → 레이어 item/도형/member → item SUCCESS → publish_spatial_release.
-- SUCCESS item 및 PUBLISHED 집합은 불변. 재시도는 새 item/version을 만든다.
-- 모든 함수는 invoker 권한으로 실행한다. 일반 계정에 소유자/DDL 권한을 주지 않는다.
-- ============================================================================

CREATE FUNCTION kras.request_key(p_dataset text, p_version text, p_pnu text,
    p_bno text, p_params jsonb) RETURNS kras.sha256
LANGUAGE sql IMMUTABLE STRICT SET search_path FROM CURRENT AS $$
    SELECT encode(sha256(convert_to(jsonb_build_array(
        p_dataset,p_version,p_pnu,p_bno,p_params)::text,'UTF8')),'hex')::kras.sha256
$$;
CREATE FUNCTION kras.entity_key(p_params jsonb) RETURNS text
LANGUAGE sql IMMUTABLE STRICT SET search_path FROM CURRENT AS $$
    SELECT 'KEY:' || encode(sha256(convert_to(p_params::text,'UTF8')),'hex')
$$;
COMMENT ON FUNCTION kras.request_key(text,text,text,text,jsonb) IS
    '호출자는 미전달/기본값을 계약대로 정규화. 빈 bno는 빈 문자열, 옵션은 JSON 객체. 기관은 PNU에 포함.';
COMMENT ON FUNCTION kras.entity_key(jsonb) IS
    '긴 동층호실을 키에 연결하지 않음. 원문은 request_params에 보존.';
ALTER TABLE kras.sync_item ADD CHECK(scope_key='ALL' OR scope_key ~ '^LAYER:.{1,80}$' OR scope_key ~ '^KEY:[0-9a-f]{64}$');
ALTER TABLE kras.sync_checkpoint ADD CHECK(scope_key='ALL' OR scope_key ~ '^LAYER:.{1,80}$' OR scope_key ~ '^KEY:[0-9a-f]{64}$');
ALTER TABLE kras.sync_publication ADD CHECK(scope_key='ALL' OR scope_key ~ '^LAYER:.{1,80}$' OR scope_key ~ '^KEY:[0-9a-f]{64}$');
ALTER TABLE kras.sync_work ADD CHECK(entity_key=kras.entity_key(request_params));

-- 기관 복합 FK: stage가 있는 39개 업무 테이블 전체에 적용한다.
CREATE TABLE kras.business_dataset (
    table_name name NOT NULL, dataset_code varchar(80) NOT NULL REFERENCES kras.sync_dataset,
    PRIMARY KEY(table_name,dataset_code)
);
INSERT INTO kras.business_dataset VALUES
 ('parcel','land_basic_file'),('parcel','land_info'),('parcel','land_bldg_check'),('parcel','cadastral_file'),
 ('land_basic','land_basic_file'),('land_register','land_info'),('land_owner','land_info'),
 ('land_share','shr_ymb'),('land_presence','land_bldg_check'),('land_price','land_info'),
 ('collective_building','collective_building'),('collective_unit','collective_unit'),
 ('land_right','land_right'),('unit_ownership_history','land_right'),('unit_ownership_history','unit_ownership_history'),
 ('land_movement_history','land_mov_hist'),('land_movement_relation','land_mov_hist'),
 ('land_ownership_history','own_rgt_hist'),('land_change_event','land_change'),
 ('integrated_building','integrated_building'),('building_parcel','integrated_building'),('building_image','building_image'),
 ('building_register','bldg_dong_info'),('building_summary','bldg_ledg_gen_hds_info'),
 ('building_title','bldg_hds_info'),('building_title','cbldg_hds_info'),
 ('building_floor','bldg_hds_info'),('building_title_owner','bldg_hds_info'),('building_title_change','bldg_hds_info'),
 ('building_unit','bldg_ho_info'),('building_exclusive','cbldg_dfhs_info'),
 ('building_exclusive_area','cbldg_dfhs_info'),('building_exclusive_owner','cbldg_dfhs_info'),('building_exclusive_price','cbldg_dfhs_info'),
 ('koreps_land_price','land_jiga'),('house_price','house_info'),('final_land_price','fin_dec_jiga'),('read_land_price','read_dec_jiga'),
 ('land_attribute','land_attr'),('land_use_attribute','land_use_plan_attr'),('land_use_zone','use_zone'),
 ('land_use_plan','land_use_plan_info'),('land_use_restriction','land_use_plan_info'),('land_use_plan_asset','land_use_plan_info');

DO $business$
DECLARE t record; f record; parent_col name; child_col name;
BEGIN
 FOR t IN SELECT DISTINCT table_name FROM kras.business_dataset LOOP
   IF NOT EXISTS(SELECT FROM pg_attribute WHERE attrelid=format('kras.%I',t.table_name)::regclass AND attname='org_cd') THEN
     EXECUTE format('ALTER TABLE kras.%I ADD COLUMN org_cd kras.org_code NOT NULL',t.table_name);
     EXECUTE format('ALTER TABLE kras.%I ADD COLUMN org_cd kras.org_code','stage_'||t.table_name);
   END IF;
   EXECUTE format('ALTER TABLE kras.%I ADD FOREIGN KEY(source_item_id,org_cd) REFERENCES kras.sync_item(item_id,org_cd)',t.table_name);
   IF EXISTS(SELECT FROM pg_attribute WHERE attrelid=format('kras.%I',t.table_name)::regclass AND attname='last_seen_item_id') THEN
     EXECUTE format('ALTER TABLE kras.%I ADD FOREIGN KEY(last_seen_item_id,org_cd) REFERENCES kras.sync_item(item_id,org_cd)',t.table_name);
   END IF;
 END LOOP;
 -- 모든 업무 부모의 단일 PK/참조키에 기관 UNIQUE를 만들어 자식 복합 FK를 추가.
 FOR f IN SELECT c.*, src.relname child_table, dst.relname parent_table
     FROM pg_constraint c JOIN pg_class src ON src.oid=c.conrelid JOIN pg_class dst ON dst.oid=c.confrelid
     WHERE c.contype='f' AND cardinality(c.conkey)=1
       AND src.relnamespace='kras'::regnamespace AND dst.relnamespace='kras'::regnamespace
       AND src.relname IN(SELECT table_name FROM kras.business_dataset)
       AND dst.relname IN(SELECT table_name FROM kras.business_dataset)
 LOOP
   SELECT attname INTO child_col FROM pg_attribute WHERE attrelid=f.conrelid AND attnum=f.conkey[1];
   SELECT attname INTO parent_col FROM pg_attribute WHERE attrelid=f.confrelid AND attnum=f.confkey[1];
   IF NOT EXISTS(SELECT FROM pg_constraint WHERE conrelid=f.confrelid AND conname='uq_org_'||f.parent_table||'_'||parent_col) THEN
     EXECUTE format('ALTER TABLE kras.%I ADD CONSTRAINT %I UNIQUE(%I,org_cd)',f.parent_table,'uq_org_'||f.parent_table||'_'||parent_col,parent_col);
   END IF;
   EXECUTE format('ALTER TABLE kras.%I ADD FOREIGN KEY(%I,org_cd) REFERENCES kras.%I(%I,org_cd)',f.child_table,child_col,f.parent_table,parent_col);
 END LOOP;
END $business$;

CREATE FUNCTION kras.guard_business_row() RETURNS trigger
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE i kras.sync_item; j kras.sync_item; c record; old_doc jsonb; new_doc jsonb; col text;
BEGIN
 IF TG_OP='DELETE' THEN
   IF TG_TABLE_NAME IN ('parcel','building_register','building_unit','collective_building','collective_unit') THEN
     RAISE EXCEPTION 'Stable identity % must be retained; mark record_status instead',TG_TABLE_NAME;
   END IF;
   RETURN OLD;
 END IF;
 SELECT * INTO STRICT i FROM kras.sync_item WHERE item_id=NEW.source_item_id FOR SHARE;
 IF NEW.org_cd IS NULL THEN NEW.org_cd:=i.org_cd; END IF;
 IF NEW.org_cd<>i.org_cd OR i.status IN('FAILED','BLOCKED','INTERRUPTED') THEN
   RAISE EXCEPTION 'Invalid business source item/organization';
 END IF;
 IF NOT EXISTS(SELECT FROM kras.business_dataset WHERE table_name=TG_TABLE_NAME AND dataset_code=i.dataset_code) THEN
   RAISE EXCEPTION 'Dataset % cannot populate %',i.dataset_code,TG_TABLE_NAME;
 END IF;
 new_doc:=to_jsonb(NEW);
 IF (new_doc->>'last_seen_item_id') IS NOT NULL THEN
   SELECT * INTO STRICT j FROM kras.sync_item WHERE item_id=(new_doc->>'last_seen_item_id')::bigint FOR SHARE;
   IF j.org_cd<>i.org_cd OR j.dataset_code<>i.dataset_code OR j.scope_key<>i.scope_key THEN
     RAISE EXCEPTION 'last_seen item must have the same organization/dataset/scope';
   END IF;
 END IF;
 IF (new_doc->>'file_id') IS NOT NULL AND NOT EXISTS(
   SELECT FROM kras.sync_file f JOIN kras.sync_item si ON si.item_id=f.item_id
   WHERE f.file_id=(new_doc->>'file_id')::bigint AND si.org_cd=i.org_cd) THEN
   RAISE EXCEPTION 'Asset file belongs to another organization';
 END IF;
 IF TG_OP='UPDATE' THEN
   old_doc:=to_jsonb(OLD);
   IF NEW.source_item_id<OLD.source_item_id THEN RAISE EXCEPTION 'Older collection cannot overwrite newer business data'; END IF;
   -- PK와 부모 연결은 유지. 관련지번의 미해결→해결(NULL→PNU)만 허용.
   FOR c IN SELECT DISTINCT a.attname FROM pg_constraint pc
       JOIN pg_attribute a ON a.attrelid=pc.conrelid AND a.attnum=ANY(pc.conkey)
       WHERE pc.conrelid=TG_RELID AND (pc.contype='p' OR (pc.contype='f' AND pc.confrelid IN(
           SELECT format('kras.%I',table_name)::regclass FROM kras.business_dataset)))
   LOOP
     IF old_doc->>c.attname IS NOT NULL AND old_doc->c.attname IS DISTINCT FROM new_doc->c.attname THEN
       RAISE EXCEPTION 'Stable identity/parent column % cannot change',c.attname;
     END IF;
   END LOOP;
   IF TG_TABLE_NAME IN ('building_register','building_unit','collective_building','collective_unit') THEN
     FOREACH col IN ARRAY ARRAY['bldg_gbn_no','cbldg_seqno','dong','flr','ho','sil'] LOOP
       IF old_doc ? col AND old_doc->col IS DISTINCT FROM new_doc->col THEN RAISE EXCEPTION 'Identity attribute % cannot change',col; END IF;
     END LOOP;
   END IF;
   NEW.updated_at:=clock_timestamp();
 END IF;
 RETURN NEW;
END $$;
DO $triggers$
DECLARE t record;
BEGIN
 FOR t IN SELECT DISTINCT table_name FROM kras.business_dataset LOOP
   EXECUTE format('CREATE TRIGGER business_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.%I FOR EACH ROW EXECUTE FUNCTION kras.guard_business_row()',t.table_name);
 END LOOP;
END $triggers$;
-- 후보 자연키 UNIQUE는 추가하지 않는다. 부모는 재조회 시 ID 유지/상태 변경,
-- 소유자/층 등 자식 집합만 해당 부모·서비스 범위에서 트랜잭션 교체한다.

ALTER TABLE kras.land_change_event ALTER COLUMN record_no SET NOT NULL;
ALTER TABLE kras.land_change_event ADD FOREIGN KEY(source_item_id,record_no) REFERENCES kras.sync_record(item_id,record_no);
ALTER TABLE kras.land_change_event ALTER COLUMN payload_hash SET NOT NULL;
ALTER TABLE kras.land_change_event ADD CHECK(payload_hash=encode(sha256(convert_to(payload::text,'UTF8')),'hex'));
CREATE TABLE kras.change_event_effect (
 org_cd kras.org_code NOT NULL, payload_hash kras.sha256 NOT NULL,
 target_dataset varchar(80) NOT NULL REFERENCES kras.sync_dataset,
 entity_key varchar(80) NOT NULL, work_id bigint NOT NULL UNIQUE REFERENCES kras.sync_work,
 PRIMARY KEY(org_cd,payload_hash,target_dataset,entity_key)
);
CREATE FUNCTION kras.enqueue_land_change(p_event bigint,p_dataset text,p_params jsonb) RETURNS bigint
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE e kras.land_change_event; k text; w bigint;
BEGIN
 SELECT * INTO STRICT e FROM kras.land_change_event WHERE event_id=p_event;
 k:=kras.entity_key(p_params);
 PERFORM pg_advisory_xact_lock(hashtextextended('event:'||e.org_cd||':'||e.payload_hash||':'||p_dataset||':'||k,0));
 SELECT work_id INTO w FROM kras.change_event_effect WHERE org_cd=e.org_cd AND payload_hash=e.payload_hash AND target_dataset=p_dataset AND entity_key=k;
 IF FOUND THEN RETURN w; END IF;
 INSERT INTO kras.sync_work(item_id,target_dataset,entity_key,request_params)
 VALUES(e.source_item_id,p_dataset,k,p_params)
 ON CONFLICT(item_id,target_dataset,entity_key) DO UPDATE SET request_params=EXCLUDED.request_params
 RETURNING work_id INTO w;
 INSERT INTO kras.change_event_effect VALUES(e.org_cd,e.payload_hash,p_dataset,k,w);
 RETURN w;
END $$;
-- 동일 payload의 재조회는 기존 작업을 재사용한다. 정정 payload는 새 효과로 처리.
-- 하나의 item에서 여러 이벤트가 같은 필지 refresh를 공유할 수 있다.
ALTER TABLE kras.change_event_effect DROP CONSTRAINT change_event_effect_work_id_key;

-- 서비스 계약은 운영 샘플로 확인해야 한다. 미검증 계약은 응답 게시 금지.
CREATE TABLE kras.api_contract (
 dataset_code varchar(80) NOT NULL REFERENCES kras.sync_dataset,
 contract_version text NOT NULL,
 success_code text NOT NULL DEFAULT '0000',
 code_xpath text NOT NULL DEFAULT '/RESPONSE/HEADER/CODE/text()',
 empty_xpath text NOT NULL,
 verified boolean NOT NULL DEFAULT false,
 PRIMARY KEY(dataset_code,contract_version)
);
ALTER TABLE kras.api_response ADD COLUMN dataset_code varchar(80) NOT NULL;
ALTER TABLE kras.api_response ADD FOREIGN KEY(dataset_code,contract_version) REFERENCES kras.api_contract;
ALTER TABLE kras.sync_item ADD UNIQUE(item_id,org_cd,dataset_code);
ALTER TABLE kras.api_response ADD FOREIGN KEY(source_item_id,org_cd,dataset_code) REFERENCES kras.sync_item(item_id,org_cd,dataset_code);

CREATE FUNCTION kras.guard_api_response() RETURNS trigger
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE c kras.api_contract; d kras.sync_dataset; i kras.sync_item; codes xml[]; empt xml[];
BEGIN
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 SELECT * INTO STRICT i FROM kras.sync_item WHERE item_id=NEW.source_item_id FOR UPDATE;
 SELECT * INTO STRICT c FROM kras.api_contract WHERE dataset_code=NEW.dataset_code AND contract_version=NEW.contract_version FOR SHARE;
 SELECT * INTO STRICT d FROM kras.sync_dataset WHERE dataset_code=NEW.dataset_code FOR SHARE;
 IF NOT c.verified OR d.contract_status<>'VERIFIED' OR NOT d.enabled OR
    i.dataset_code<>d.dataset_code OR i.org_cd<>NEW.org_cd OR i.status IN('FAILED','BLOCKED','INTERRUPTED') OR
    NEW.source_system<>d.source_system OR NEW.service_code IS DISTINCT FROM d.service_code THEN
   RAISE EXCEPTION 'Response source/contract is not verified';
 END IF;
 IF jsonb_typeof(NEW.normalized_params)<>'object' OR NEW.request_key<>
   kras.request_key(NEW.dataset_code,NEW.contract_version,NEW.pnu,NEW.bno,NEW.normalized_params) THEN
   RAISE EXCEPTION 'Response request key mismatch';
 END IF;
 codes:=xpath(c.code_xpath,xmlparse(document NEW.response_xml));
 empt:=xpath('boolean('||c.empty_xpath||')',xmlparse(document NEW.response_xml));
 IF cardinality(codes)<>1 OR codes[1]::text<>c.success_code OR NEW.source_result_code<>c.success_code THEN
   RAISE EXCEPTION 'Source XML is not a successful response';
 END IF;
 IF cardinality(empt)<>1 OR (empt[1]::text='true')<>(NEW.data_state='CONFIRMED_EMPTY') THEN
   RAISE EXCEPTION 'Empty response classification mismatch';
 END IF;
 IF NEW.response_hash<>encode(sha256(convert_to(NEW.response_xml,'UTF8')),'hex') THEN
   RAISE EXCEPTION 'Response hash mismatch';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER api_response_validation BEFORE INSERT OR UPDATE ON kras.api_response FOR EACH ROW EXECUTE FUNCTION kras.guard_api_response();

CREATE TABLE kras.api_bundle_request (
 bundle_id bigint NOT NULL REFERENCES kras.api_bundle,
 request_key kras.sha256 NOT NULL,
 dataset_code varchar(80) NOT NULL, contract_version text NOT NULL,
 bno text NOT NULL DEFAULT '', normalized_params jsonb NOT NULL DEFAULT '{}',
 max_age_seconds bigint NOT NULL CHECK(max_age_seconds>0),
 PRIMARY KEY(bundle_id,request_key),
 FOREIGN KEY(dataset_code,contract_version) REFERENCES kras.api_contract
);
CREATE TABLE kras.api_bundle_profile (
 bundle_kind varchar(80) PRIMARY KEY,
 required_datasets varchar(80)[] NOT NULL CHECK(cardinality(required_datasets)>0)
);
INSERT INTO kras.api_bundle_profile
 SELECT 'conn:'||dataset_code,ARRAY[dataset_code] FROM kras.sync_dataset
 WHERE collection_mode='DETAIL' AND service_code IS NOT NULL AND dataset_code<>'layer_list';
INSERT INTO kras.api_bundle_profile VALUES
 ('GetLandInfo',ARRAY['land_info']),('GetJigaInfo',ARRAY['land_jiga']),('GetShareInfo',ARRAY['shr_ymb']),
 ('GetLandHistInfo',ARRAY['land_mov_hist']),('GetOwnerHistInfo',ARRAY['own_rgt_hist']),
 ('GetUseZoneList',ARRAY['land_use_plan_attr']),('LandUsePlanAttr',ARRAY['land_use_plan_attr']),
 ('GetLandBldgChk',ARRAY['land_bldg_check']),('GetBldgList',ARRAY['bldg_dong_info']),('GetHouseInfo',ARRAY['house_info']),
 ('GetJeonyubldg',ARRAY['bldg_ho_info']),('GetDjyexpos',ARRAY['cbldg_dfhs_info']),
 ('GetTojiDaejangPrint',ARRAY['land_info','land_jiga','land_mov_hist','own_rgt_hist','shr_ymb']),
 ('GetTojiDaejangPrint2',ARRAY['land_info','land_jiga','land_mov_hist','own_rgt_hist','shr_ymb']),
 ('GetBldgInfo',ARRAY['bldg_dong_info','bldg_hds_info','bldg_ledg_gen_hds_info','cbldg_hds_info']),
 ('GetDjyrecaptitle',ARRAY['bldg_dong_info','bldg_hds_info','bldg_ledg_gen_hds_info','cbldg_hds_info']),
 ('GetDjytitle',ARRAY['bldg_hds_info','cbldg_hds_info']),
 ('GetLandUsePlanInfo',ARRAY['land_use_plan_info','land_jiga']);
ALTER TABLE kras.api_bundle ADD FOREIGN KEY(bundle_kind) REFERENCES kras.api_bundle_profile;
ALTER TABLE kras.api_bundle_member ADD FOREIGN KEY(bundle_id,request_key) REFERENCES kras.api_bundle_request;
CREATE FUNCTION kras.guard_bundle_child() RETURNS trigger
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b kras.api_bundle; r kras.api_bundle_request; a kras.api_response; v bigint;
BEGIN
 v:=CASE WHEN TG_OP='DELETE' THEN OLD.bundle_id ELSE NEW.bundle_id END;
 IF TG_OP='UPDATE' AND NEW.bundle_id<>OLD.bundle_id THEN RAISE EXCEPTION 'Cannot move bundle child'; END IF;
 SELECT * INTO STRICT b FROM kras.api_bundle WHERE bundle_id=v FOR UPDATE;
 IF b.status='PUBLISHED' OR (TG_TABLE_NAME='api_bundle_request' AND b.status<>'DRAFT') THEN
   RAISE EXCEPTION 'Bundle manifest/member is sealed';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF TG_TABLE_NAME='api_bundle_request' THEN
   IF NEW.request_key<>kras.request_key(NEW.dataset_code,NEW.contract_version,b.pnu,NEW.bno,NEW.normalized_params)
      OR jsonb_typeof(NEW.normalized_params)<>'object' THEN RAISE EXCEPTION 'Manifest request key mismatch'; END IF;
 ELSE
   SELECT * INTO STRICT r FROM kras.api_bundle_request WHERE bundle_id=v AND request_key=NEW.request_key;
   SELECT * INTO STRICT a FROM kras.api_response WHERE response_id=NEW.response_id FOR SHARE;
   IF (a.dataset_code,a.contract_version,a.bno,a.normalized_params) IS DISTINCT FROM
      (r.dataset_code,r.contract_version,r.bno,r.normalized_params) THEN RAISE EXCEPTION 'Member is not expected request'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER bundle_request_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.api_bundle_request FOR EACH ROW EXECUTE FUNCTION kras.guard_bundle_child();
CREATE TRIGGER bundle_member_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.api_bundle_member FOR EACH ROW EXECUTE FUNCTION kras.guard_bundle_child();

CREATE FUNCTION kras.validate_api_bundle(p_id bigint) RETURNS void
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b kras.api_bundle; r record; i kras.sync_item;
BEGIN
 SELECT * INTO STRICT b FROM kras.api_bundle WHERE bundle_id=p_id FOR UPDATE;
 IF NOT EXISTS(SELECT FROM kras.api_bundle_request WHERE bundle_id=p_id) THEN RAISE EXCEPTION 'Empty API manifest'; END IF;
 IF EXISTS(SELECT FROM kras.api_bundle_request q LEFT JOIN kras.api_bundle_member m USING(bundle_id,request_key)
           WHERE q.bundle_id=p_id AND m.response_id IS NULL) THEN RAISE EXCEPTION 'Missing API member'; END IF;
 FOR r IN SELECT a.*,q.max_age_seconds FROM kras.api_bundle_member m
   JOIN kras.api_bundle_request q USING(bundle_id,request_key) JOIN kras.api_response a ON a.response_id=m.response_id
   WHERE m.bundle_id=p_id ORDER BY a.source_item_id LOOP
   SELECT * INTO STRICT i FROM kras.sync_item WHERE item_id=r.source_item_id FOR SHARE;
   IF i.status<>'SUCCESS' OR NOT i.is_complete OR i.rows_rejected<>0 OR
      r.observed_at>clock_timestamp() OR r.observed_at<clock_timestamp()-r.max_age_seconds*interval '1 second' THEN
      RAISE EXCEPTION 'API item incomplete or response expired';
   END IF;
 END LOOP;
END $$;
CREATE FUNCTION kras.guard_api_bundle() RETURNS trigger
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE required_codes varchar(80)[];
BEGIN
 IF TG_OP='INSERT' THEN
   IF NEW.status<>'DRAFT' OR NEW.is_complete THEN RAISE EXCEPTION 'Create bundle in DRAFT'; END IF;
   RETURN NEW;
 END IF;
 IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'Published API bundle is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF (NEW.bundle_id,NEW.org_cd,NEW.pnu,NEW.bundle_kind) IS DISTINCT FROM (OLD.bundle_id,OLD.org_cd,OLD.pnu,OLD.bundle_kind) THEN
   RAISE EXCEPTION 'Bundle identity cannot change';
 END IF;
 IF NEW.status='READY' AND OLD.status='DRAFT' THEN
   IF NOT EXISTS(SELECT FROM kras.api_bundle_request WHERE bundle_id=OLD.bundle_id) THEN RAISE EXCEPTION 'Empty API manifest'; END IF;
   SELECT required_datasets INTO STRICT required_codes FROM kras.api_bundle_profile WHERE bundle_kind=OLD.bundle_kind FOR SHARE;
   IF EXISTS((SELECT unnest(required_codes) EXCEPT SELECT dataset_code FROM kras.api_bundle_request WHERE bundle_id=OLD.bundle_id)
     UNION ALL (SELECT dataset_code FROM kras.api_bundle_request WHERE bundle_id=OLD.bundle_id EXCEPT SELECT unnest(required_codes))) THEN
     RAISE EXCEPTION 'Manifest services do not match bundle profile';
   END IF;
   NEW.is_complete:=true; -- sealed manifest; publish validator still checks all responses
 ELSIF NEW.status='PUBLISHED' AND OLD.status='READY' THEN
   PERFORM kras.validate_api_bundle(OLD.bundle_id); NEW.is_complete:=true; NEW.published_at:=clock_timestamp();
 ELSIF NEW.status<>OLD.status THEN RAISE EXCEPTION 'Invalid API bundle transition';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER api_bundle_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.api_bundle FOR EACH ROW EXECUTE FUNCTION kras.guard_api_bundle();
CREATE FUNCTION kras.seal_api_bundle(p_id bigint) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 UPDATE kras.api_bundle SET status='READY',is_complete=true WHERE bundle_id=p_id AND status='DRAFT';
 IF NOT FOUND THEN RAISE EXCEPTION 'Bundle must be DRAFT'; END IF;
END $$;
CREATE FUNCTION kras.guard_api_publication() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b kras.api_bundle; active_id bigint;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Replace publication instead of deleting'; END IF;
 IF TG_OP='UPDATE' AND (NEW.org_cd,NEW.pnu,NEW.bundle_kind) IS DISTINCT FROM (OLD.org_cd,OLD.pnu,OLD.bundle_kind) THEN RAISE EXCEPTION 'Publication identity cannot change'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('api:'||NEW.org_cd||':'||NEW.pnu||':'||NEW.bundle_kind,0));
 SELECT * INTO STRICT b FROM kras.api_bundle WHERE bundle_id=NEW.bundle_id FOR UPDATE;
 IF b.status<>'PUBLISHED' THEN RAISE EXCEPTION 'Bundle has not passed publication validation'; END IF;
 PERFORM kras.validate_api_bundle(b.bundle_id);
 SELECT bundle_id INTO active_id FROM kras.api_publication WHERE org_cd=NEW.org_cd AND pnu=NEW.pnu AND bundle_kind=NEW.bundle_kind;
 IF active_id IS NOT NULL AND NEW.bundle_id<active_id THEN RAISE EXCEPTION 'Stale API publication'; END IF;
 IF active_id IS NOT NULL AND EXISTS(
   SELECT FROM kras.api_bundle_member old_m JOIN kras.api_response old_r ON old_r.response_id=old_m.response_id
   JOIN kras.api_bundle_member new_m ON new_m.bundle_id=NEW.bundle_id AND new_m.request_key=old_m.request_key
   JOIN kras.api_response new_r ON new_r.response_id=new_m.response_id
   WHERE old_m.bundle_id=active_id AND new_r.source_item_id<old_r.source_item_id
 ) THEN RAISE EXCEPTION 'New bundle would regress a request response'; END IF;
 NEW.published_at:=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER api_publication_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.api_publication FOR EACH ROW EXECUTE FUNCTION kras.guard_api_publication();
CREATE FUNCTION kras.publish_api_bundle(p_id bigint) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b kras.api_bundle;
BEGIN
 SELECT * INTO STRICT b FROM kras.api_bundle WHERE bundle_id=p_id;
 PERFORM pg_advisory_xact_lock(hashtextextended('api:'||b.org_cd||':'||b.pnu||':'||b.bundle_kind,0));
 UPDATE kras.api_bundle SET status='PUBLISHED' WHERE bundle_id=p_id AND status='READY';
 IF NOT FOUND THEN RAISE EXCEPTION 'Bundle must be READY'; END IF;
 INSERT INTO kras.api_publication(org_cd,pnu,bundle_kind,bundle_id) VALUES(b.org_cd,b.pnu,b.bundle_kind,p_id)
 ON CONFLICT(org_cd,pnu,bundle_kind) DO UPDATE SET bundle_id=EXCLUDED.bundle_id;
END $$;

ALTER TABLE kras.spatial_release ADD COLUMN catalog_item_id bigint NOT NULL REFERENCES kras.sync_item;
CREATE TABLE kras.spatial_release_expected (
 release_id bigint NOT NULL REFERENCES kras.spatial_release,
 layer_code varchar(80) NOT NULL REFERENCES kras.spatial_layer,
 PRIMARY KEY(release_id,layer_code)
);
ALTER TABLE kras.spatial_release_member ADD FOREIGN KEY(release_id,layer_code) REFERENCES kras.spatial_release_expected;
CREATE FUNCTION kras.guard_spatial_child() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE r kras.spatial_release; i kras.sync_item; v bigint;
BEGIN
 v:=CASE WHEN TG_OP='DELETE' THEN OLD.release_id ELSE NEW.release_id END;
 IF TG_OP='UPDATE' AND NEW.release_id<>OLD.release_id THEN RAISE EXCEPTION 'Cannot move release child'; END IF;
 SELECT * INTO STRICT r FROM kras.spatial_release WHERE release_id=v FOR UPDATE;
 IF r.status='PUBLISHED' OR (TG_TABLE_NAME='spatial_release_expected' AND r.status<>'DRAFT') THEN RAISE EXCEPTION 'Spatial manifest/member sealed'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF TG_TABLE_NAME='spatial_release_member' THEN
   SELECT * INTO STRICT i FROM kras.sync_item WHERE item_id=NEW.item_id FOR SHARE;
   IF i.org_cd<>r.org_cd OR i.dataset_code<>'usezone_file' OR i.scope_key<>'LAYER:'||NEW.layer_code THEN
     RAISE EXCEPTION 'Layer item organization/dataset/scope mismatch';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER spatial_expected_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.spatial_release_expected FOR EACH ROW EXECUTE FUNCTION kras.guard_spatial_child();
CREATE TRIGGER spatial_member_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.spatial_release_member FOR EACH ROW EXECUTE FUNCTION kras.guard_spatial_child();
CREATE FUNCTION kras.validate_spatial_manifest(p_id bigint) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE r kras.spatial_release; i kras.sync_item;
BEGIN
 SELECT * INTO STRICT r FROM kras.spatial_release WHERE release_id=p_id FOR UPDATE;
 SELECT * INTO STRICT i FROM kras.sync_item WHERE item_id=r.catalog_item_id FOR SHARE;
 IF i.org_cd<>r.org_cd OR i.dataset_code<>'layer_list' OR i.status<>'SUCCESS' OR NOT i.is_complete OR i.rows_rejected<>0 THEN
   RAISE EXCEPTION 'Catalog is not a complete successful layer list';
 END IF;
 -- sync_record의 정규화 목록: {"dataset_code":"usezone_file","layer_code":"..."}
 IF EXISTS(SELECT FROM kras.sync_record WHERE item_id=i.item_id AND (validation_status<>'VALID' OR
       payload->>'dataset_code' IS NULL OR payload->>'layer_code' IS NULL)) THEN RAISE EXCEPTION 'Invalid normalized layer catalog'; END IF;
 IF EXISTS(SELECT payload->>'layer_code' FROM kras.sync_record WHERE item_id=i.item_id
       AND payload->>'dataset_code'='usezone_file' GROUP BY payload->>'layer_code' HAVING count(*)>1) THEN
   RAISE EXCEPTION 'Duplicate source catalog layer';
 END IF;
 IF EXISTS(
   (SELECT payload->>'layer_code' FROM kras.sync_record WHERE item_id=i.item_id AND payload->>'dataset_code'='usezone_file'
    EXCEPT SELECT layer_code FROM kras.spatial_release_expected WHERE release_id=p_id)
   UNION ALL
   (SELECT layer_code FROM kras.spatial_release_expected WHERE release_id=p_id
    EXCEPT SELECT payload->>'layer_code' FROM kras.sync_record WHERE item_id=i.item_id AND payload->>'dataset_code'='usezone_file')
 ) THEN RAISE EXCEPTION 'Expected layers differ from source catalog'; END IF;
END $$;
CREATE FUNCTION kras.validate_spatial_release(p_id bigint) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE member_row record; i kras.sync_item; n bigint; raw_count bigint;
BEGIN
 PERFORM kras.validate_spatial_manifest(p_id);
 IF EXISTS(SELECT FROM kras.spatial_release_expected e LEFT JOIN kras.spatial_release_member m USING(release_id,layer_code)
           WHERE e.release_id=p_id AND m.item_id IS NULL) THEN RAISE EXCEPTION 'Missing spatial layer'; END IF;
 FOR member_row IN SELECT * FROM kras.spatial_release_member WHERE release_id=p_id ORDER BY item_id LOOP
   SELECT * INTO STRICT i FROM kras.sync_item WHERE item_id=member_row.item_id FOR SHARE;
   SELECT count(*) INTO n FROM kras.usezone_feature WHERE item_id=member_row.item_id AND layer_code=member_row.layer_code;
   SELECT count(*) INTO raw_count FROM kras.spatial_feature WHERE item_id=member_row.item_id;
   IF i.status<>'SUCCESS' OR NOT i.is_complete OR i.rows_rejected<>0 OR n<>i.rows_valid OR raw_count<>n THEN
     RAISE EXCEPTION 'Incomplete layer or feature count mismatch: %',member_row.layer_code;
   END IF;
 END LOOP;
END $$;
CREATE FUNCTION kras.guard_spatial_release() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   IF NEW.status<>'DRAFT' THEN RAISE EXCEPTION 'Create release in DRAFT'; END IF; RETURN NEW;
 END IF;
 IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'Published spatial release is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF (NEW.release_id,NEW.org_cd,NEW.catalog_item_id) IS DISTINCT FROM (OLD.release_id,OLD.org_cd,OLD.catalog_item_id) THEN RAISE EXCEPTION 'Release identity/catalog cannot change'; END IF;
 IF NEW.status='READY' AND OLD.status='DRAFT' THEN PERFORM kras.validate_spatial_manifest(OLD.release_id);
 ELSIF NEW.status='PUBLISHED' AND OLD.status='READY' THEN PERFORM kras.validate_spatial_release(OLD.release_id); NEW.published_at:=clock_timestamp();
 ELSIF NEW.status<>OLD.status THEN RAISE EXCEPTION 'Invalid spatial release transition'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER spatial_release_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.spatial_release FOR EACH ROW EXECUTE FUNCTION kras.guard_spatial_release();
CREATE FUNCTION kras.seal_spatial_release(p_id bigint) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 UPDATE kras.spatial_release SET status='READY' WHERE release_id=p_id AND status='DRAFT';
 IF NOT FOUND THEN RAISE EXCEPTION 'Release must be DRAFT'; END IF;
END $$;
CREATE FUNCTION kras.guard_spatial_publication() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE r kras.spatial_release; active_id bigint;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Replace publication instead of deleting'; END IF;
 IF TG_OP='UPDATE' AND NEW.org_cd<>OLD.org_cd THEN RAISE EXCEPTION 'Publication identity cannot change'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('spatial:'||NEW.org_cd,0));
 SELECT * INTO STRICT r FROM kras.spatial_release WHERE release_id=NEW.release_id FOR UPDATE;
 IF r.status<>'PUBLISHED' THEN RAISE EXCEPTION 'Release not validated'; END IF;
 PERFORM kras.validate_spatial_release(r.release_id);
 SELECT release_id INTO active_id FROM kras.spatial_publication WHERE org_cd=NEW.org_cd;
 IF active_id IS NOT NULL AND NEW.release_id<active_id THEN RAISE EXCEPTION 'Stale spatial publication'; END IF;
 IF active_id IS NOT NULL AND (
   r.catalog_item_id<(SELECT catalog_item_id FROM kras.spatial_release WHERE release_id=active_id)
   OR EXISTS(SELECT FROM kras.spatial_release_member old_m JOIN kras.spatial_release_member new_m
       ON new_m.release_id=NEW.release_id AND new_m.layer_code=old_m.layer_code
       WHERE old_m.release_id=active_id AND new_m.item_id<old_m.item_id)
 ) THEN RAISE EXCEPTION 'New release would regress source versions'; END IF;
 NEW.published_at:=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER spatial_publication_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.spatial_publication FOR EACH ROW EXECUTE FUNCTION kras.guard_spatial_publication();
CREATE FUNCTION kras.publish_spatial_release(p_id bigint) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE r kras.spatial_release;
BEGIN
 SELECT * INTO STRICT r FROM kras.spatial_release WHERE release_id=p_id;
 PERFORM pg_advisory_xact_lock(hashtextextended('spatial:'||r.org_cd,0));
 UPDATE kras.spatial_release SET status='PUBLISHED' WHERE release_id=p_id AND status='READY';
 IF NOT FOUND THEN RAISE EXCEPTION 'Release must be READY'; END IF;
 INSERT INTO kras.spatial_publication(org_cd,release_id) VALUES(r.org_cd,p_id)
 ON CONFLICT(org_cd) DO UPDATE SET release_id=EXCLUDED.release_id;
END $$;

-- 같은 item의 레코드 변경과 SUCCESS 전환이 동일 item 행 잠금으로 직렬화된다.
CREATE FUNCTION kras.guard_item_content() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE v bigint; s text;
BEGIN
 IF TG_OP<>'INSERT' THEN
   v:=coalesce((to_jsonb(OLD)->>'item_id')::bigint,(to_jsonb(OLD)->>'source_item_id')::bigint);
   SELECT status INTO STRICT s FROM kras.sync_item WHERE item_id=v FOR UPDATE;
   IF s='SUCCESS' THEN RAISE EXCEPTION 'Successful item content is immutable'; END IF;
 END IF;
 IF TG_OP<>'DELETE' THEN
   v:=coalesce((to_jsonb(NEW)->>'item_id')::bigint,(to_jsonb(NEW)->>'source_item_id')::bigint);
   SELECT status INTO STRICT s FROM kras.sync_item WHERE item_id=v FOR UPDATE;
   IF s='SUCCESS' THEN RAISE EXCEPTION 'Successful item content is immutable'; END IF;
   RETURN NEW;
 END IF;
 RETURN OLD;
END $$;
DO $immutable$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['sync_record','sync_file','api_response','spatial_feature','cadastral_feature','usezone_feature','land_price_file_row'] LOOP
   EXECUTE format('CREATE TRIGGER a_item_content_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.%I FOR EACH ROW EXECUTE FUNCTION kras.guard_item_content()',t);
 END LOOP;
END $immutable$;
CREATE FUNCTION kras.guard_item_transition() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE n bigint;
BEGIN
 IF TG_OP<>'INSERT' AND OLD.status='SUCCESS' THEN RAISE EXCEPTION 'Successful item is immutable; create retry item'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF TG_OP='UPDATE' AND (NEW.item_id,NEW.org_cd,NEW.run_id,NEW.dataset_code,NEW.scope_key,NEW.window_start,NEW.window_end_exclusive) IS DISTINCT FROM
   (OLD.item_id,OLD.org_cd,OLD.run_id,OLD.dataset_code,OLD.scope_key,OLD.window_start,OLD.window_end_exclusive) THEN RAISE EXCEPTION 'Item identity cannot change'; END IF;
 IF NEW.status='SUCCESS' THEN
   IF TG_OP='INSERT' THEN RAISE EXCEPTION 'Item must collect before SUCCESS'; END IF;
   IF NOT EXISTS(SELECT FROM kras.sync_dataset WHERE dataset_code=NEW.dataset_code AND enabled AND contract_status='VERIFIED') THEN
     RAISE EXCEPTION 'Dataset must be verified/enabled before SUCCESS';
   END IF;
   IF NEW.rows_valid<>NEW.rows_received OR NEW.rows_rejected<>0 OR NOT NEW.is_complete THEN RAISE EXCEPTION 'Incomplete item counts'; END IF;
   IF EXISTS(SELECT FROM kras.sync_record WHERE item_id=NEW.item_id AND validation_status<>'VALID') THEN RAISE EXCEPTION 'Unvalidated source records'; END IF;
   IF NEW.dataset_code='layer_list' THEN
     SELECT count(*) INTO n FROM kras.sync_record WHERE item_id=NEW.item_id;
     IF n<>NEW.rows_valid THEN RAISE EXCEPTION 'Catalog count mismatch'; END IF;
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sync_item_transition BEFORE INSERT OR UPDATE OR DELETE ON kras.sync_item FOR EACH ROW EXECUTE FUNCTION kras.guard_item_transition();
CREATE FUNCTION kras.guard_contract() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF EXISTS(SELECT FROM kras.api_response WHERE dataset_code=OLD.dataset_code AND contract_version=OLD.contract_version) OR
    EXISTS(SELECT FROM kras.api_bundle_request WHERE dataset_code=OLD.dataset_code AND contract_version=OLD.contract_version) THEN
   RAISE EXCEPTION 'Contract in use is immutable; create a new version';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER api_contract_guard BEFORE UPDATE OR DELETE ON kras.api_contract FOR EACH ROW EXECUTE FUNCTION kras.guard_contract();

CREATE FUNCTION kras.guard_bundle_profile() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP<>'INSERT' AND EXISTS(SELECT FROM kras.api_bundle WHERE bundle_kind=OLD.bundle_kind) THEN
   RAISE EXCEPTION 'Bundle profile in use is immutable; create a new profile';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF EXISTS(SELECT FROM unnest(NEW.required_datasets) x(code) WHERE code IS NULL OR NOT EXISTS(SELECT FROM kras.sync_dataset d WHERE d.dataset_code=x.code)) THEN
   RAISE EXCEPTION 'Unknown dataset in profile';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER bundle_profile_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.api_bundle_profile FOR EACH ROW EXECUTE FUNCTION kras.guard_bundle_profile();

CREATE FUNCTION kras.guard_geometry() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE i kras.sync_item; l kras.spatial_layer; raw kras.spatial_feature;
BEGIN
 SELECT * INTO STRICT i FROM kras.sync_item WHERE item_id=NEW.item_id FOR UPDATE;
 SELECT * INTO STRICT l FROM kras.spatial_layer WHERE layer_code=NEW.layer_code FOR SHARE;
 IF l.source_epsg IS NULL OR l.dataset_code<>i.dataset_code OR i.scope_key<>'LAYER:'||NEW.layer_code OR i.org_cd<>NEW.org_cd THEN
   RAISE EXCEPTION 'Unknown CRS or layer scope/dataset mismatch';
 END IF;
 IF ST_IsEmpty(NEW.geom) OR NOT ST_IsValid(NEW.geom) THEN RAISE EXCEPTION 'Empty or invalid feature geometry'; END IF;
 IF TG_TABLE_NAME<>'spatial_feature' THEN
   SELECT * INTO STRICT raw FROM kras.spatial_feature WHERE item_id=NEW.item_id AND feature_no=NEW.feature_no;
   IF NEW.pnu IS DISTINCT FROM raw.pnu OR NOT ST_Equals(NEW.geom,raw.geom) THEN RAISE EXCEPTION 'Spatial projection differs from source'; END IF;
   IF (TG_TABLE_NAME='cadastral_feature' AND i.dataset_code<>'cadastral_file') OR
      (TG_TABLE_NAME='usezone_feature' AND i.dataset_code<>'usezone_file') THEN RAISE EXCEPTION 'Wrong projection dataset'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER geometry_guard BEFORE INSERT OR UPDATE ON kras.spatial_feature FOR EACH ROW EXECUTE FUNCTION kras.guard_geometry();
CREATE TRIGGER geometry_guard BEFORE INSERT OR UPDATE ON kras.cadastral_feature FOR EACH ROW EXECUTE FUNCTION kras.guard_geometry();
CREATE TRIGGER geometry_guard BEFORE INSERT OR UPDATE ON kras.usezone_feature FOR EACH ROW EXECUTE FUNCTION kras.guard_geometry();

CREATE FUNCTION kras.guard_sync_publication() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE i kras.sync_item; prev bigint; n bigint;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Replace publication instead of deleting'; END IF;
 IF TG_OP='UPDATE' AND (NEW.org_cd,NEW.dataset_code,NEW.scope_key) IS DISTINCT FROM (OLD.org_cd,OLD.dataset_code,OLD.scope_key) THEN RAISE EXCEPTION 'Publication identity cannot change'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('sync:'||NEW.org_cd||':'||NEW.dataset_code||':'||NEW.scope_key,0));
 SELECT * INTO STRICT i FROM kras.sync_item WHERE item_id=NEW.item_id FOR SHARE;
 IF i.status<>'SUCCESS' OR NOT i.is_complete OR i.rows_rejected<>0 THEN RAISE EXCEPTION 'Item not publishable'; END IF;
 IF i.dataset_code='cadastral_file' THEN
   SELECT count(*) INTO n FROM kras.cadastral_feature WHERE item_id=i.item_id;
   IF n<>i.rows_valid OR n<>(SELECT count(*) FROM kras.spatial_feature WHERE item_id=i.item_id) THEN RAISE EXCEPTION 'Cadastral projection incomplete'; END IF;
 END IF;
 SELECT item_id INTO prev FROM kras.sync_publication WHERE org_cd=NEW.org_cd AND dataset_code=NEW.dataset_code AND scope_key=NEW.scope_key;
 IF prev IS NOT NULL AND NEW.item_id<prev THEN RAISE EXCEPTION 'Stale item publication'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sync_publication_guard BEFORE INSERT OR UPDATE OR DELETE ON kras.sync_publication FOR EACH ROW EXECUTE FUNCTION kras.guard_sync_publication();

-- FK 확인/보존 정리 시 전체 원본 테이블 스캔 방지.
CREATE INDEX ix_sync_file_item ON kras.sync_file(item_id);
CREATE INDEX ix_sync_reject_item ON kras.sync_reject(item_id);
CREATE INDEX ix_api_member_response ON kras.api_bundle_member(response_id);
CREATE INDEX ix_spatial_member_item ON kras.spatial_release_member(item_id);
CREATE INDEX ix_spatial_release_catalog ON kras.spatial_release(catalog_item_id);
ALTER TABLE kras.sync_dataset ADD COLUMN raw_retention_days integer CHECK(raw_retention_days>0);
ALTER TABLE kras.sync_dataset ADD COLUMN stage_retention_days integer CHECK(stage_retention_days>0);
COMMENT ON COLUMN kras.sync_dataset.raw_retention_days IS 'NULL=보존기간 미확정. 자동 삭제 없음. 운영 정책으로 설정 후 참조 없는 자료만 정리.';
COMMENT ON COLUMN kras.sync_dataset.stage_retention_days IS 'NULL=미확정. 종료된 실행의 staging만 정리 대상. 원본·게시 데이터와 구별.';
CREATE VIEW kras.storage_usage AS
 SELECT c.relname AS table_name,c.reltuples::bigint AS estimated_rows,
        pg_total_relation_size(c.oid) AS total_bytes,pg_indexes_size(c.oid) AS index_bytes
 FROM pg_class c WHERE c.relnamespace='kras'::regnamespace AND c.relkind='r';

-- ALTER로 추가된 dataset_code도 조회 계약에 포함(기존 view의 r.*는 자동 확장되지 않음).
CREATE OR REPLACE VIEW kras.api_current_response AS
 SELECT p.bundle_kind,p.bundle_id,r.*
 FROM kras.api_publication p
 JOIN kras.api_bundle b ON b.bundle_id=p.bundle_id AND b.org_cd=p.org_cd AND b.pnu=p.pnu
 JOIN kras.api_bundle_member m ON m.bundle_id=b.bundle_id
 JOIN kras.api_response r ON r.response_id=m.response_id
 WHERE b.status='PUBLISHED' AND b.is_complete;

-- 추가 검토 결정:
-- 39개 stage는 계약별 검증을 위해 유지. 실제 데이터량 없이 파티션/테이블 축소를 추정하지 않는다.
-- 부모 식별자는 유지하고 삭제 대신 record_status 변경. 자식 교체 시 서비스 범위를 고정한다.
-- 빈 PNU는 게시 대상이 아님(기존 필수 FK 유지). 기존 클라이언트 사용 여부 확인 전 호환 완료 주장 금지.
-- 지도 옵션은 canonical request key에 포함. 미수집 옵션을 다른 응답으로 대체하지 않는다.
-- 운영 샘플/성능/보존기간은 SQL만으로 확정 불가. 신규 API 계약은 별도 등록·검증한다.
-- 데이터 및 시퀀스는 보존. 자동 DELETE/CASCADE/운영 스키마 이동은 수행하지 않는다.
-- 현재 SQL이 정본이다. 이전 문서의 테이블 수·게시 책임 설명은 이번 강화 이전 기준이다.

COMMIT;
