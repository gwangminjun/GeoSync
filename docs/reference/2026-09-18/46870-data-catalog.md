# workspace/kras/46870 데이터 카탈로그

> 작성일: 2026-06-23  
> 기관코드: `46870` (완도군)  
> 목적: 기존 geosync2.jar 엔진이 생성한 중간 작업 파일 분류 및 신규 프로젝트 연계 정보

---

## 1. 디렉토리 개요

```
workspace/kras/46870/
├── lsmd_cont_u*.shp/dbf/shx   ← 47개 레이어 × 3파일 = 141개 (KRAS 수신 원본 SHP)
├── ods.lp_pa_cbnd.*           ← 연속지적도 처리 결과
├── ods.land_frst_ledg.*       ← 토지기본정보 처리 결과
├── ods.lt_c_uzone.gmx         ← 용도지역지구 통합 결과
└── ods.f_fac_building.*       ← 건물 처리 결과 (비활성)
```

파일 종류는 크게 두 가지다:

| 종류 | 파일 패턴 | 생성 주체 | 역할 |
|------|-----------|-----------|------|
| **KRAS 수신 원본** | `lsmd_cont_u*.shp` | KRAS estateGateway | 용도지역지구 레이어별 경계 SHP |
| **엔진 중간 산출물** | `ods.*` | geosync2.jar 내부 | PostgreSQL 적재 전 캐시/검증용 |

---

## 2. KRAS 수신 원본: 용도지역지구 SHP (lsmd_cont_u*)

### 개요

- **소스**: KRAS `POST /conn/estateGateway` → `USEZONE:LSMD_CONT_XXXXX` 레이어 목록 일괄 수신
- **좌표계**: EPSG:5174 (수신) → EPSG:5176으로 변환 후 DB 저장
- **적재 테이블**: `ods.lt_c_uzone` (MULTIPOLYGON)
- **저장 컬럼**: `ulyr`(레이어코드 5자), `ucode`(용도코드 6자), `uname`(명칭 100자), `org_cd`(기관코드), `geometry`

### 레이어 목록 (47개)

#### UB — 도시지역 용도지역 (국토의계획및이용에관한법률)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_ub201` | UB201 | 제1종일반주거지역 |
| `lsmd_cont_ub210` | UB210 | 준주거지역 |

#### UD — 도시개발 (도시개발법)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_ud602` | UD602 | 도시개발구역 |

#### UE — 비도시지역 용도지역 (국토의계획및이용에관한법률)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_ue101` | UE101 | 보전관리지역 |
| `lsmd_cont_ue102` | UE102 | 생산관리지역 |
| `lsmd_cont_ue301` | UE301 | 농림지역 |
| `lsmd_cont_ue601` | UE601 | 자연환경보전지역 |

#### UF — 용도지구 / 산지 (국토계획법, 산지관리법)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_uf151` | UF151 | 준보전산지 |
| `lsmd_cont_uf201` | UF201 | 미관지구 |
| `lsmd_cont_uf301` | UF301 | 고도지구 |
| `lsmd_cont_uf401` | UF401 | 방화지구 |
| `lsmd_cont_uf801` | UF801 | 방재지구 |
| `lsmd_cont_uf811` | UF811 | 시가지방재지구 |

#### UG — 개발제한구역 / 도시자연공원 (개발제한구역법, 도시공원법)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_ug101` | UG101 | 개발제한구역 (그린벨트) |
| `lsmd_cont_ug401` | UG401 | 도시자연공원구역 |

#### UH — 주거환경개선 (도시및주거환경정비법)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_uh101` | UH101 | 주거환경개선지구 |

#### UI — 산업단지 (산업입지및개발에관한법률)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_ui101` | UI101 | 산업단지 |
| `lsmd_cont_ui201` | UI201 | 준산업단지 |

#### UJ — 관광 (관광진흥법)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_uj201` | UJ201 | 관광단지 |
| `lsmd_cont_uj301` | UJ301 | 관광특구 |
| `lsmd_cont_uj401` | UJ401 | 관광지 |

