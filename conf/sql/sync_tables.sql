CREATE TABLE ods.anvm_jiga
(
  land_cd character varying(19),
  base_year character varying(4),
  jiga numeric(10,0),
  base_mon character varying(2),
  pyo_yn character varying(1),
  org_cd character varying(10)
);

CREATE TABLE ods.land_frst_ledg
(
  adm_sec_cd character varying(5),
  land_loc_cd character varying(5),
  ledg_gbn character varying(1),
  bobn character varying(4),
  bubn character varying(4),
  jimok character varying(2),
  parea numeric(13,2),
  owngbn character varying(2),
  org_cd character varying(5)
);
CREATE TABLE ods.lp_pa_cbnd
(
  pnu character varying(19),
  jibun character varying(15),
  bchk character varying(2),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(MultiPolygon,5176),
  CONSTRAINT lp_pa_cbnd_pkey PRIMARY KEY (_gid)
);

CREATE TABLE ods.lt_c_uzone
(
  mnum character varying(33),
  remark character varying(100),
  alias character varying(100),
  ulyr character varying(5),
  ucode character varying(6),
  uname character varying(100),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(MultiPolygon,5176),
  CONSTRAINT lt_c_uzone_pkey PRIMARY KEY (_gid)
);

CREATE TABLE ods.mt_clct_log
(
  org_cd character varying(10),
  sys_cd character varying(10),
  tbl_nm character varying(50),
  tot_cnt numeric(8,0),
  suc_cnt numeric(8,0),
  err_cnt numeric(8,0),
  st_time character(14),
  ed_time character(14),
  err_desc text,
  reg_time character(14),
  log_note text
);

CREATE TABLE ods.mt_link_log
(
  usr_id character varying(30),
  org_cd character varying(10),
  sys_cd character varying(10),
  link_typ character(3),
  work_nm character varying(50),
  rslt_cd character(1),
  st_time character(14),
  ed_time character(14),
  err_desc text,
  reg_time character(14),
  log_note text
);

CREATE TABLE ods.mt_ods_log
(
  _seq serial NOT NULL,
  tbl_nm character varying(50),
  org_cd character varying(10),
  base_year character varying(6),
  data_cnt numeric(12,0),
  base_col character varying(50),
  CONSTRAINT mt_clouddata_log_pk PRIMARY KEY (_seq)
);

CREATE TABLE ods.mt_usezone_cd
(
  use_zone_zone_cd character varying(255),
  law_cls_cd character varying(255),
  rem character varying(255),
  del_ymd character varying(255),
  cre_ymd character varying(255),
  fac_cls character varying(255),
  fac_type character varying(255),
  cont_tmap_layer_no character varying(255),
  use_zone_zone_cd_nm character varying(255),
  use_zone_zone_cls_gbn character varying(255),
  cflt_expr_yn character varying(255),
  app_law_cd character varying(255),
  rpt_cls character varying(255),
  edt_tmap_layer_no character varying(255)
);
CREATE TABLE ods.tl_spbd_buld
(
  sig_cd character varying(5), 
  bul_man_no numeric(7,0), 
  rn_cd character varying(7),
  rds_man_no numeric(12,0),
  bsi_int_sn numeric(10,0),
  eqb_man_sn numeric(10,0),
  buld_se_cd character varying(1),
  buld_mnnm numeric(5,0),
  buld_slno numeric(5,0),
  buld_nm character varying(200),
  bul_eng_nm character varying(200),
  buld_nm_dc character varying(100),
  buld_sttus character varying(40),
  bdtyp_cd character varying(5),
  bul_dpn_se character varying(1),
  gro_flo_co numeric(3,0),
  und_flo_co numeric(3,0),
  zip character varying(7),
  pos_bul_yn character varying(1),
  pos_bul_nm character varying(40),
  reg_pub_nm character varying(20),
  emd_cd character varying(3),
  li_cd character varying(2),
  mntn_yn character varying(1),
  lnbr_mnnm numeric(4,0),
  lnbr_slno numeric(4,0),
  compet_de character varying(8),
  ntfc_de character varying(8),
  mvm_res_cd character varying(10),
  mvmn_resn character varying(254),
  mvmn_de character varying(8),
  opert_de character varying(14),
  ima_fil_sn numeric(11,0),
  bsi_zon_no character varying(5),
  nti_trg_yn character varying(1),
  input_mthd character varying(1),
  bd_mgt_sn character varying(25),
  issu_yn character varying(1),  
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(MultiPolygon,5176),
  CONSTRAINT tl_spbd_buld_pkey PRIMARY KEY (_gid)
);
CREATE TABLE ods.tl_spbd_entrc
(
  sig_cd character varying(5),
  ent_man_no numeric(10,0),
  eqb_man_sn numeric(10,0),
  bul_man_no numeric(7,0),
  entrc_se character varying(2),
  opert_de character varying(14),
  nmt_ins_yn character varying(1),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(Point,5176),
  CONSTRAINT tl_spbd_entrc_pkey PRIMARY KEY (_gid)
);
CREATE TABLE ods.tl_spbd_eqb
(
  sig_cd character varying(5),
  eqb_man_sn numeric(10,0),
  rn_cd character varying(7),
  rds_man_no numeric(12,0),
  bsi_int_sn numeric(10,0),
  eqb_nm character varying(40),
  eqb_eng_nm character varying(80),
  opert_de character varying(14),
  input_mthd character varying(1),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(MultiPolygon,5176),
  CONSTRAINT tl_spbd_eqb_pkey PRIMARY KEY (_gid)
);
CREATE TABLE ods.tl_spot_cntc
(
  sig_cd character varying(5),
  ent_man_no numeric(10,0),
  rds_man_no numeric(12,0),
  bsi_int_sn numeric(10,0),
  cnt_drc_ln character varying(1),
  cnt_dst_ln character varying(10),
  opert_de character varying(14),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(MultiLineString,5176),
  CONSTRAINT tl_spot_cntc_pkey PRIMARY KEY (_gid)
);
CREATE TABLE ods.tl_spot_fcltylc
(
  sig_cd character varying(5),
  fclty_sn numeric(12,0),
  pil_man_no character varying(20),
  ins_fcl_se character varying(9),
  ima_fil_sn numeric(11,0),
  symbol_se character varying(10),
  bsi_zon_no numeric(5,0),
  rec_chc_de character varying(8),
  chc_stt_cd character varying(10),
  opert_de character varying(14),
  crsrd_sn numeric(12,0),
  rds_man_no numeric(12,0),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(Point,5176),
  CONSTRAINT tl_spot_fcltylc_pkey PRIMARY KEY (_gid)
);

