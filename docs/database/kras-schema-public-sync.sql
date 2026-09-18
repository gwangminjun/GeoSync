-- kras → public 승격 함수. public.lp_pa_cbnd/lt_c_uzone의 테이블 객체(OID)는 절대 바꾸지 않는다.
-- GeoServer가 이 두 테이블(및 lt_c_uzone 위의 306개 필터 뷰)을 레이어로 물고 있어 DROP/CREATE로
-- 객체를 교체하면 GeoServer 쪽 재등록이 필요할 수 있다 — 그래서 TRUNCATE+INSERT만 쓴다.
--
-- 소스는 kras.lp_pa_cbnd / kras.lt_c_uzone 뷰다. 이 뷰들은 이미 SUCCESS·완전성·PUBLISHED
-- 검증을 통과한 데이터만 보여주므로(F1/F2 수정 사항), 여기서 다시 검증하지 않는다 —
-- kras.stage_* → 업무테이블 승격과 같은 패턴으로, kras 뷰가 "검증된 스테이징" 역할을 한다.
--
-- 0건 가드: 소스가 0건이면 TRUNCATE 전에 예외를 던진다. cadastral_file/usezone_file
-- 데이터셋이 아직 UNVERIFIED/disabled인 지금 상태에서 이 함수를 돌리면 반드시 이 가드에서
-- 막혀야 정상이다 — 실수로 실행돼도 살아있는 GeoServer 레이어가 비는 사고를 막는다.
CREATE OR REPLACE FUNCTION kras.sync_public_cadastral() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM kras.lp_pa_cbnd;
  IF n = 0 THEN
    RAISE EXCEPTION 'kras.lp_pa_cbnd has 0 published rows; refusing to truncate public.lp_pa_cbnd (would blank the live GeoServer layer)';
  END IF;
  TRUNCATE public.lp_pa_cbnd;
  INSERT INTO public.lp_pa_cbnd(uid,geom,jibun,bchk,pnu)
    SELECT uid,geom,jibun,bchk,pnu FROM kras.lp_pa_cbnd;
  RETURN n;
END $$;

CREATE OR REPLACE FUNCTION kras.sync_public_usezone() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM kras.lt_c_uzone;
  IF n = 0 THEN
    RAISE EXCEPTION 'kras.lt_c_uzone has 0 published rows; refusing to truncate public.lt_c_uzone (would blank up to 306 GeoServer layers)';
  END IF;
  TRUNCATE public.lt_c_uzone;
  INSERT INTO public.lt_c_uzone(mnum,remark,alias,layer_code,theme_code,theme_name,org_cd,uid,geom)
    SELECT mnum,remark,alias,layer_code,theme_code,theme_name,org_cd,uid,geom FROM kras.lt_c_uzone;
  RETURN n;
END $$;

COMMENT ON FUNCTION kras.sync_public_cadastral() IS
  'kras 게시본을 public.lp_pa_cbnd로 승격. 배치가 kras.publish_* 이후 호출한다. 0건이면 실패.';
COMMENT ON FUNCTION kras.sync_public_usezone() IS
  'kras 게시본을 public.lt_c_uzone으로 승격. kras.publish_spatial_release 이후 호출한다. 0건이면 실패. 306개 필터 뷰는 테이블 내용만 바뀌므로 재등록 불필요.';

-- 호출 예: SELECT kras.sync_public_cadastral(); SELECT kras.sync_public_usezone();
