# 차세대 레이어(연속지적·용도지역 SHP) 적재 주기 분리 설계

- 작성일: 2026-10-16
- 상태: 설계 확정(2026-10-16), 구현 계획 `docs/superpowers/plans/2026-10-16-kras-layer-group-and-refresh-by-dataset.md`
- 관련: `2026-10-15/2026-10-15-kras-pnu-api-full-incremental-design.md` §7·§8(묶음 2개 구조)

## 1. 요구 (2026-10-16 사용자 결정)

1. 연속지적·용도지역 SHP는 전체 적재(월 1회)에서 떼어 **권장 적정 주기**로 적재한다.
2. 토지기본정보 TXT·공시지가 TXT를 실제로 쓰는 곳이 있는지 확인한다(§6).
3. PNU 재조회 주기(`refresh-days`)는 30일로 한다 → 반영함(§7).
4. 서비스별 재조회 권장 주기를 정리한다(§8, 구현은 별도 결정).
5. YG-Space 조회 필지 즉시 갱신은 하지 않는다.

## 2. 현재 문제

- 차세대는 연속지적·용도지역 SHP를 전체 적재 묶음(제안 월 1회)에서만 받는다.
- 그런데 KRAS 규격서 15. SHAPE 다운로드는 "매일 갱신하여 시군구 전체 정보를 제공함"이라고 적혀 있다(`docs/reference/kras.md`).
- 기존 연계(KrasWorker)도 두 레이어를 매일 04:30에 받는다(기본값 `kras.schedule`).
- 그래서 차세대로 전환하면 지도 레이어 갱신이 **매일 → 월 1회**로 줄어든다.
- 월 1회는 원천 갱신 주기를 근거로 정한 값이 아니었다. 2026-10-15 설계에서 PNU 전체 대기열 등록 주기에 파일 4종을 함께 묶은 결과다.

## 3. 권장 주기

| 레이어 | 원천 갱신 | 권장 | 근거 |
|---|---|---|---|
| 연속지적 SHP (`cadastral_file`) | 매일(규격서) | **매일** | 원천 주기, 기존 연계와 같음 |
| 용도지역 SHP (`layer_list` + `usezone_file`) | 규격서에 명시 없음 | **매일** (시범 측정 후 확정) | 기존 연계가 매일 받음. 레이어 300여 개를 따로 받으므로 소요 시간이 길면 주 1회로 내린다(§5) |

## 4. 구조 — 묶음 하나 추가

묶음은 지금 두 개(`full-load`, `correction`)다. 스케줄은 묶음에만 걸린다. 여기에 세 번째 묶음을 추가한다.

| 묶음 | 단계 | 제안 cron |
|---|---|---|
| 전체 적재 `full-load` | 토지기본정보 TXT → PNU 전체 대기열 등록 (**SHP 2단계 제거**, 공시지가 TXT는 §6에서 삭제) | `0 0 0 1 * *` (매월 1일 00:00) |
| 보정 적재 `correction` | 토지이동내역 → 재조회 등록 → 대기열 처리 (변경 없음) | `0 0 1 * * *` (매일 01:00, 07:00 종료) |
| **레이어 적재 `layer`** (신규) | 연속지적 SHP → 용도지역 SHP (둘 다 public 자동 승격) | **`0 30 7 * * *`** (매일 07:30) |

### 4.1 실행 시각 — 권고 시각 없음, 07:30 제안

- **공식 권고 시각은 없다.** KRAS 규격서(`kras.md` 15. SHAPE 다운로드)는 "매일 갱신"이라고만 적고, 몇 시에 파일을 새로 만드는지는 적지 않는다. 참고 문서(`docs/reference/`)에도 이용 시간대·권고 시각이 없다.
  - 기존 연계는 04:30(`kras.schedule` 기본값)에 받는다. 이 값도 근거 문서는 없다.
  - **KRAS 사업단에 일일 SHAPE 생성 완료 시각을 문의해야 한다.** 생성 전에 받으면 하루 늦은 파일을 받는다.
