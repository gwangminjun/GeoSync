-- kras 스키마 전체 업무 테이블(§4.1~4.7, 39개) + 공간 게시 투영 2개(cadastral_feature/usezone_feature)
-- 더미데이터. docs/database/kras-schema-dummy-data.sql(§1·§2·§7 HWP 예시값 기반, 7개 테이블)과는
-- 별개 스크립트다 — 두 스크립트를 같이 실행할 경우 PNU가 겹치지 않도록 여기서는
-- bobn/bubn을 9998로 다르게 잡았다.
--
-- 제외 대상: stage_* 39개(검증 전 임시 데이터라 표시할 "데이터"가 아님), api_*/api_contract 등
-- 8개(원문 XML 게시 캐시), 동기화 제어 12개(sync_run 등 파이프라인 내부 상태) — 이 셋은
-- "연계된 데이터 표출" 대상이 아니라는 원칙(이전 대화)을 그대로 따른다. 다만 업무 테이블을
-- 가드 트리거(guard_business_row 등) 없이 못 넣으므로 sync_run/sync_item/sync_file/sync_record는
-- 딱 필요한 만큼만(전제조건 충족용으로) 같이 만든다.
--
-- 값 자체는 실제 KRAS 응답이 아니라 타입만 맞춘 표시용 더미다(kras-schema-dummy-data.sql처럼
-- kras.md 원문 예시를 따르지 않음) — 화면 표출 테스트가 목적이라 정확한 업무 값보다
-- "필드가 다 채워진 모양"을 우선했다.
--
-- 개발/임시 DB 전용. org_cd=12830(application.yml sync.org-code 기본값)로 고정.
BEGIN;

WITH
run AS (
  INSERT INTO kras.sync_run(org_cd,job_kind,period_start,period_end_exclusive)
  VALUES ('12830','MANUAL','2026-09-18','2026-09-19')
  RETURNING run_id
),
items AS (
  INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive)
  SELECT run.run_id,'12830',d.code,'ALL','2026-09-18','2026-09-19'
  FROM run, (VALUES
    ('land_basic_file'),('land_info'),('land_bldg_check'),
    ('shr_ymb'),('collective_building'),('collective_unit'),('land_right'),
    ('unit_ownership_history'),('land_mov_hist'),('own_rgt_hist'),('land_change'),
    ('integrated_building'),('building_image'),('bldg_dong_info'),('bldg_ledg_gen_hds_info'),
    ('bldg_hds_info'),('cbldg_hds_info'),('bldg_ho_info'),('cbldg_dfhs_info'),
    ('land_jiga'),('house_info'),('fin_dec_jiga'),('read_dec_jiga'),
    ('land_attr'),('land_use_plan_attr'),('use_zone'),('land_use_plan_info')
  ) AS d(code)
  RETURNING item_id, dataset_code
),
item_cadastral AS (
  INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive)
  SELECT run_id,'12830','cadastral_file','LAYER:LP_PA_CBND','2026-09-18','2026-09-19' FROM run
  RETURNING item_id
),
item_usezone AS (
  INSERT INTO kras.sync_item(run_id,org_cd,dataset_code,scope_key,window_start,window_end_exclusive)
  SELECT run_id,'12830','usezone_file','LAYER:UQ112','2026-09-18','2026-09-19' FROM run
  RETURNING item_id
),

-- sync_file (건물통합도면, 토지이용계획 지도) — building_image/land_use_plan_asset의 FK 전제조건
file_image AS (
  INSERT INTO kras.sync_file(item_id,file_type,storage_uri,sha256,byte_size,received_at)
  SELECT item_id,'IMAGE','file://dummy/bldg.png',repeat('a',64),1024,now()
  FROM items WHERE dataset_code='integrated_building'
  RETURNING file_id
),
file_map AS (
  INSERT INTO kras.sync_file(item_id,file_type,storage_uri,sha256,byte_size,received_at)
  SELECT item_id,'IMAGE','file://dummy/map.png',repeat('b',64),2048,now()
  FROM items WHERE dataset_code='land_use_plan_info'
  RETURNING file_id
),