#### UL — 토지거래 (부동산거래신고등에관한법률)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_ul201` | UL201 | 토지거래허가구역 |

#### UM — 관리지역 (국토의계획및이용에관한법률)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_um000` | UM000 | 관리지역 (세분 미완료 — 미분류 전체) |
| `lsmd_cont_um101` | UM101 | 보전관리지역 |
| `lsmd_cont_um102` | UM102 | 생산관리지역 |
| `lsmd_cont_um710` | UM710 | 계획관리지역 |
| `lsmd_cont_um711` | UM711 | 계획관리지역 (세부) |
| `lsmd_cont_um910` | UM910 | 관리지역 세분화 미완료 |

> `um000` / `um910`은 법정 세분화 이전의 관리지역 데이터. 실운용에서 UE101/UE102/UM710과 중복될 수 있음.

#### UN — 자연공원 (자연공원법)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_un501` | UN501 | 자연공원구역 |

#### UO — 연안 / 수산 / 재해 (연안관리법, 수산자원관리법, 자연재해대책법)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_uo101` | UO101 | 수산자원보호구역 |
| `lsmd_cont_uo301` | UO301 | 연안관리지역 |
| `lsmd_cont_uo601` | UO601 | 자연재해위험개선지구 |

> 완도군은 해안 도서 지역이므로 UO 레이어 비중이 높다.

#### UP — 문화재 / 역사 (문화재보호법)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_up201` | UP201 | 문화재보호구역 |
| `lsmd_cont_up401` | UP401 | 역사문화미관지구 |

#### UQ — 지구단위계획구역 / 각종 특구 (국토계획법 등)

| 파일 | ulyr | 내용 |
|------|------|------|
| `lsmd_cont_uq111` | UQ111 | 지구단위계획구역 (제1종) |
| `lsmd_cont_uq112` | UQ112 | 지구단위계획구역 (제1종 세부) |
| `lsmd_cont_uq113` | UQ113 | 지구단위계획구역 (제1종 세부) |
| `lsmd_cont_uq114` | UQ114 | 지구단위계획구역 (제1종 세부) |
| `lsmd_cont_uq121` | UQ121 | 지구단위계획구역 (제2종) |
| `lsmd_cont_uq123` | UQ123 | 지구단위계획구역 (제2종 세부) |
| `lsmd_cont_uq126` | UQ126 | 지구단위계획구역 (제2종 세부) |
| `lsmd_cont_uq128` | UQ128 | 지구단위계획구역 (혼합형) |
| `lsmd_cont_uq129` | UQ129 | 지구단위계획구역 (혼합형) |
| `lsmd_cont_uq141` | UQ141 | 특별계획구역 |
| `lsmd_cont_uq161` | UQ161 | 각종 특구·구역 (1) |
| `lsmd_cont_uq162` | UQ162 | 각종 특구·구역 (2) |
| `lsmd_cont_uq163` | UQ163 | 각종 특구·구역 (3) |
| `lsmd_cont_uq164` | UQ164 | 각종 특구·구역 (4) |
| `lsmd_cont_uq165` | UQ165 | 각종 특구·구역 (5) |
| `lsmd_cont_uq166` | UQ166 | 각종 특구·구역 (6) |
| `lsmd_cont_uq167` | UQ167 | 각종 특구·구역 (7) |

---

## 3. 엔진 중간 산출물 (ods.*)

기존 `geosync2.jar`가 KRAS에서 수신한 데이터를 PostgreSQL에 적재하기 전 내부적으로 생성하는 캐시/검증 파일이다. 신규 프로젝트에서는 생성하지 않는다.

### `ods.lp_pa_cbnd.*`

| 항목 | 내용 |
|------|------|
| 형식 | SHP + DBF + GMX |
| 소스 | KRAS `LSMD_CONT_LDREG` (연속지적도) |
| 내용 | 필지 경계 MULTIPOLYGON |
| 주요 컬럼 | `PNU`(19), `JIBUN`(15), `BCHK`(2), geometry |
| 적재 테이블 | `ods.lp_pa_cbnd` |

### `ods.land_frst_ledg.*`