- 그래서 시각은 GeoSync 쪽 사정으로 정한다.
  - 차세대 스케줄러는 한 번에 하나만 돈다. 겹치면 뒤에 온 스케줄이 앞 묶음이 끝나길 기다린다(§11).
  - 보정 적재가 01:00–07:00(대기열 처리 종료 07:00)을 쓴다. 그 앞이나 안에 레이어 적재를 넣으면, 보정 적재가 그만큼 늦게 시작한다. 그런데 종료는 07:00으로 고정이라 대기열 처리 시간이 줄어든다.
  - 그래서 보정 적재가 끝난 뒤, 업무 시작(09:00) 전인 **07:30**을 제안한다. 지도 레이어가 업무 중에 바뀌지 않게 하려는 것이다.
  - KRAS 생성 시각이 07:30보다 늦다고 확인되면 그 뒤로 옮긴다.
- 업무 시작 전에 끝나는지는 시범 측정으로 확인한다(§5).

### 4.2 코드 변경 (예정)

- `KrasNextgenGroup`
  - `LAYER("layer", "레이어 적재", List.of(CADASTRAL, USEZONE))` 추가.
  - `FULL_LOAD`에서 `CADASTRAL`, `USEZONE` 제거.
  - `replacesPublic()`은 단계 기준이라 그대로 동작한다. 레이어 묶음이 true, 전체 적재는 false가 된다.
- `KrasNextgenSettingsService`
  - `schedules.layer` 키를 읽고 쓴다(묶음 enum 순회라 대부분 자동).
  - 이전 설정 옮기기: `full-load` cron만 있는 기존 설정은 레이어 적재가 꺼진 채로 시작한다. 자동으로 켜지 않는다.
- `application.yml` `kras-nextgen.schedules`에 `layer: ""`를 추가하고, 제안값을 주석으로 단다.
- 차세대 스케줄 화면은 묶음 목록을 그대로 그리므로 카드가 하나 늘어난다. 기존 연계 충돌 안내(`legacy-conflict`)는 `FULL_LOAD` 대신 `LAYER` 기준으로 바꾼다(§4.3).
- 테스트
  - `KrasNextgenSchedulerTest`: 묶음 단계 구성
  - `KrasNextgenSettingsServiceTest`: 키 읽기·쓰기
  - `KrasScheduleViewTest`: 카드 3개

### 4.3 기존 연계(KrasWorker)와의 관계

- 기존 연계는 `ods.schema` 설정 스키마(기본 `ods`)의 `lp_pa_cbnd`·`lt_c_uzone`에 쓴다(`TargetTableNameService.resolve`).
- 차세대는 `public.lp_pa_cbnd`·`public.lt_c_uzone`에 쓴다.
- 지금 화면 안내 문구 "두 곳이 같은 테이블을 씁니다"는 `ods.schema`가 `public`일 때만 맞는다.
  - 문구를 "기존 연계도 매일 같은 레이어를 받습니다. 차세대로 전환하면 기존 KRAS 스케줄을 끄세요"로 고친다.
  - `ods.schema`가 `public`이면 "같은 테이블을 덮어씁니다"를 덧붙인다.
- 두 경로를 동시에 켜 두는 것은 권하지 않는다(같은 KRAS 파일을 하루 두 번 받음).

### 4.4 하지 않는 것

- GeoWebCache 캐시 비우기: YG-Space가 GWC 레이어를 쓴다. 승격 뒤 캐시 비우기는 YG-Space 쪽 과제로 둔다(YG-Space `docs/design/geosync-integration-plan.md` 4.2).
- 레이어별 개별 스케줄(연속지적만 매일, 용도지역만 주 1회 등): §5 측정 결과 필요하면 묶음을 하나 더 나눈다.

## 5. 시범 측정 (구현 후 운영 서버)

- 개발 서버는 게이트웨이에 연결되지 않아 소요 시간 실측이 없다. 개발 DB `kras.ui_operation_log`에도 파일 적재 기록이 없다.
- 운영 서버에서 [지금 실행]으로 레이어 적재를 1회 돌려 단계별 시간을 확인한다.

