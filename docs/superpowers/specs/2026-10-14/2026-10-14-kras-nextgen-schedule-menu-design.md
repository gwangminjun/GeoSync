# 차세대 연계 스케줄 메뉴 설계

> 2026-10-15: 작업 5개 개별 스케줄은 **묶음 2개(전체 적재 / 보정 적재)**로 바뀌었다. PNU 단위 API 대기열이 추가됐다. 현재 기준은 `docs/superpowers/specs/2026-10-15/2026-10-15-kras-pnu-api-full-incremental-design.md`이다.


- 작성일: 2026-10-14
- 상태: **구현 완료(2026-10-14, 미커밋).** 결정 내용은 §10, 구현 결과는 §11.
- 관련: 증분 규칙 `docs/database/2026-10-14/2026-10-14-kras-incremental-sync-rules.md`,
  적재 현황 `docs/database/2026-10-14/2026-10-14-db-load-status.md`

## 1. 목적

차세대(kras 스키마) 적재의 스케줄과 설정을 한 화면에서 관리한다.

- **증분 적재**: 일일 토지이동(land_change) + 월 보정(토지기본정보 TXT). 지금은 `conf/application.yml`을 직접 고치고 재시작해야 한다.
- **전체 적재**: 연속지적 SHP, 용도지역 SHP, 공시지가 TXT. 지금은 `/kras-db` 화면 버튼으로만 실행된다(스케줄 없음).

기존 연계의 "스케줄"(`/schedule`)·"설정"(`/settings`) 화면과 코드는 건드리지 않는다. 그 화면들은 KrasWorker 적재와 파일 내려받기만 다룬다.

## 2. 메뉴

- 상단 메뉴에 **"차세대 스케줄"** `/kras-schedule`를 추가한다. 위치는 "KRAS 연계 관리" 오른쪽이다.
- 공용 `layout.html`에는 링크 한 줄만 추가한다("KRAS 연계 관리"를 넣었을 때와 같은 방식).
- 새 컨트롤러·템플릿·서비스는 모두 `geosync.kras` 패키지에 둔다.

## 3. 관리 대상 작업

| 작업 ID | 이름 | 구분 | 하는 일 | 기존 구현 |
|---|---|---|---|---|
| `land-change-daily` | 일일 토지이동 | 증분 | [시작일, 어제]를 10일 조각으로 수집 → 반영 | `KrasIncrementalSyncService.runDaily` |
| `land-basic-correction` | 토지기본정보 보정 | 증분(보정) | TXT 전체 수집 → 반영 → 필지 상태 보정 | `runMonthlyCorrection` |
| `cadastral-full` | 연속지적 전체 | 전체 | SHP 다운로드 → kras 적재(수집) → public 승격(자동) | `/kras-db/ingest/cadastral`, `/promote/cadastral` |
| `usezone-full` | 용도지역 전체 | 전체 | 카탈로그 → 레이어 순회 → 발행 → public 승격(자동) | `KrasUsezoneIngestService` 4단계 |
| `land-price-full` | 공시지가 TXT 전체 | 전체 | TXT 수집(원본 보존 테이블, 반영 단계 없음) | `/kras-db/ingest/land-price-file` |

범위 밖:
- **PNU 단건 데이터셋 전수 수집**(토지대장, 건축물대장 등): 전수 PNU 목록의 출처가 정해지지 않았다(2026-09-18 설계 §6.1).
  목록이 정해지면 같은 화면에 작업 행으로 추가한다.

## 4. 화면 (와이어프레임)