| 항목 | 내용 |
|------|------|
| 형식 | TXT + GMX |
| 소스 | KRAS `ABPM_LAND_FRST_LEDG` (토지기본정보) |
| 내용 | 토지 속성 정보 (도형 없음) |
| 주요 컬럼 | `ADM_SECT_CD`, `LAND_LOC_CD`, `LEDG_GBN`, `BOBN`, `BUBN`, `JIMOK`, `PAREA`, `OWN_GBN` |
| 적재 테이블 | `ods.land_frst_ledg` |

### `ods.lt_c_uzone.gmx`

| 항목 | 내용 |
|------|------|
| 형식 | GMX (geosync 내부 바이너리) |
| 소스 | 위 47개 `lsmd_cont_u*` SHP 파일 통합 |
| 내용 | 용도지역지구 전체 통합 MULTIPOLYGON |
| 주요 컬럼 | `org_cd`(5), `ulyr`(5), `ucode`(6), `uname`(100), geometry |
| 적재 테이블 | `ods.lt_c_uzone` |

### `ods.f_fac_building.*`

| 항목 | 내용 |
|------|------|
| 형식 | SHP + DBF + GMX + **GMX_ERR** |
| 소스 | KRAS `F_FAC_BUILDING` (건물통합) |
| 내용 | 건물 MULTIPOLYGON |
| 주요 컬럼 | `UFID`, `BLD_NM`, `DONG_NM`, geometry |
| 적재 테이블 | `ods.f_fac_building` |
| **상태** | **비활성** — `conf/kras/base-tables.xml`에서 주석 처리됨 |

> `ods.f_fac_building.gmx_err` 파일이 존재한다는 것은 기존 엔진에서 건물 레이어 처리 중 오류가 발생했음을 의미한다. 비활성 처리된 이유로 추정.

---

## 4. GMX 파일 형식 (참고)

`.gmx` / `.gmx_err` 는 기존 엔진(`geosync2.jar`) 고유의 바이너리 포맷이다.

- 헤더에 레이어명, 대상 테이블명, 컬럼 정의 포함
- 공간 데이터는 IEEE 754 배정밀도 좌표 스트림으로 직렬화
- **신규 프로젝트에서는 읽거나 생성하지 않음** — 참조 목적으로만 보존

---

## 5. 신규 프로젝트 (GeoSync) 연계

신규 프로젝트는 이 디렉토리의 파일을 직접 사용하지 않고 KRAS API에서 실시간으로 수신한다.

```
기존 엔진 흐름:
  KRAS API → SHP 파일 저장 (workspace/kras/46870/) → GMX 변환 → PostgreSQL

신규 엔진 흐름 (KrasWorker):
  KRAS API → Java Map<String, Object> → OdsRepository → PostgreSQL (직접)
```

| 기존 파일 | 신규 처리 |
|-----------|-----------|
| `lsmd_cont_u*.shp` | `USEZONE:LSMD_CONT_XXXXX` 레이어 목록 API 조회 후 직접 파싱 |
| `ods.lp_pa_cbnd.*` | `LSMD_CONT_LDREG` API 직접 수신 → `ods.lp_pa_cbnd` |
| `ods.land_frst_ledg.*` | `ABPM_LAND_FRST_LEDG` API 직접 수신 → `ods.land_frst_ledg` |
| `ods.lt_c_uzone.gmx` | 47개 레이어 통합 처리 → `ods.lt_c_uzone` |
| `ods.f_fac_building.*` | 비활성 유지 (base-tables.xml 주석) |

---

## 6. 코드 조회 참고 테이블

용도코드(`ucode`) 전체 정의는 `conf/sql/mt_usezone_cd.sql` 참조.  
테이블 구조: `use_zone_zone_cd`(코드 6자) / `use_zone_zone_cd_nm`(명칭) / `law_cls_cd`(근거법령 코드)

```sql
-- 예시: 특정 코드 조회
SELECT use_zone_zone_cd, use_zone_zone_cd_nm
FROM mt_usezone_cd
WHERE use_zone_zone_cd LIKE 'UM%';
```