-- ============================================================ §4.1 필지·토지대장 ============
ins_parcel AS (
  INSERT INTO kras.parcel(pnu,org_cd,adm_sect_cd,land_loc_cd,ledg_gbn,bobn,bubn,source_item_id)
  SELECT '1283010200199989998','12830','12830','10200','1','9998','9998',item_id
  FROM items WHERE dataset_code='land_info'
  RETURNING pnu
),
ins_land_basic AS (
  INSERT INTO kras.land_basic(pnu,jimok,parea,own_gbn,source_item_id)
  SELECT ins_parcel.pnu,'08',100,'02', items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_basic_file'
  RETURNING pnu
),
ins_land_register AS (
  INSERT INTO kras.land_register(pnu,jimok,jimok_nm,parea,grd,land_mov_rsn_cd,land_mov_rsn_cd_nm,
    ledg_cntrst_cnf_gbn,biz_act_ntc_gbn,map_gbn,scale,scale_nm,doho,jiga_base_mon,pann_jiga,source_item_id)
  SELECT ins_parcel.pnu,'08','대',100,'150','20','분할','1','0','도해','12','1:1200','001','2026-01',1000000, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_info'
  RETURNING pnu
),
ins_land_owner AS (
  INSERT INTO kras.land_owner(pnu,owner_nm,own_gbn,own_gbn_nm,shr_cnt,owner_addr,own_rgt_chg_rsn_cd,
    own_rgt_chg_rsn_cd_nm,owndymd,availability,source_item_id)
  SELECT ins_parcel.pnu,'더미소유자','01','개인',1,'더미주소','03','소유권이전', DATE '2020-01-01','PROVIDED', items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_info'
  RETURNING pnu
),
ins_land_presence AS (
  INSERT INTO kras.land_presence(pnu,real_gbn,sect_loc_cd,adm_sect_nm,sect_loc_nm,map_gbn,source_item_id)
  SELECT ins_parcel.pnu,'토지(건물)','1283010200','더미구','더미동','1', items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_bldg_check'
  RETURNING pnu
),
ins_land_price AS (
  INSERT INTO kras.land_price(pnu,base_month,pann_jiga,source_item_id)
  SELECT ins_parcel.pnu, DATE '2026-01-01', 1000000, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_info'
  RETURNING pnu
),
ins_land_share AS (
  INSERT INTO kras.land_share(pnu,shr_seqno,own_rgt_chg_rsn_cd,own_rgt_chg_rsn_nm,own_rgt_chg_ymd,
    owner_nm,owner_addr,own_rgt_jibun,own_gbn,own_gbn_nm,source_item_id)
  SELECT ins_parcel.pnu,'000001','03','소유권이전', DATE '2020-01-01','더미공유자','더미주소','1/2','01','개인', items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='shr_ymb'
  RETURNING share_id
),

-- ============================================================ §4.2 집합건물·대지권 ==========
ins_collective_building AS (
  INSERT INTO kras.collective_building(pnu,org_cd,cbldg_seqno,cbldg_nm,source_item_id)
  SELECT ins_parcel.pnu,'12830','0001','더미공동주택', items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='collective_building'
  RETURNING collective_building_id
),
ins_collective_unit AS (
  INSERT INTO kras.collective_unit(collective_building_id,dong,flr,ho,sil,cbldg_nm,shr_cnt,source_item_id)
  SELECT ins_collective_building.collective_building_id,'101','1','101','','더미공동주택',1, items.item_id
  FROM ins_collective_building, items WHERE items.dataset_code='collective_unit'
  RETURNING unit_id
),
ins_land_right AS (
  INSERT INTO kras.land_right(unit_id,pnu,land_rgt_jibun_rate,shr_cnt,reljibn,closure_gbn,source_item_id)
  SELECT ins_collective_unit.unit_id, ins_parcel.pnu,'10/100',1,'9998-9998','0', items.item_id
  FROM ins_collective_unit, ins_parcel, items WHERE items.dataset_code='land_right'
  RETURNING land_right_id
),
ins_unit_ownership_history AS (
  INSERT INTO kras.unit_ownership_history(unit_id,source_service,own_rgt_hist_odrno,own_rgt_chg_rsn_cd,
    own_rgt_chg_rsn_nm,owner_nm,own_gbn,own_gbn_nm,own_rgt_chg_ymd,source_item_id)
  SELECT ins_collective_unit.unit_id,'6','0001','03','소유권이전','더미소유자','01','개인', DATE '2026-01-01', items.item_id
  FROM ins_collective_unit, items WHERE items.dataset_code='unit_ownership_history'
  RETURNING history_id
),