```
┌ 차세대 스케줄 ──────────────────────────────────────────────── 서버 시각 2026-10-14 16:40 ┐
│ ⚠ 기존 KRAS 동기화(KrasWorker)가 켜져 있습니다(매일 04:30). 연속지적·용도지역 전체 적재를 켜면 │
│   두 곳이 public.lp_pa_cbnd/lt_c_uzone을 같이 씁니다. 기존 동기화를 끄세요(설정 → KRAS 스케줄 비활성). │
│                                                       (둘 다 켜져 있을 때만 표시)       │
│ 실행 중: 없음                                                                          │
├ 증분 적재 ───────────────────────────────────────────────────────────────────────────┤
│ 토지이동내역 연계 정보 (배포 시 확인값)                                                  │
│   서비스 ID [KRAS0000xx]  시작일 필드 [______]  종료일 필드 [______]  날짜 형식 [yyyyMMdd] │
│   DB 서비스 ID: (없음) ⚠ 일치해야 호출됩니다                                             │
│                                                                                      │
│ 작업               스케줄                               다음 실행        실행 조건   마지막 결과        │
│ 일일 토지이동      (매일)(매주)(매월)(cron)(끔)  06:00   10-15 06:00   ✕ 미승인    —         [지금 실행] │
│ 토지기본정보 보정  (매일)(매주)(매월)(cron)(끔)  매월 1일 02:00  11-01 02:00   ✕ 미승인    —         [지금 실행] │
│ 기준선(T0): 없음 — 토지기본정보 보정을 한 번 실행하면 생깁니다                                      │
├ 전체 적재 ───────────────────────────────────────────────────────────────────────────┤
│ 작업               스케줄          반영 대상                      다음 실행   실행 조건   마지막 결과          │
│ 연속지적 전체      (매일)…(끔) 끔  kras → public.lp_pa_cbnd       —          ✕ 미승인    수집 실패 …  [지금 실행] │
│ 용도지역 전체      (매일)…(끔) 끔  kras → 발행 → public.lt_c_uzone —          ✕ 미승인    —            [지금 실행] │
│ 공시지가 TXT 전체  (매일)…(끔) 끔  kras.land_price_file_row       —          ✕ 미승인    —            [지금 실행] │
├ 최근 스케줄 실행 (kras.ui_operation_log 중 SCHEDULE) ─────────────────────────────────────────┤
│ 시각  작업  단계(수집/반영/승격)  상태  메시지                                          │
└──────────────────────────────────────────────────────────── [저장] (저장 즉시 적용) ┘
```

- **스케줄 입력**: 매일(시각) / 매주(요일+시각) / 매월(일+시각) / cron 직접 입력 / 끔. 내부에는 cron으로 저장한다.
  기존 설정 화면의 "인터벌 반복"은 넣지 않는다(적재는 정해진 시각이 맞다).
- **실행 조건**: 작업마다 지금 실행할 수 있는지와 그 사유를 보여준다(R1 게이트와 같은 판정).
  예: 계약 미승인, 서비스 ID 불일치, 필드명 미설정, 기준선 없음.
- **지금 실행**: 스케줄과 같은 경로로 즉시 1회 실행한다. 다른 차세대 작업이 실행 중이면 거절한다.
  오래 걸리므로 백그라운드로 돌리고, 결과는 실행 이력에서 본다(기존 operationId 폴링 재사용).
- **public 승격**: 연속지적·용도지역 전체 적재는 수집(·발행)에 성공하면 **자동으로 public까지 승격**한다(D-1).
  별도 켜기 옵션은 두지 않는다. 승격 단계의 기존 안전장치(0건 거부, 건수 50% 미만 급감 거부, 용도지역 전체 레이어 완료 필수)는 그대로 적용된다.
- **스케줄을 켤 때 확인창**: 연속지적·용도지역 전체 적재 스케줄을 켜면 "public GeoServer 테이블을 자동 교체한다"는 확인창을 띄운다.

## 5. 설정 저장과 적용

### 5.1 저장 위치 — `conf/application.yml`의 최상위 블록

`kras-incremental:` 블록(2026-10-14 커밋 `fa0fcf6`, 아직 배포 전)을 `kras-nextgen:`으로 넓힌다.

```yaml
kras-nextgen:
  land-change:
    service-id: ""
    start-param: ""
    end-param: ""
    date-format: "yyyyMMdd"
  schedules:                       # cron, 비우면 꺼짐
    land-change-daily: ""
    land-basic-correction: ""
    cadastral-full: ""
    usezone-full: ""
    land-price-full: ""
```

public 승격은 설정 항목이 아니다 — 연속지적·용도지역 전체 적재는 항상 승격까지 한다(D-1).

- **이 화면이 이 블록만 다시 쓴다.**
  - 다른 블록은 읽어서 그대로 보존한다. 기존 `SettingsFileService`와 같은 방식이지만 그 클래스는 수정하지 않는다.
  - 새 `KrasNextgenSettingsService`가 임시 파일에 쓴 뒤 교체한다.
- **설정 화면과의 관계**: 기존 설정 화면은 `kras`·`ods`·`sync` 블록만 교체한다. 그래서 `kras-nextgen` 블록은 보존된다(테스트로 확인됨).
- **주석 손실**: 파일을 다시 쓰면 YAML 주석은 사라진다. 기존 설정 화면도 같다.
- 서버 환경변수는 쓰지 않는다(2026-10-14 결정).

### 5.2 적용 — 재시작 없이

- 지금은 `@Value`로 기동할 때 한 번만 읽는다. 이것을 `KrasNextgenSettingsService`가 파일 수정 시각을 보고 다시 읽는 방식으로 바꾼다.
  `RuntimeSettingsService`와 같은 방식이다.