| 결과 | 조치 |
|---|---|
| 두 단계 합 1시간 이내 | 매일 07:30 유지 |
| 용도지역만 1시간 초과 | 용도지역을 별도 묶음으로 나눠 주 1회(일요일 07:30) |
| 연속지적도 오래 걸림 | 실행 시각을 조정한다(업무시간 영향 확인) |

## 6. 토지기본정보·공시지가 TXT 사용처 (2026-10-16 확인)

| 파일 | 적재 테이블 | 읽는 곳 | 판단 |
|---|---|---|---|
| 토지기본정보 TXT | `kras.parcel`, `kras.land_basic` | **`kras.parcel`은 핵심**:<br>① PNU 전체·순환 대기열 등록의 필지 목록(`KrasPnuQueueService`)<br>② 토지이동연혁 등 반영의 FK 부모<br>③ YG-Space DB 모드 조회 조인(`KrasLand.xml`, `KrasBldg.xml`)<br>`kras.land_basic`은 건수 통계(`KrasStatsController`)와 토지이동 반영 대상일 뿐, 업무 조회에 쓰는 곳은 없다 | **유지**. 월 1회 전체 대조가 새 필지 등록·폐쇄 필지 정리를 맡는다 |
| 공시지가 TXT | `kras.land_price_file_row` (원본 보존, 반영 단계 없음) | 건수 통계·이력 화면뿐. YG-Space는 `kras.koreps_land_price`(API `land_jiga`)를 읽는다 | **삭제함(2026-10-16 사용자 결정, §11)**. KRAS 규격서(kras.md 16개 서비스)에도 없다 |

## 7. 재조회 주기 30일 (반영함)

- `KrasNextgenSettingsService.Pnu.defaults()`와 `src/main/resources/application.yml`
  - `refresh-days`: 90 → **30**
  - `rotate-limit`: 40,000 → 120,000 → **200,000** (30일 일괄이면 약 11만. 서비스별 주기·건물 여유로 20만 — §8.4)
- 운영은 `conf/application.yml`의 `kras-nextgen.pnu` 값이 우선한다. 값이 없으면 위 기본값이 쓰인다. 차세대 스케줄 화면 > 고급에서 저장하면 그 파일에 기록된다.
- 처리 시간 예상: 30일 일괄이면 하루 약 11만 건(건당 0.3초·동시 8건 ≈ 1.1시간), 한도 20만을 다 쓰면 약 2.1시간이다. 건당 시간은 시범 적재로 확인한다.

## 8. 서비스별 재조회 주기 (설계, 미구현)

현재는 모든 서비스가 같은 `refresh-days`(30일)를 쓴다. 서비스마다 바뀌는 빈도가 달라서, 서비스별로 주기를 나누면 같은 호출량으로 체감 신선도를 올릴 수 있다.

### 8.1 권장 주기

| 구분 | 서비스 (→ 테이블) | 권장 | 이유 |
|---|---|---|---|
| 소유자 | `land_info` (→ `land_register`·`land_owner`) | **14일** | 소유자·주소 변동이 가장 잦다 |
| 소유자 | `own_rgt_hist` (→ `land_ownership_history`) | **14일** | 소유권 이전 |
| 소유자 | `shr_ymb` (→ `land_share`) | **14일** | 공유자 변동 |
| 소유자 | `bldg_hds_info` (→ `building_title`·`floor`·`title_owner`·`title_change`) | **14일** | 건물 소유자 포함 |
| 소유자 | `cbldg_dfhs_info` (→ `building_exclusive*`) | 14일 | 전유부 소유자. 실응답 확인 전이라 현재 꺼짐 |
| 토지 상태 | `land_mov_hist` (→ `land_movement_history`) | 30일 → 토지이동내역 활성 후 **90일** | 토지이동이 생기면 다음 날 재조회로 잡힌다 |
| 토지 상태 | `land_bldg_check` (→ `land_presence`) | 30일 | 건물 신축·멸실 |
| 토지이용 | `land_use_plan_attr`, `land_use_plan_info`, `use_zone` | 30일 | 도시계획 고시 단위. 용도지역 레이어는 §3 매일 |
| 건물 | `bldg_dong_info` (→ `building_register`) | 30일 | 신축 건물 발견(하위 서비스 등록의 관문) |
| 건물 | `cbldg_hds_info` (→ `building_title`) | 30일 | 집합건물 표제부 |
| 건물 | `bldg_ho_info` (→ `building_unit`) | 30일 | 실응답 확인 전이라 현재 꺼짐 |
| 건물 | `bldg_ledg_gen_hds_info` (→ `building_summary`) | **90일** | 총괄표제부, 거의 안 바뀜 |
| 가격 | `land_jiga`, `house_info`, `fin_dec_jiga`, `read_dec_jiga`, `land_attr` | **연 1회**(06-01 이후, 현재와 같음) | 연 1회 공시 |

