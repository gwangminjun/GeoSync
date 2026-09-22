-- kras 스키마 더미 데이터. docs/reference/kras.md의 실제 예시값을 그대로 사용한다.
--   §1 토지(임야)대장 결과 XML(154~201행) → parcel, land_register
--   §2 공유지연명부 결과 XML(248~293행)   → land_share (2건, 홍길동/이순신)
--   §7 토지이동연혁 결과 XML(632~668행)   → land_movement_history, land_movement_relation
-- land_owner는 §1 예시의 소유내역 필드가 전부 빈 값이라 §2 인물(홍길동)을 참고해 합성했다 — 아래 표시.
--
-- org_cd=12830은 HWP 원문 예시(27170)가 아니라 이 앱의 application.yml sync.org-code 기본값을
-- 맞춘 것이다. 개발/임시 DB에서만 실행할 것 — 운영 DB에서는 org_cd/PNU가 실제 데이터와
-- 충돌하지 않는지 먼저 확인한다.
--
-- 가드 트리거(guard_business_row 등) 때문에 parcel 등 업무 테이블에 바로 못 넣고, 먼저
-- sync_run/sync_item을 만들어야 한다. business_dataset 매핑상 parcel/land_register/land_owner는
-- dataset_code='land_info', land_share는 'shr_ymb', land_movement_history(+relation)는
-- 'land_mov_hist'를 요구하므로 item을 3개로 나눴다.
--
-- 소유자 등록번호(OWNER_REGNO/DREGNO)는 원문에서 마스킹(****)되어 있어 더미로도 채우지 않고
-- NULL로 둔다. 관련지번(§7의 999-9)은 PNU로 단정하지 않는다는 설계 원칙에 따라 related_pnu는
-- NULL로 둔다.
BEGIN;

WITH
run AS (
  INSERT INTO kras.sync_run(org_cd,job_kind,period_start,period_end_exclusive)
  VALUES ('12830','MANUAL','2026-09-16','2026-09-17')
  RETURNING run_id
),
item_land_info AS (
  INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive)
  SELECT run_id,'12830','land_info','ALL','2026-09-16','2026-09-17' FROM run
  RETURNING item_id
),
item_shr AS (
  INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive)
  SELECT run_id,'12830','shr_ymb','ALL','2026-09-16','2026-09-17' FROM run
  RETURNING item_id
),
item_mov AS (
  INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive)
  SELECT run_id,'12830','land_mov_hist','ALL','2026-09-16','2026-09-17' FROM run
  RETURNING item_id
),

-- §1 결과 XML 154~201행: ADM_SECT_CD=27170→12830으로 치환, 나머지 값은 예시 그대로.
ins_parcel AS (
  INSERT INTO kras.parcel(pnu,org_cd,adm_sect_cd,land_loc_cd,ledg_gbn,bobn,bubn,source_item_id)
  SELECT '1283010200199999999','12830','12830','10200','1','9999','9999',item_id
  FROM item_land_info
  RETURNING pnu
),
ins_land_register AS (
  INSERT INTO kras.land_register(
    pnu,jimok,jimok_nm,parea,grd,land_mov_rsn_cd,land_mov_rsn_cd_nm,
    ledg_cntrst_cnf_gbn,biz_act_ntc_gbn,map_gbn,land_last_hist_odrno,own_rgt_last_hist_odrno,
    scale,scale_nm,doho,jiga_base_mon,pann_jiga,
    last_jibn,last_bu,lastbobn,lastbubn,land_mov_chrg_man_id,own_rgt_chg_chrg_man_id,
    source_item_id
  )
  SELECT
    pnu,'08','대',152,'227','50','대구광역시서구에서행정구역명칭변경',
    '1','0','도해','02','0005',
    '12','1:1200','009','2013-01',788000,
    '34430000','40','8888','0','22006900','62121300',
    item_land_info.item_id
  FROM ins_parcel, item_land_info
  RETURNING pnu
),
-- §1 예시는 소유내역 필드가 전부 빈 값(마스킹)이라, §2 인물(홍길동)을 참고해 합성한 값이다.
-- 등록번호는 마스킹 원칙에 따라 채우지 않는다.
ins_land_owner AS (
  INSERT INTO kras.land_owner(
    pnu,owner_nm,own_gbn,own_gbn_nm,shr_cnt,owner_addr,
    own_rgt_chg_rsn_cd,own_rgt_chg_rsn_cd_nm,owndymd,availability,source_item_id
  )
  SELECT
    ins_parcel.pnu,'홍길동','01','개인',1,'대구광역시 서구 원대동',
    '03','소유권이전',DATE '1979-07-25','PROVIDED',item_land_info.item_id
  FROM ins_parcel, item_land_info
  RETURNING pnu
),

-- §2 결과 XML 248~293행: 두 공유인(홍길동 2/14, 이순신 1/14) 그대로.
ins_land_share AS (
  INSERT INTO kras.land_share(
    pnu,shr_seqno,own_rgt_chg_rsn_cd,own_rgt_chg_rsn_nm,own_rgt_chg_ymd,
    owner_nm,owner_addr,own_rgt_jibun,own_gbn,own_gbn_nm,source_item_id
  )
  SELECT v.pnu,v.shr_seqno,v.rsn_cd,v.rsn_nm,v.chg_ymd,v.owner_nm,v.owner_addr,v.jibun,v.gbn,v.gbn_nm,item_shr.item_id
  FROM item_shr, (VALUES
    ('1283010200199999999'::varchar(19),'000001','03','소유권이전',DATE '1979-07-25','홍길동','138','2/14','01','개인'),
    ('1283010200199999999'::varchar(19),'000002','03','소유권이전',DATE '1979-07-25','이순신','중구 서성로1가 65','1/14','01','개인')
  ) AS v(pnu,shr_seqno,rsn_cd,rsn_nm,chg_ymd,owner_nm,owner_addr,jibun,gbn,gbn_nm)
  RETURNING share_id
),

-- §7 결과 XML 632~668행: 분할 이동 이력 1건 + 관련지번 999-9.
ins_land_movement_history AS (
  INSERT INTO kras.land_movement_history(
    pnu,land_mov_hist_odrno,land_hist_odrno,jimok,jimok_nm,land_mov_rsn_cd,land_mov_rsn_cd_nm,
    scale,scale_nm,doho,land_mov_chrg_man_id,dymd,land_mov_del_ymd,parea,shr_cnt,source_item_id
  )
  SELECT
    ins_parcel.pnu,'1882697','01','08','대','20','분할되어 본번에 -4내지 -10부함',
    '12','1:1200','008','00000000',DATE '1957-05-18',DATE '1995-01-01',10,0,item_mov.item_id
  FROM ins_parcel, item_mov
  RETURNING history_id
),
ins_land_movement_relation AS (
  INSERT INTO kras.land_movement_relation(history_id,relation_no,jibun,source_item_id)
  SELECT ins_land_movement_history.history_id,1,'999-9',item_mov.item_id
  FROM ins_land_movement_history, item_mov
  RETURNING history_id
)

SELECT 'dummy data inserted for pnu 1283010200199999999' AS status;

COMMIT;