-- ============================================================ §4.3~4.4 연혁·변동이벤트 ======
ins_land_movement_history AS (
  INSERT INTO kras.land_movement_history(pnu,land_mov_hist_odrno,land_hist_odrno,jimok,jimok_nm,
    land_mov_rsn_cd,land_mov_rsn_cd_nm,scale,scale_nm,doho,land_mov_chrg_man_id,dymd,parea,shr_cnt,source_item_id)
  SELECT ins_parcel.pnu,'9999901','01','08','대','20','분할','12','1:1200','008','00000000',
    DATE '2020-01-01',100,0, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_mov_hist'
  RETURNING history_id
),
ins_land_movement_relation AS (
  INSERT INTO kras.land_movement_relation(history_id,relation_no,jibun,source_item_id)
  SELECT ins_land_movement_history.history_id,1,'9998-1', items.item_id
  FROM ins_land_movement_history, items WHERE items.dataset_code='land_mov_hist'
  RETURNING history_id
),
ins_land_ownership_history AS (
  INSERT INTO kras.land_ownership_history(pnu,own_rgt_chg_hist_odrno,dodrno,own_rgt_chg_rsn_cd,
    own_rgt_chg_rsn_cd_nm,owner_nm,own_gbn,own_gbn_nm,dymd,shr_cnt,source_item_id)
  SELECT ins_parcel.pnu,'0001','001','03','소유권이전','더미소유자','01','개인', DATE '2020-01-01',0, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='own_rgt_hist'
  RETURNING history_id
),
rec_change AS (
  INSERT INTO kras.sync_record(item_id,record_no,payload,payload_hash,parser_version,validation_status)
  SELECT items.item_id,1,'{}'::jsonb, encode(sha256(convert_to(('{}'::jsonb)::text,'UTF8')),'hex'),'1','VALID'
  FROM items WHERE items.dataset_code='land_change'
  RETURNING item_id, record_no
),
ins_land_change_event AS (
  INSERT INTO kras.land_change_event(org_cd,land_mov_no,land_mov_nm,land_mov_item,
    bf_land_loc_cd,bf_ledg_gbn,bf_bobn,bf_bubn,bf_jimok,bf_parea,
    af_land_loc_cd,af_ledg_gbn,af_bobn,af_bubn,af_jimok,af_parea,
    before_pnu,land_mov_rsn_cd,land_mov_rsn_nm,adj_ymd,hndl_ymd,
    source_item_id,record_no,payload,payload_hash)
  SELECT '12830','99990001','분할(더미)','20',
    '10200','1','9998','9998','08',100,
    '10200','1','9998','9999','08',50,
    ins_parcel.pnu,'20','분할', DATE '2026-01-01', DATE '2026-01-01',
    rec_change.item_id, rec_change.record_no, '{}'::jsonb,
    encode(sha256(convert_to(('{}'::jsonb)::text,'UTF8')),'hex')
  FROM rec_change, ins_parcel
  RETURNING event_id
),

-- ============================================================ §4.5 통합건물·이미지 ==========
ins_integrated_building AS (
  INSERT INTO kras.integrated_building(org_cd,pnu,land_loc_nm,jibn,ufid,bldg_nm,dong,bldg_gbn_no,
    larea,barea,garea,blr,fsi,hgt,uflr,bflr,use_aprv_ymd,source_item_id)
  SELECT '12830',ins_parcel.pnu,'더미동','9998-9998','DUMMYUFID0001','더미빌딩','101동','99999',
    1000,500,2000,50,150,30,10,1, DATE '2020-01-01', items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='integrated_building'
  RETURNING building_id
),
ins_building_parcel AS (
  INSERT INTO kras.building_parcel(building_id,pnu,relation_type,rel_jibun,relation_no,source_item_id)
  SELECT ins_integrated_building.building_id, ins_parcel.pnu,'MAIN','9998-9998',1, items.item_id
  FROM ins_integrated_building, ins_parcel, items WHERE items.dataset_code='integrated_building'
  RETURNING relation_id
),
ins_building_image AS (
  INSERT INTO kras.building_image(pnu,file_id,width,height,scale,request_key,mime_type,source_item_id)
  SELECT ins_parcel.pnu, file_image.file_id,800,600,'1:500',
    encode(sha256(convert_to('dummy-image-req','UTF8')),'hex'),'image/png', items.item_id
  FROM ins_parcel, file_image, items WHERE items.dataset_code='building_image'
  RETURNING image_id
),

