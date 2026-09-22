-- KRAS 연계 관리 1차 실행 기록. 기존 kras 스키마에 추가하며 업무 데이터는 변경하지 않는다.
-- 애플리케이션도 첫 실행 요청에서 테이블이 없으면 생성한다.
-- DDL 권한이 없는 운영 계정은 관리자가 이 파일을 먼저 적용하고 SELECT/INSERT/UPDATE 및
-- operation_id 시퀀스 USAGE 권한을 운영 계정에 부여한다. 계정명은 배포 환경에 맞게 지정한다.
CREATE TABLE IF NOT EXISTS kras.ui_operation_log (
    operation_id bigserial PRIMARY KEY,
    org_cd varchar(5) NOT NULL,
    dataset_code varchar(80) NOT NULL,
    action varchar(30) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'RUNNING',
    item_id bigint,
    release_id bigint,
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    request_summary varchar(300) NOT NULL DEFAULT '',
    message text,
    details jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS ix_kras_ui_operation_org
    ON kras.ui_operation_log(org_cd, operation_id DESC);
