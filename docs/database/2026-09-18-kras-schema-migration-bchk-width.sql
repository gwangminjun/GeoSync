-- 2026-09-18 마이그레이션: BCHK 컬럼 폭 1자리 → 2자리로 확장
--
-- 근거: 두 개의 독립 문서가 BCHK를 2자리로 정의함.
--   - conf/kras/base-tables.xml:56 <src name='bchk' type='STRING' size='2' />
--   - docs/reference/46870-data-catalog.md:178 "주요 컬럼 | PNU(19), JIBUN(15), BCHK(2), geometry"
-- 그런데 실제 저장 컬럼은 kras.cadastral_feature.bchk, public.lp_pa_cbnd.bchk 모두 varchar(1)이었다.
-- 실제 SHP 값이 2자리로 나오면 INSERT가 "value too long for type character varying(1)"로 실패해
-- 수집 전체가 롤백된다.
--
-- kras.lp_pa_cbnd 뷰가 cadastral_feature.bchk에 의존하므로 ALTER COLUMN TYPE 전에
-- 뷰를 지우고 동일 정의로 재생성한다(물리 데이터 없는 뷰라 안전, GeoServer는 public 테이블만 봄).
--
-- 영향: 폭 확장(1→2)이라 기존 데이터 손실 없음. NOT NULL 아님 — 그대로 유지.
-- 대상: kras-schema-create.sql이 이미 적용된 DB. 최초 생성 스크립트는 재실행하지 않는다.
BEGIN;

DROP VIEW kras.lp_pa_cbnd;

ALTER TABLE kras.cadastral_feature ALTER COLUMN bchk TYPE varchar(2);
ALTER TABLE public.lp_pa_cbnd ALTER COLUMN bchk TYPE varchar(2);

CREATE VIEW kras.lp_pa_cbnd AS
SELECT f.uid, f.geom, f.jibun, f.bchk, f.pnu
FROM kras.cadastral_feature f
JOIN kras.sync_publication p ON p.item_id=f.item_id AND p.org_cd=f.org_cd
    AND p.dataset_code='cadastral_file' AND p.scope_key='LAYER:' || f.layer_code
JOIN kras.sync_item i ON i.item_id=f.item_id
WHERE i.status='SUCCESS' AND i.is_complete AND i.rows_rejected=0;

COMMENT ON COLUMN kras.cadastral_feature.bchk IS
    'base-tables.xml/데이터 카탈로그 기준 2자리 — 2026-09-18 varchar(1)에서 확장.';

COMMIT;