-- ============================================================ §4.6 건축물대장 ================
ins_building_register AS (
  INSERT INTO kras.building_register(org_cd,pnu,bldg_gbn_no,bldg_kind_cd,bldg_kind_nm,bldg_nm,dong_nm,bmap_yn,garea,source_item_id)
  SELECT '12830',ins_parcel.pnu,'99999','1','일반','더미빌딩','101동','Y',2000, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='bldg_dong_info'
  RETURNING register_id
),
ins_building_summary AS (
  INSERT INTO kras.building_summary(pnu,register_id,bldg_gbn_no,bldg_nm,main_use_nm,lega_yn,vio_bldg_yn,
    larea,barea,garea,blr,fsi,land_cnt,tot_main_bldg_cnt,use_aprv_ymd,source_item_id)
  SELECT ins_parcel.pnu, ins_building_register.register_id,'99999','더미빌딩','공동주택','Y','N',
    1000,500,2000,50,150,1,1, DATE '2020-01-01', items.item_id
  FROM ins_parcel, ins_building_register, items WHERE items.dataset_code='bldg_ledg_gen_hds_info'
  RETURNING summary_id
),
ins_building_title AS (
  INSERT INTO kras.building_title(register_id,source_service,bldg_gbn_no,bldg_kind_cd,bldg_nm,dong,
    main_use_nm,stru_nm,roof_nm,lega_yn,vio_bldg_yn,larea,barea,garea,land_cnt,fmly_cnt,hehd_cnt,ho_cnt,source_item_id)
  SELECT ins_building_register.register_id,'014','99999','1','더미빌딩','101동',
    '공동주택','철근콘크리트','평지붕','Y','N',1000,500,2000,1,10,10,10, items.item_id
  FROM ins_building_register, items WHERE items.dataset_code='bldg_hds_info'
  RETURNING title_id
),
ins_building_floor AS (
  INSERT INTO kras.building_floor(title_id,flr_gbn_cd,flr,main_use_nm,stru_nm,btm_area,source_item_id)
  SELECT ins_building_title.title_id,'1','1','공동주택','철근콘크리트',200, items.item_id
  FROM ins_building_title, items WHERE items.dataset_code='bldg_hds_info'
  RETURNING floor_id
),
ins_building_title_owner AS (
  INSERT INTO kras.building_title_owner(title_id,owner_nm,own_gbn_nm,jibun_desc,detl_addr,last_yn,adj_ymd,source_item_id)
  SELECT ins_building_title.title_id,'더미소유자','개인','9998-9998','더미주소','Y', DATE '2020-01-01', items.item_id
  FROM ins_building_title, items WHERE items.dataset_code='bldg_hds_info'
  RETURNING owner_id
),
ins_building_title_change AS (
  INSERT INTO kras.building_title_change(title_id,chg_rsn_nm,chg_cntn,chg_ymd,source_item_id)
  SELECT ins_building_title.title_id,'신축','신규 건축', DATE '2020-01-01', items.item_id
  FROM ins_building_title, items WHERE items.dataset_code='bldg_hds_info'
  RETURNING change_id
),
ins_building_unit AS (
  INSERT INTO kras.building_unit(register_id,pnu,bldg_gbn_no,parent_bno,bldg_kind_cd,dong_nm,flr_ho_nm,bmap_yn,garea,source_item_id)
  SELECT ins_building_register.register_id, ins_parcel.pnu,'99999','99999','1','101동','1-101','Y',80, items.item_id
  FROM ins_building_register, ins_parcel, items WHERE items.dataset_code='bldg_ho_info'
  RETURNING building_unit_id
),
ins_building_exclusive AS (
  INSERT INTO kras.building_exclusive(building_unit_id,pnu,request_key,bldg_gbn_no,upper_bldg_no,source_item_id)
  SELECT ins_building_unit.building_unit_id, ins_parcel.pnu,
    encode(sha256(convert_to('dummy-exclusive-req','UTF8')),'hex'),'99999','99999', items.item_id
  FROM ins_building_unit, ins_parcel, items WHERE items.dataset_code='cbldg_dfhs_info'
  RETURNING exclusive_id
),
ins_building_exclusive_area AS (
  INSERT INTO kras.building_exclusive_area(exclusive_id,expos_comm_gbn_nm,flr,flr_no,main_sub_gbn_nm,main_use_nm,stru_nm,source_item_id)
  SELECT ins_building_exclusive.exclusive_id,'전유','1','101','주','공동주택','철근콘크리트', items.item_id
  FROM ins_building_exclusive, items WHERE items.dataset_code='cbldg_dfhs_info'
  RETURNING area_id
),
ins_building_exclusive_owner AS (
  INSERT INTO kras.building_exclusive_owner(exclusive_id,owner_nm,own_gbn_nm,jibun_desc,detl_addr,chg_rsn_nm,last_yn,chg_ymd,source_item_id)
  SELECT ins_building_exclusive.exclusive_id,'더미소유자','개인','9998-9998','더미주소','소유권이전','Y', DATE '2020-01-01', items.item_id
  FROM ins_building_exclusive, items WHERE items.dataset_code='cbldg_dfhs_info'
  RETURNING owner_id
),
ins_building_exclusive_price AS (
  INSERT INTO kras.building_exclusive_price(exclusive_id,base_ymd,house_prc,source_item_id)
  SELECT ins_building_exclusive.exclusive_id, DATE '2026-01-01',500000000, items.item_id
  FROM ins_building_exclusive, items WHERE items.dataset_code='cbldg_dfhs_info'
  RETURNING price_id
),