- 호출량(토지 8종 기준, 25.7만 필지)
  - 일괄 30일: 8 × 8,600 ≈ 하루 6.9만 건
  - 위 권장: 14일 3종(5.5만) + 30일 5종(4.3만) ≈ 하루 9.8만 건(약 40% 증가)
  - 건물 서비스는 건물 수에 비례해 별도로 더해진다.

### 8.2 설정

```yaml
kras-nextgen:
  pnu:
    refresh-days: 30              # 서비스별 값이 없을 때의 기본
    refresh-days-by-dataset:      # 서비스 코드 → 일. 비우면 전부 refresh-days
      land_info: 14
      own_rgt_hist: 14
      shr_ymb: 14
      bldg_hds_info: 14
      cbldg_dfhs_info: 14
      bldg_ledg_gen_hds_info: 90
    rotate-limit: 200000          # §8.4 (2026-10-16 사용자 결정)
```

- 위 6개 외(30일 권장 서비스)는 적지 않는다. 기본값 30일을 쓴다.
- `land_mov_hist`는 토지이동내역이 켜진 뒤 90일로 늘린다. 그때 이 목록에 `land_mov_hist: 90`을 추가한다.
- 가격 5종은 이 설정과 무관하다. 연 1회 규칙(`price-refresh-after`)을 그대로 쓴다. 목록에 적으면 검증에서 막는다.
- 검증: 값은 1 이상의 정수. 서비스 코드는 `KrasPnuQueueService.ALL_DATASETS` 중 가격 5종을 뺀 것만.
- 코드 기본값(`Pnu.defaults()`)에 위 6개를 넣는다. 운영 `conf/application.yml`에 이 키가 없어도 권장값으로 돈다.

### 8.3 코드 변경 (예정)

- `KrasNextgenSettingsService.Pnu`
  - `Map<String, Integer> refreshDaysByDataset` 필드와 `int refreshDays(String dataset)`(없으면 기본 `refreshDays`)를 추가한다.
  - 읽기·쓰기·검증을 붙인다.
- `KrasPnuQueueService` — 서비스별 일수를 jsonb 하나로 넘겨 SQL에서 고른다. 서비스마다 쿼리를 나누지 않는다.
  - 식: `make_interval(days => coalesce((?::jsonb ->> dataset_code)::int, ?))`
  - `enqueueFull`: 제외 기준(최근 N일 안에 받은 행은 다시 넣지 않음)에 이 식을 쓴다.
  - `enqueueRotation`: 대상 조건에 같은 식을 쓴다. 정렬은 `last_success_at`(가장 오래된 순)에서 **마감일 순**(`last_success_at + 서비스별 일수`)으로 바꾼다.
    - 바꾸는 이유: 오래된 순이면 90일 서비스의 오래된 행이 14일 서비스보다 먼저 뽑힌다. 하루 한도 안에서는 주기를 넘긴 정도가 큰 것부터 처리해야 한다.