- `LandChangeMapper`의 서비스 ID·필드명도 이 서비스에서 실행할 때마다 읽는다.
- 저장하면 `KrasNextgenScheduler.reload()`가 스케줄을 다시 등록한다.
- 파일을 직접 고친 경우에는 재시작하거나, 화면의 [다시 읽기] 버튼으로 반영한다.

### 5.3 저장 시 검증

| 항목 | 검증 |
|---|---|
| cron | `CronExpression.parse` 실패 시 저장 거부 |
| 시작일·종료일 필드 | 둘 다 채우거나 둘 다 비우기 |
| 서비스 ID | 저장은 허용하되, DB `sync_dataset.service_code`와 다르면 경고(실행 조건에서도 막힘) |
| 연속지적·용도지역 스케줄 켜기 | 확인창(public 자동 교체 안내). 기존 KRAS 동기화가 켜져 있으면 경고 |

## 6. 실행 구조

```
KrasNextgenScheduler (ThreadPoolTaskScheduler 1스레드 — 작업이 겹치지 않음)
  └ 작업 실행기 (작업 ID → 실행 함수)
       ├ 공통: sync.enabled 확인 → 차세대 공용 락(tryLock, 실패 시 건너뜀) → 실행 조건 → 실행
       ├ 단계마다 ui_operation_log 기록 (요청 요약 "SCHEDULE …" / 지금 실행은 "NOW …")
       └ sync_run.job_kind: 매월 cron → MONTHLY, 그 외 → DAILY, 지금 실행 → MANUAL
```

- `KrasIncrementalSyncService`의 스케줄 등록 부분을 이 스케줄러로 옮긴다. 일일·월 보정 실행 함수는 그대로 쓴다.
- `KrasCadastralIngestService`·`KrasUsezoneIngestService`는 `sync_run`을 `MANUAL`로 고정해 두었다.
  TXT 수집처럼 `jobKind` 인자를 추가한다.
- 전체 적재 작업의 실행 함수는 지금 `KrasSchemaController`(컨트롤러)에 섞여 있다.
  예: SHP 다운로드 → 파싱 → 수집. 이것을 서비스로 옮겨 화면 버튼과 스케줄이 같은 코드를 쓰게 한다.
- **용도지역 전체 1회의 흐름**:
  1. 카탈로그 수집
  2. 레이어 순회
     - 실패한 레이어가 있으면 같은 릴리즈로 1회 더 재시도한다.
     - 그래도 실패하면 발행하지 않고 멈춘다. 다음 회차는 새 카탈로그부터 시작한다.
  3. 발행
  4. public 승격(자동)
- **연속지적 전체 1회**: SHP 다운로드 → 파싱 → 수집 → public 승격(자동). 수집이 실패하면 승격하지 않는다.
- **락 범위**: 스케줄과 지금 실행이 같은 락을 쓴다.
  - `/kras-db` 화면의 개별 버튼은 기존대로 데이터셋별 락만 쓴다.
  - ponytail: 화면 수동 작업과 스케줄이 겹칠 수 있다. 문제가 되면 같은 락으로 묶는다.

## 7. 기존 연계와의 충돌 방지

| 항목 | 규칙 |
|---|---|
| public 테이블 | 차세대 연계가 확정되면 기존 연계는 종료한다(2026-10-14 결정). 그 전까지는 기존 KrasWorker(매일 04:30)와 차세대 전체 적재가 같은 `public.lp_pa_cbnd`/`lt_c_uzone`을 쓰게 된다. **운영 규칙: 차세대 연속지적·용도지역 스케줄을 켤 때 기존 KRAS 스케줄은 끈다**(설정 화면 → KRAS 스케줄 비활성). 둘 다 켜져 있으면 차세대 스케줄 화면에 경고를 띄운다. 기존 코드는 수정하지 않는다 |
| 시각 | 제안 기본 시각을 기존 04:30(KRAS)·05:30(ODS)과 겹치지 않게 잡는다(일일 06:00, 전체 적재 02:00대, 월 보정 매월 1일 03:00) |
| 기존 화면·코드 | `/schedule`, `/settings`, KrasWorker, DynamicScheduleManager는 수정하지 않는다 |
| SHP 파일 | 기존과 같은 작업 폴더(`workspace/kras/{기관}`)에 같은 이름으로 받는다. 기존 적재가 같은 시각에 돌면 파일이 겹쳐 쓰일 수 있으므로 시각을 분리한다 |

## 8. 연관 화면 변경

- `/kras-db` 현황판의 스케줄 열을 새 스케줄러 기준으로 표시한다. 대상은 land_change, land_basic_file, cadastral_file, usezone_file, land_price_file.
- 증분 규칙 문서 §3(배포 설정)을 "화면에서 설정, 저장 위치는 `kras-nextgen` 블록"으로 고친다.