CREATE TABLE ods.tl_sprd_crsrd
(
  sig_cd character varying(5),
  crsrd_sn numeric(12,0),
  kor_crsrd character varying(100),
  eng_crsrd character varying(100),
  crsrd_se character varying(1),
  crsrd_ty character varying(1),
  opert_de character varying(14),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(Point,5176),
  CONSTRAINT tl_sprd_crsrd_pkey PRIMARY KEY (_gid)
);

CREATE TABLE ods.tl_sprd_intrvl
(
  sig_cd character varying(5),
  rds_man_no numeric(12,0),
  bsi_int_sn numeric(10,0),
  odd_bsi_mn numeric(5,0),
  odd_bsi_sl numeric(5,0),
  eve_bsi_mn numeric(5,0),
  eve_bsi_sl numeric(5,0),
  mvm_res_cd character varying(10),
  mvmn_resn character varying(254),
  mvmn_de character varying(8),
  opert_de character varying(14),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(MultiLineString,5176),
  CONSTRAINT tl_sprd_intrvl_pkey PRIMARY KEY (_gid)
);

CREATE TABLE ods.tl_sprd_manage
(
  sig_cd character varying(5),
  rds_man_no numeric(12,0),
  rn character varying(80),
  rn_cd character varying(7),
  eng_rn character varying(80),
  ntfc_de character varying(8),
  rn_dlb_de character varying(8),
  roa_man_es character varying(20),
  wdr_rd_cd character varying(10),
  roa_cls_se character varying(2),
  rds_dpn_se character varying(1),
  rbp_cn character varying(80),
  rep_cn character varying(80),
  road_bt numeric(10,3),
  road_lt numeric(10,3),
  bsi_int character varying(5),
  nlr_lcl_no character varying(13),
  alwnc_resn character varying(254),
  alwnc_de character varying(8),
  mvm_res_cd character varying(10),
  mvmn_resn character varying(254),
  mvmn_de character varying(8),
  opert_de character varying(14),
  par_sig_cd character varying(5),
  par_rds_no numeric(12,0),
  input_mthd character varying(1),
  crsrd_cnt numeric(3,0),
  issu_yn character varying(1),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(MultiLineString,5176),
  CONSTRAINT tl_sprd_manage_pkey PRIMARY KEY (_gid)
);

CREATE TABLE ods.tl_sprd_rw
(
  sig_cd character varying(5),
  rw_sn numeric(12,0),
  roa_cls_se character varying(2),
  rds_man_no numeric(12,0),
  opert_de character varying(14),
  org_cd character varying(10),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(MultiPolygon,5176),
  CONSTRAINT tl_sprd_rw_pkey PRIMARY KEY (_gid)
);

CREATE TABLE ods.f_fac_building
(
  ufid character varying(28),
  bld_nm character varying(150),
  dong_nm character varying(150),
  grnd_flr numeric(5,0),
  ugrnd_flr numeric(5,0),
  pnu character varying(19),
  archarea numeric(28,9),
  totalarea numeric(28,9),
  platarea numeric(28,9),
  height numeric(28,9),
  strct_cd character varying(2),
  usability character varying(5),
  bc_rat numeric(28,9),
  vl_rat numeric(28,9),
  bldrgst_pk character varying(28),
  useapr_day character varying(8),
  regist_day character varying(8),
  gb_cd character varying(2),
  viol_bd_yn character varying(1),
  bldg_pnu character varying(19),
  bldg_pnu_yn character varying(1),
  bld_unlice character varying(1),
  bd_mgt_sn character varying(25),
  org_cd character varying(5),
  _gid serial NOT NULL,
  _annox numeric(20,4),
  _annoy numeric(20,4),
  _geometry geometry(MultiPolygon,5176),
  CONSTRAINT f_fac_building_pkey PRIMARY KEY (_gid)
);