-- 2026-09-16 마이그레이션: kras.collective_building에 GAREA(연면적) 컬럼 추가
--
-- 근거: docs/reference/kras.md §4 대지권등록부(건물조회)
--   - 서비스 항목 표(379~381행, Out rowspan="7")에는 ADM_SECT_CD/LAND_LOC_CD/LEDG_GBN/
--     BOBN/BUBN/CBLDG_SEQNO/CBLDG_NM 7개만 등재됨.
--   - 그러나 결과 XML 예시(393행, 403행) 두 인스턴스 모두 <GAREA/>가 <ADM_SECT_CD> 앞에 존재.
--     <LAND_RGT_BLDG_INFO><GAREA/><ADM_SECT_CD>44131</ADM_SECT_CD>...
--   - kras-schema-create.sql 최초 생성분(437~453행)에는 이 필드가 반영되지 않았다.
--     §7의 OWNER_ADDR/OWNER_NM(예시에만 존재)은 land_movement_history에 이미 반영됐던
--     것과 달리, §4는 같은 패턴을 놓친 것으로 확인됨.
--
-- 대상: kras-schema-create.sql이 이미 적용된 DB. 최초 생성 스크립트는 재실행하지 않는다
--   (kras-schema-create.md: "같은 SQL을 두 번 실행하면 기존 객체 중복으로 실패한다").
--
-- 영향: 기존 행은 모두 NULL로 유지된다(수집 전 데이터이므로 백필 대상 없음, 초기 게시 전
--   0건이 정상이라는 kras-schema-create.md의 검증 결과와 일치). NOT NULL을 걸지 않는다 —
--   실제 운영 응답으로 이 필드가 항상 채워지는지 확인되지 않았기 때문이다(다른 업무 컬럼과
--   동일하게 "확정 전 원본 보존, 임의 필수화 금지" 원칙을 따름).
BEGIN;

ALTER TABLE kras.collective_building
    ADD COLUMN IF NOT EXISTS garea numeric;

COMMENT ON COLUMN kras.collective_building.garea IS
    'HWP §4 결과 XML 예시의 GAREA(연면적). 서비스 항목 표에는 없었으나 실제 응답 예시에 존재해 추가. 실제 계약 확인 전 정밀도 제한 없음(numeric).';

COMMIT;