-- ============================================================ §4.7 KOREPS·토지이용계획 ======
ins_koreps_land_price AS (
  INSERT INTO kras.koreps_land_price(pnu,base_year,base_mon,jibun,jiga_jibn,pann_jiga,pann_ymd,source_item_id)
  SELECT ins_parcel.pnu,'2026','01','9998-9998','9998-9998',1000000, DATE '2026-01-01', items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_jiga'
  RETURNING price_id
),
ins_house_price AS (
  INSERT INTO kras.house_price(pnu,base_year,stdmt,dong_no,land_area,land_calc_area,bldg_area,bldg_calc_area,indi_house_prc,source_item_id)
  SELECT ins_parcel.pnu,'2026','1','101',100,90,80,70,500000000, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='house_info'
  RETURNING price_id
),
ins_final_land_price AS (
  INSERT INTO kras.final_land_price(pnu,base_year,stdmt,jiga,source_item_id)
  SELECT ins_parcel.pnu,'2026','1',1000000, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='fin_dec_jiga'
  RETURNING price_id
),
ins_read_land_price AS (
  INSERT INTO kras.read_land_price(pnu,cald_stdmt,seqno,jimok,land_loc_addr,decn_jiga,read_jiga,py_jiga,parea,source_item_id)
  SELECT ins_parcel.pnu,'2026-01','1','08','더미주소',1000000,1000000,3300000,100, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='read_dec_jiga'
  RETURNING price_id
),
ins_land_attribute AS (
  INSERT INTO kras.land_attribute(pnu,land_seqno,sgg_cd,land_loc_cd,ledg_gbn,bobn,bubn,land_loc_nm,jimok,jimok_nm,
    own_gbn,land_use,geo_form,geo_hl,road_side,spfc1,pann_year,stdmt,parea,spfc1_area,calc_jiga,py_jiga,source_item_id)
  SELECT ins_parcel.pnu,'1','12830','10200','1','9998','9998','더미동','08','대',
    '02','주거','평지','저','광대로','제2종일반주거지역','2026','1',100,100,1000000,3300000, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_attr'
  RETURNING attribute_id
),
ins_land_use_attribute AS (
  INSERT INTO kras.land_use_attribute(pnu,ctype,divno,gubun,lawnm,seq,ucode,uname,unm,source_item_id)
  SELECT ins_parcel.pnu,'1','1','용도지역','국토의 계획 및 이용에 관한 법률','1','UQ112','제2종일반주거지역','2종일주', items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_use_plan_attr'
  RETURNING attribute_id
),
ins_land_use_zone AS (
  INSERT INTO kras.land_use_zone(pnu,use_zone_zone_cd,use_zone_zone_cd_nm,cflt_yn,source_item_id)
  SELECT ins_parcel.pnu,'UQ112','제2종일반주거지역','N', items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='use_zone'
  RETURNING zone_id
),
ins_land_use_plan AS (
  INSERT INTO kras.land_use_plan(pnu,request_key,land_loc_nm,jibn,jimok,jimok_nm,jiga_ym,scale,parea,jiga,source_item_id)
  SELECT ins_parcel.pnu, encode(sha256(convert_to('dummy-plan-req','UTF8')),'hex'),
    '더미동','9998-9998','08','대','2026-01','1:1200',100,1000000, items.item_id
  FROM ins_parcel, items WHERE items.dataset_code='land_use_plan_info'
  RETURNING plan_id
),
ins_land_use_restriction AS (
  INSERT INTO kras.land_use_restriction(plan_id,seqno,ucode,law_full_cd,law_level,law_contents,uselaw_a,uselaw_b,use_restrict,source_item_id)
  SELECT ins_land_use_plan.plan_id,'1','UQ112','국토의 계획 및 이용에 관한 법률','법률','제2종일반주거지역 안에서의 건축제한',
    '4층 이하','','건축제한', items.item_id
  FROM ins_land_use_plan, items WHERE items.dataset_code='land_use_plan_info'
  RETURNING restriction_id
),
ins_land_use_plan_asset AS (
  INSERT INTO kras.land_use_plan_asset(plan_id,file_id,asset_kind,mime_type,scale,width,height,legend_width,legend_height,source_item_id)
  SELECT ins_land_use_plan.plan_id, file_map.file_id,'MAP','image/png','1:1200',600,400,150,100, items.item_id
  FROM ins_land_use_plan, file_map, items WHERE items.dataset_code='land_use_plan_info'
  RETURNING asset_id
),