- 차세대 스케줄 화면(고급 > PNU 서비스 표)
  - "재조회(일)" 칸을 추가한다. 비우면 기본값이고, 가격 5종은 "연 1회"로 고정 표시한다.
  - 저장하면 `refresh-days-by-dataset`에 쓴다.
- 대기열 현황 안내
  - 마감이 지난 행 수가 하루 한도(`rotate-limit`)를 넘으면 한 줄 경고한다.
  - 예: "ⓘ 재조회 마감이 지난 N건이 하루 한도 M건보다 많아 주기가 밀립니다."

### 8.4 호출량과 하루 한도

| 구분 | 서비스 수 | 하루 호출 (25.7만 필지 기준) |
|---|---|---|
| 14일 (토지 소유자 3종) | 3 | 3 × 18,400 ≈ 5.5만 |
| 30일 (토지 5종) | 5 | 5 × 8,600 ≈ 4.3만 |
| 건물 서비스 | 건물 수에 비례 | 운영 측정 필요 |
| **합계(토지)** | | **약 9.8만** (일괄 30일이면 6.9만) |

- 건물 서비스까지 더하면 12만을 넘을 수 있어 **20만**으로 올렸다(2026-10-16 사용자 결정, 코드 기본값·`application.yml` 반영).
- 한도가 모자라도 마감일 순이라 가장 많이 밀린 것부터 처리된다. 밀림은 위 안내로 알린다.
- 처리 시간: 20만 건 × 건당 0.3초 ÷ 동시 8건 ≈ 2.1시간. 보정 적재 창(01:00–07:00) 안이다. 건당 시간은 시범 적재로 확인한다.

### 8.5 테스트 (구현 시)

- 설정
  - 서비스별 값 읽기·쓰기
  - 없는 서비스는 기본값
  - 가격 서비스·0·음수는 검증 오류
- 대기열(DB 통합)
  - 같은 날 받은 행이라도 14일 서비스는 15일 뒤, 30일 서비스는 31일 뒤에 재조회 대상이 된다.
  - 한도가 작을 때 마감일 순으로 뽑힌다.
  - 전체 적재 제외 기준도 서비스별로 다르다.
- 소유자 변동을 매일 잡는 정확한 방법은 소유권 변경 피드(`land_owner_change`, `unit_owner_change`)다. 다만 규격서(§11·§12)가 §10 복사본이라 서비스 ID를 KRAS 담당자에게 확인해야 한다.

## 9. 테스트·검증 계획 (구현 시)

- 단위: 묶음 구성(`FULL_LOAD`에 SHP 없음, `LAYER` = CADASTRAL → USEZONE), 설정 키 `layer` 읽기·쓰기, 이전 설정에서 `layer`가 꺼진 채 시작.
- 화면: 카드 3개, 레이어 적재 안내 문구.
- 개발 서버: 레이어 적재 [지금 실행]이 실행 전 확인 단계에서 막히는 것까지 확인(`cadastral_file`·`usezone_file` UNVERIFIED). 실제 다운로드는 운영 서버에서.

## 10. 결정 필요

1. ~~레이어 적재 시각~~ → 07:30 확정(2026-10-16). KRAS 사업단에 일일 SHAPE 생성 시각 문의는 남음(§4.1).
2. ~~공시지가 TXT 제외~~ → 삭제함(§11).
3. ~~서비스별 재조회 주기~~ → 하루 한도 20만 확정, 구현 계획 작성(`docs/superpowers/plans/2026-10-16-kras-layer-group-and-refresh-by-dataset.md`).
4. ~~매월 1일 겹침~~ → 해결함(§11).

## 11. 반영한 것 (2026-10-16, 커밋 ec6766d)

