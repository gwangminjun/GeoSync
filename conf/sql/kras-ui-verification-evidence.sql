-- KRAS 연계 관리 2차 검증 근거. 기존 kras 스키마에 추가하며 업무 데이터는 변경하지 않는다.
-- 애플리케이션도 첫 저장 요청에서 테이블이 없으면 생성한다.
-- DDL 권한이 없는 운영 계정은 관리자가 이 파일을 먼저 적용하고 SELECT/INSERT 및
-- evidence_id 시퀀스 USAGE 권한을 운영 계정에 부여한다. 계정명은 배포 환경에 맞게 지정한다.
CREATE TABLE IF NOT EXISTS kras.ui_verification_evidence (
    evidence_id bigserial PRIMARY KEY,
    org_cd varchar(5) NOT NULL,
    dataset_code varchar(80) NOT NULL,
    verified_at timestamptz NOT NULL DEFAULT now(),
    verified_by varchar(100),
    mapper_version varchar(100),
    response_sample text,
    note text
);
CREATE INDEX IF NOT EXISTS ix_ui_verification_evidence_org_dataset
    ON kras.ui_verification_evidence(org_cd, dataset_code, verified_at DESC);