-- ============================================================ 공간 게시 투영 ==================
ins_spatial_layer_cad AS (
  INSERT INTO kras.spatial_layer(layer_code,dataset_code,layer_name,source_epsg,target_epsg,geometry_type)
  VALUES ('LP_PA_CBND','cadastral_file','연속지적',5186,5186,'MultiPolygon')
  ON CONFLICT (layer_code) DO UPDATE SET layer_name=EXCLUDED.layer_name
  RETURNING layer_code
),
ins_spatial_layer_use AS (
  INSERT INTO kras.spatial_layer(layer_code,dataset_code,layer_name,source_epsg,target_epsg,geometry_type)
  VALUES ('UQ112','usezone_file','제2종일반주거지역',5186,5186,'MultiPolygon')
  ON CONFLICT (layer_code) DO UPDATE SET layer_name=EXCLUDED.layer_name
  RETURNING layer_code
),
ins_spatial_feature_cad AS (
  INSERT INTO kras.spatial_feature(item_id,feature_no,org_cd,layer_code,source_feature_id,pnu,geom)
  SELECT item_cadastral.item_id,1,'12830',ins_spatial_layer_cad.layer_code,'DUMMY-1',ins_parcel.pnu,
    ST_GeomFromText('MULTIPOLYGON(((0 0,0 1,1 1,1 0,0 0)))',5186)
  FROM item_cadastral, ins_spatial_layer_cad, ins_parcel
  RETURNING item_id, feature_no, org_cd, layer_code, pnu, geom
),
ins_cadastral_feature AS (
  INSERT INTO kras.cadastral_feature(item_id,feature_no,org_cd,layer_code,source_uid,pnu,jibun,bchk,geom)
  SELECT item_id,feature_no,org_cd,layer_code,'DUMMY-1',pnu,'9998-9998','0',geom
  FROM ins_spatial_feature_cad
  RETURNING feature_id
),
ins_spatial_feature_use AS (
  INSERT INTO kras.spatial_feature(item_id,feature_no,org_cd,layer_code,source_feature_id,pnu,geom)
  SELECT item_usezone.item_id,1,'12830',ins_spatial_layer_use.layer_code,'DUMMY-2',ins_parcel.pnu,
    ST_GeomFromText('MULTIPOLYGON(((0 0,0 2,2 2,2 0,0 0)))',5186)
  FROM item_usezone, ins_spatial_layer_use, ins_parcel
  RETURNING item_id, feature_no, org_cd, layer_code, pnu, geom
),
ins_usezone_feature AS (
  INSERT INTO kras.usezone_feature(item_id,feature_no,org_cd,layer_code,source_uid,pnu,mnum,remark,alias,theme_code,theme_name,geom)
  SELECT item_id,feature_no,org_cd,layer_code,'DUMMY-2',pnu,'1283010200-UQ112-0001','','제2종일반주거지역','UQ112','제2종일반주거지역',geom
  FROM ins_spatial_feature_use
  RETURNING feature_id
)

SELECT 'dummy data inserted for all 41 domain tables (pnu=1283010200199989998)' AS status;

COMMIT;