- **공시지가 TXT 삭제**: 차세대 경로만 지웠다.
  - 전체 적재 단계 `LAND_PRICE`, `KrasFullLoadService.runLandPrice`, `KrasTxtIngestService.ingestLandPrice`를 지웠다.
  - `/kras-db` 카드·수집 API(`/kras-db/ingest/land-price-file`), 통계 화면 건수, 이력·검증 목록에서도 뺐다.
  - 기존 연계(`KrasTxtLoaderService` → `ods.anvm_jiga`, `KrasFileDownloadService`)와 `KrasApiClient.downloadJigaTxt`는 그대로 둔다.
  - DB 테이블 `kras.land_price_file_row`와 `sync_dataset`의 `land_price_file` 행은 남는다(DROP 하지 않음).
- **매월 1일 겹침**: `KrasNextgenScheduler.runGroup`에서 스케줄 실행은 다른 묶음이 돌고 있으면 건너뛰지 않고 끝나길 기다린다.
  - 화면 [지금 실행]은 종전처럼 거절한다.
  - 전체 적재 제안 cron을 `0 0 0 1 * *`(00:00)로 바꿔 보정 적재(01:00)보다 먼저 돌게 했다.
- **재조회 주기 30일**: §7.
- **DatabaseDefaultsTest**: 2026-10-14 결정(기본 설정에 값 직접 기재, 환경변수 미사용)에 맞게 고쳤다.
  - 비밀번호 칸이 비었는지 대신 `${`가 없는지를 본다.
  - 비밀번호 값은 테스트에 적지 않았다.

## 12. 구현 결과 (2026-10-16, 계획 `docs/superpowers/plans/2026-10-16-kras-layer-group-and-refresh-by-dataset.md`)

- **레이어 적재 묶음:** `KrasNextgenGroup.LAYER`(연속지적 → 용도지역)를 추가했다. 전체 적재는 토지기본정보 TXT → 대기열 등록만 남았다.
  - 설정 키 `kras-nextgen.schedules.layer`(기본 꺼짐, 제안 `0 30 7 * * *`)를 추가했다. 옛 설정 키 목록에도 `layer`를 넣어 설정을 읽다가 NPE가 나지 않게 했다.
  - 기존 연계 안내는 레이어 적재 기준으로 바꿨다. `ods.schema`가 public일 때만 "같이 덮어씁니다"가 붙는다.
- **서비스별 재조회 주기:** 설정 `Pnu.refreshDaysByDataset`를 추가했다.
  - 권장값은 `RECOMMENDED_REFRESH_DAYS`다. 키가 없으면 권장값, `{}`면 전부 기본 일수를 쓴다. 권장값과 같으면 파일에 쓰지 않는다.
  - 대기열 SQL은 jsonb로 서비스별 일수를 고른다. 순환 재조회는 마감일 순으로 뽑는다.
  - `overdue()`로 밀림을 알린다. 대기열 현황에 안내가 뜨며, 전체 적재 잔량이 있으면 생략한다.
  - 화면: 고급 > PNU 서비스 표에 "재조회(일)" 칸을 넣었다. 가격 5종은 "연 1회"로 표시한다.
- **테스트:** 전체 147개가 통과했다. 추가·수정한 테스트는 아래와 같다.
  - 설정: layer 왕복, 권장값·생략·`{}`, 검증
  - 스케줄러: 전체 적재는 SHP 단계를 부르지 않는다. 레이어 적재는 연속지적이 실패해도 용도지역을 실행한다.
  - 화면: 카드 3개, 재조회 칸
  - DB 통합: 서비스별 대상, 마감일 순, 전체 적재 제외 기준
- **개발 서버 확인:**
  - `/kras-schedule`에 카드 3장이 나온다. 재조회 칸 14개(가격 5종 제외)가 있고, land_info 값은 14다. 스크립트는 `node --check`를 통과했다.
  - 레이어 적재 [지금 실행]은 연속지적·용도지역 모두 실행 전 확인에서 멈춘다(개발 DB 미승인).
  - 대기열 현황 API는 정상이다. 전체 적재 잔량 4건이 있어 밀림 안내는 뜨지 않는다.
  - 화면 [적용]은 누르지 않았다. 실제 `conf/application.yml`을 다시 쓰게 되므로, 저장 왕복은 테스트(임시 파일)로만 확인했다.
