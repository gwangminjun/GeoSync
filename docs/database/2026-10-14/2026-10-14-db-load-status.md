# DB 적재 현황 (2026-10-14 기준)

- 조회 대상: 개발 DB `112.216.98.130:31613 / YG-Space_260714` (읽기 전용 조회, `SET default_transaction_read_only=on`)
- 증분 규칙은 별도 문서: `2026-10-14-kras-incremental-sync-rules.md`

## 1. 한눈에 보기

| 경로 | 데이터를 DB에 쌓나 | 대상 | 실행 | 현재 상태 |
|---|---|---|---|---|
| **기존 연계 — 실시간 중계** (`/conn`, `/svc`, `/api-test/call`) | **안 쌓음.** 요청을 KRAS/KOREPS로 바로 넘기고 응답을 그대로 돌려준다 | 요청 로그만 `ods.conn_request_log` | 외부 요청 시 | 정상. 로그 9건 |
| **기존 연계 — KrasWorker 스케줄 적재** | 쌓음(매번 전체 교체: 기관 행 DELETE 후 INSERT) | `public.lp_pa_cbnd`, `public.lt_c_uzone` | cron `kras.schedule`(개발 설정은 꺼짐 `-`) | 255,441 / 17,658건 |
| **기존 연계 — 전체 TXT 로더** | 쌓음 | `public.land_frst_ledg`, `public.anvm_jiga` | 파일 내려받기 화면/스케줄 | 256,858 / 0건 |
| **차세대 — kras 스키마** (`/kras-db`, `/kras-schedule`) | 쌓음(수집 → stage → 검증 → 업무 테이블 반영) | `kras.*` 업무 테이블 39개 | 화면 수동 + 차세대 스케줄(묶음 2개: 전체 적재·보정 적재, 설정 시). PNU 단위 19종은 대기열 `kras.pnu_work`로 적재(2026-10-15) | 더미 데이터 1~3건씩만, 실제 수집 없음 |

기존 연계는 이번 차세대 작업에서 수정하지 않았다(코드 변경 없음, 2026-10-14 재기동 후 중계 호출로 확인).

## 2. 차세대(kras) — 데이터셋별 적재 대상과 반영 방식

반영 방식 기호:
- **A** 자연키 UPSERT (PNU 단건: stage 1행)
- **A\*** 자연키 UPSERT, 파일 전체 행
- **B** 범위(PNU) 전체 교체
- **B+** 범위 교체 + 자식 테이블
- **C** 신원 매칭 후 UPSERT
- **D** 드릴다운(부모 조회 후 신원 매칭)
- **E** 추가만(append)

| # | 데이터셋 | 서비스 ID | 조회 단위 | 업무 테이블 (반영 방식) |
|---|---|---|---|---|
| 1 | land_info 토지(임야)대장 | KRAS000002 | PNU | parcel(A), land_register(A), land_owner(A) |
| 2 | shr_ymb 공유지연명부 | KRAS000003 | PNU | parcel(A), land_share(B) |
| 3 | land_bldg_check 토지·건물 존재 | KRAS000101 | PNU | parcel(A), land_presence(A) |
| 4 | collective_building 대지권(건물) | 미확정 | PNU | parcel(A), collective_building(C) |
| 5 | collective_unit 대지권(전유부) | 미확정 | 드릴다운 | collective_unit(D) |
| 6 | land_right 대지권등록부 | 미확정 | 드릴다운 | land_right(D), unit_ownership_history |
| 7 | land_mov_hist 토지이동연혁 | KRAS000006 | PNU | land_movement_history + relation(B+) |
| 8 | own_rgt_hist 소유권변동연혁 | KRAS000007 | PNU | parcel(A), land_ownership_history(B) |
| 9 | unit_ownership_history 집합건물소유권연혁 | 미확정 | 드릴다운 | unit_ownership_history(D) |
| 10 | **land_change 토지이동내역** | **배포 시 설정** | **기간(최대 10일)** | land_change_event(E) → **parcel·land_basic 현재 상태 반영** |
| 11·12 | 소유권/집합건물 변경내역 | — | — | 규격서가 §10 복사본이라 미구현 |
| 13 | integrated_building 건물통합정보 | 미확정 | PNU | integrated_building + building_parcel(B+) |
| 14 | building_image 건물통합도면 | 미확정 | PNU | building_image(E) |
| 15 | cadastral_file / usezone_file SHAPE | KRAS000038 | 시군구 전체 파일 | kras 공간 테이블 → 발행 → **public 승격(수동 버튼)** |
| 16 | **land_basic_file 토지기본정보 TXT** | KRAS000040 | 기관 전체 파일 | **parcel(A\*), land_basic(A\*)** — 기준선·월 보정 |
| — | land_price_file 공시지가 TXT | KRAS000039 | 기관 전체 파일 | land_price_file_row(원본 보존, 반영 단계 없음) |
| — | 건물 대장 4종(bldg_*), 토지이용계획 3종, KOREPS 가격 5종 | 선언형 매퍼 | PNU | building_*, land_use_*, *_price (B·C·D) |