## 9. 구현 순서 (승인 후)

1. `KrasNextgenSettingsService`: 블록 읽기·쓰기·검증, 수정 시각 감지. `kras-incremental` → `kras-nextgen` 키 이전
2. `KrasNextgenScheduler`: 등록·재등록, 공용 락, 작업 실행기. 일일·월 보정을 이쪽으로 이전
3. 전체 적재 실행 함수 서비스화(연속지적·용도지역·공시지가) + `jobKind`
4. 화면 `/kras-schedule`(조회·저장·지금 실행·이력) + 메뉴 링크
5. `/kras-db` 현황판 연동, 문서 갱신
6. 테스트: 설정 블록 보존·검증, 스케줄 재등록, 테스트 DB에서 전체 적재 1회(API mock)

## 10. 결정 (2026-10-14 확정)

| # | 항목 | 결정 |
|---|---|---|
| D-1 | 전체 적재 뒤 public 승격 | **자동 진행.** 차세대 연계가 확정되면 기존 연계를 종료하므로 옵션으로 두지 않는다. 전환기에는 기존 KRAS 스케줄을 끄는 것을 운영 규칙으로 하고, 둘 다 켜져 있으면 화면이 경고한다 |
| D-2 | 설정 저장 위치 | `conf/application.yml`의 `kras-nextgen` 블록에 화면이 직접 저장, 재시작 없이 적용 |
| D-3 | 메뉴 이름·경로 | "차세대 스케줄" `/kras-schedule` |
| D-4 | 포함 작업 | §3의 5개. PNU 단건 전수 수집은 목록 출처가 정해진 뒤 |
| D-5 | 스케줄 입력 방식 | 매일/매주/매월/cron/끔 (인터벌 없음) |
| D-6 | 용도지역 실패 레이어 | 같은 회차에 1회 재시도, 그래도 실패하면 발행·승격하지 않음 |

## 11. 구현 결과 (2026-10-14)

| 구성 | 클래스·파일 |
|---|---|
| 작업 목록 | `KrasNextgenJob` (작업 ID·이름·구분·대표 데이터셋·실행 조건 데이터셋·public 교체 여부) |
| 설정 읽기·저장·검증 | `KrasNextgenSettingsService` — `kras-nextgen` 블록만 교체, 이전 `kras-incremental` 블록은 저장 시 삭제, 파일 수정 시각 감지 |
| 스케줄·락·지금 실행 | `KrasNextgenScheduler` — 1스레드 + 공용 락, 저장 시 `reload()`, `sync.enabled=false`면 스케줄 실행만 건너뜀 |
| 전체 적재 실행 | `KrasFullLoadService` — 연속지적(수집→public), 용도지역(카탈로그→순회×최대 2→발행→public), 공시지가 TXT |
| 증분 실행 | `KrasIncrementalSyncService` — 스케줄·락을 떼어내고 trigger/jobKind 인자 추가, `dailyBlockedReasons()` 공개 |
| 화면 | `KrasScheduleController` + `templates/kras-schedule.html`, 메뉴 "차세대 스케줄" |
| 현황판 | `/kras-db`의 스케줄 열을 `KrasNextgenScheduler.scheduleDisplay()`로 표시 |
| `/kras-db` 연속지적 수집 버튼 | `KrasFullLoadService.ingestCadastral()`을 같이 쓴다 |

- `sync_run.job_kind`: 스케줄은 일(day-of-month) 필드가 고정된 cron이면 MONTHLY, 아니면 DAILY. 지금 실행·화면 버튼은 MANUAL.
- 실행 기록 요청 요약: 스케줄 `SCHEDULE …`, 지금 실행 `NOW …`. 화면의 "최근 스케줄 실행"이 이 두 가지를 보여준다.

구현 중 실제 스키마(테스트 DB `geosync_it`)에서 찾아 고친 결함:
- **용도지역 순회가 실 DB에서 항상 실패**: release 기대 목록(`spatial_release_expected`)이 `spatial_layer`를 FK로 참조하는데,
  레이어 등록이 순회 단계에만 있어 release 준비에서 FK 위반이 났다. release 준비 때 레이어 계약을 먼저 등록하도록 고쳤다
  (레이어 이름은 순회 때 채움). 기존 `/kras-db` 용도지역 버튼도 같은 코드라 함께 고쳐졌다.

검증: `KrasFullLoadDbIntegrationTest` — 연속지적·용도지역은 public 테이블까지 자동 승격, 용도지역 레이어 실패 시 2회차 후 발행·승격 안 함,
공시지가 TXT 적재. 테스트 DB 준비에 `kras-schema-public-sync.sql`과 public 두 테이블(운영 DB에서 구조만 복제)을 추가했다.
