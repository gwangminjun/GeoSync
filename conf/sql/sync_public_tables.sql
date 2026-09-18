CREATE TABLE public.anvm_jiga (
	land_cd varchar(19) NULL,
	base_year varchar(4) NULL,
	jiga numeric(10) NULL,
	base_mon varchar(2) NULL,
	pyo_yn varchar(1) NULL,
	org_cd varchar(10) NULL
);


CREATE TABLE public.land_frst_ledg (
	adm_sec_cd varchar(5) NULL,
	land_loc_cd varchar(5) NULL,
	ledg_gbn varchar(1) NULL,
	bobn varchar(4) NULL,
	bubn varchar(4) NULL,
	jimok varchar(2) NULL,
	parea numeric(13, 2) NULL,
	owngbn varchar(2) NULL,
	org_cd varchar(5) NULL
);


CREATE TABLE public.lp_pa_cbnd (
	uid int4 NULL,
	geom public.geometry(multipolygon, 5186) NULL,
	jibun varchar(100) NULL,
	bchk varchar(2) NULL,
	pnu varchar(19) NULL
);



CREATE TABLE public.lt_c_uzone (
	mnum varchar(33) NULL,
	remark varchar(100) NULL,
	alias varchar(100) NULL,
	layer_code varchar(5) NULL,
	theme_code varchar(6) NULL,
	theme_name varchar(100) NULL,
	org_cd varchar(10) NULL,
	uid int4 NULL,
	geom public.geometry(multipolygon, 5186) NULL
);