주의: 15번 public 승격은 기존 연계가 매일 쓰는 `public.lp_pa_cbnd`/`lt_c_uzone`을 덮어쓴다. 0건이거나 건수가 급감하면 막는 장치는 있지만, 차세대 작업 중에 누르지 않는다.

## 3. 현재 개발 DB 상태

### 3.1 계약 상태 (`kras.sync_dataset`)
- 33개 데이터셋 전부 `UNVERIFIED` + 비활성.
- 그래서 어떤 데이터셋도 SUCCESS로 수집되거나 반영될 수 없다(트리거 `guard_item_transition`).
- 서비스 ID가 비어 있는 데이터셋: land_change, land_owner_change, unit_owner_change, building_image, collective_building, collective_unit, integrated_building, land_right, unit_ownership_history.

### 3.2 수집 이력 (`kras.sync_item`, `kras.sync_run`)
- 수집 건 32건이 모두 `PLANNED`(2026-09-18 더미)이고, 실행 2건은 모두 MANUAL이다.
- 실제 수집·반영 이력은 없다.
- `kras.ui_operation_log`: INGEST FAILED 1건.

### 3.3 업무 테이블 건수
`kras.*` 업무 테이블 39개 모두 1~3건이다. 전부 `kras-schema-dummy-data.sql`의 더미이고, 실제 KRAS 데이터는 없다.

| 테이블 | 건수 | 테이블 | 건수 |
|---|---|---|---|
| public.land_frst_ledg (기존) | 256,858 | kras.land_share | 3 |
| public.lp_pa_cbnd (기존) | 255,441 | kras.parcel / land_register / land_owner | 2 / 2 / 2 |
| public.lt_c_uzone (기존) | 17,658 | kras.land_movement_history / relation | 2 / 2 |
| ods.conn_request_log (중계 로그) | 9 | kras.land_basic / land_change_event | 1 / 1 |
| public.anvm_jiga (기존) | 0 | 그 외 kras.* 31개 | 각 1 |

### 3.4 배포 전 적용해야 할 마이그레이션 (개발 DB 미적용)

| 파일 | 내용 | 개발 DB |
|---|---|---|
| `docs/database/2026-10-07/2026-10-07-kras-schema-migration-land-change-dedup-key.sql` | land_change_event 중복 방지 키 `UNIQUE(org_cd, land_mov_no, hndl_ymd)` | **미적용** |
| `docs/database/2026-10-14/2026-10-14-kras-schema-migration-land-change-business-dataset.sql` | land_change가 parcel·land_basic을 갱신할 수 있게 등록 | **미적용** (land_change 대상 1개뿐) |
| `docs/database/2026-10-15/2026-10-15-kras-schema-migration-pnu-work.sql` | PNU 단위 API 전체·보정 적재 대기열 `kras.pnu_work` | **적용됨** (2026-10-15) |
| `docs/database/2026-10-15/2026-10-15-kras-schema-migration-parcel-business-dataset.sql` | shr_ymb·own_rgt_hist·collective_building이 parcel을 갱신할 수 있게 등록 | **적용됨** (2026-10-15) |

`kras-schema-create.sql`(신규 설치용)에는 네 내용이 모두 반영돼 있다.

## 4. 테스트 전용 DB

- `112.216.98.130:31613 / geosync_it`: 증분 동기화 검증용으로 2026-10-14에 만들었다.
- 운영·개발 DB와 별개이고, 작업이 끝나면 `DROP DATABASE geosync_it`로 통째로 지운다.
- 테스트는 실행마다 임의 기관코드(9xxxx)를 써서 데이터가 계속 쌓인다. SUCCESS 수집 건은 트리거상 지울 수 없기 때문이다.

## 5. 개발 DB 간이 데이터 (2026-10-15)

차세대 스케줄 화면의 대기열 현황을 보려고 mock 게이트웨이(`/mock/estateGateway`) 응답으로 넣은 시험 데이터다. **실제 KRAS 데이터가 아니다.**

- 시험 필지 30개: PNU `1283099999` + `1` + `9001~9030` + `0000`. 앱의 `/kras-db` 토지·건물 존재 수집·반영으로 만들었다.
- 계약 상태 변경: PNU 서비스 17종을 `VERIFIED` + 활성으로 바꿨다(그 전에는 UNVERIFIED·비활성). 바꾼 서비스는 land_info, shr_ymb, land_mov_hist, own_rgt_hist, land_bldg_check, 토지이용계획 3종, 건물 4종, KOREPS 5종이다.
- 대기열 `kras.pnu_work`(기관 12830): 시험 필지 30 + 기존 더미 필지 2 × 서비스. 대부분 완료이고, 건물 하위 조회 행을 포함한다.
- 패널 표시용 예시 상태 4건(개별공시지가, 오류 문구에 "(예시)"): 처리 도중 중단 2, 재시도 대기 2.
- 실패 1건은 실제 오류다. 기존 더미 필지 `1283010200199989998`의 land_use_plan_info가 실패했다. 더미 `land_use_plan_asset`이 계획 행을 참조하고 있어 범위 교체(B)가 지우지 못한다(아래 설계 문서 §12.2).
- 수집 성공 건(`sync_item` SUCCESS)은 트리거상 지울 수 없다. 업무 테이블의 시험 행도 그 수집 건을 참조한다.
