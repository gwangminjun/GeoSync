# KRAS 증분 동기화 — 남은 설계 (서비스 ID 없이 응답 기준으로)

- 작성일: 2026-10-14
- 선행 설계: `docs/superpowers/specs/2026-10-07/2026-10-07-kras-incremental-sync-design.md` (이하 "10-07 설계")
- 상태: **구현 완료(2026-10-14, 미커밋).** 구현 중 바뀐 점은 §11.

## 0. 전제

- `land_change` 서비스 ID, 요청 파라미터 이름은 **배포 시에만 알 수 있다.** 개발 환경에서는 실 API가 동작하지 않는다.
- 그래서 코드는 kras.md §10 **응답 구조 기준**으로 만들고, 배포 때 알게 되는 값은 전부 **설정으로 주입**한다. 코드를 고치거나 다시 빌드하지 않아도 되게 한다.
- 목표 흐름은 10-07 설계와 같다: **1회 기준선(전체) → 매일 변경분 → 월 1회 전체 보정.**

## 1. 현재 코드 점검 결과 — 실 DB에서 막히는 문제

10-07 설계 §8에서 "구현 + 테스트 완료"로 표시한 부분을 실제 스키마 트리거(`guard_business_row`, `kras-schema-create.sql:4050`)와 대조했다. 단위 테스트는 JdbcTemplate을 mock으로 써서 아래 문제를 잡지 못했다.

| # | 문제 | 위치 | 결과 |
|---|---|---|---|
| P-1 | `business_dataset`에 `(parcel, land_change)`, `(land_basic, land_change)` 행이 없다 | `kras-schema-create.sql:4002` | 신규 필지 INSERT가 `Dataset land_change cannot populate parcel`로 실패한다 |
| P-2 | 반영 시 기존 행의 `source_item_id`는 그대로 두고 `last_seen_item_id`만 land_change 수집 건으로 바꾼다 | `KrasLandChangeReflectionService.java:101,114` | 트리거가 `source_item_id`와 `last_seen_item_id`의 데이터셋·scope가 같은지 검사하므로 `last_seen item must have the same organization/dataset/scope`로 실패한다 |
| P-3 | 수집할 때 날짜(시작일·종료일)를 KRAS 요청에 넣지 않는다. `extraParams`만 보낸다 | `KrasDateRangeIngestService.java:95` | 지금은 화면에서 운영자가 직접 입력해야 한다. 스케줄 실행에는 이 값을 넣을 주체가 없다 |
| P-4 | 응답에 `LAND_MOV_CHG`가 0건이면 예외를 던진다 | `LandChangeMapper.java:74` | 이동이 없는 날 수집이 실패하고, 커서가 거기서 영원히 멈춘다 |
| P-5 | 커서가 "마지막 SUCCESS 수집 건"을 기준으로 한다. 반영됐는지는 보지 않는다 | `KrasDateRangeIngestService.nextWindowStart()` | 수집은 됐는데 반영에 실패한 구간을 건너뛴다. 그 구간의 변경은 현재 상태에 영영 반영되지 않는다 |
| P-6 | `findOrCreateTodayRun`이 `job_kind='MANUAL'`로 고정돼 있다 | `KrasDateRangeIngestService.java:135` | 스케줄 실행과 수동 실행을 이력에서 구분할 수 없다 |

P-1·P-2는 서비스 ID와 상관없다. 배포 후 첫 반영에서 바로 터진다. **구현 단계에서 가장 먼저 고쳐야 한다.**

## 2. 배포 시 주입하는 값 (설정)

> 구현에서 바뀜: 키는 최상위 `kras-incremental.*`이고 `${VAR}` 없이 값을 직접 적는다(§11 "배포 시 설정").

`src/main/resources/application.yml`에 `${VAR:}` 형태로 빈 기본값을 추가한다. 운영 값은 배포 서버의 `conf/application.yml`에서 채운다.

```yaml
kras:
  land-change:
    service-id: ${KRAS_LAND_CHANGE_SERVICE_ID:}      # 예: KRAS0000xx
    start-param: ${KRAS_LAND_CHANGE_START_PARAM:}    # 처리일자(시작일) 요청 필드명
    end-param: ${KRAS_LAND_CHANGE_END_PARAM:}        # 처리일자(종료일) 요청 필드명
    date-format: ${KRAS_LAND_CHANGE_DATE_FORMAT:yyyyMMdd}
    schedule: ${KRAS_LAND_CHANGE_SCHEDULE:}          # 일일 증분 cron. 비우면 꺼짐. 제안: 0 0 6 * * *
  land-basic:
    correction-schedule: ${KRAS_LAND_BASIC_CORRECTION_SCHEDULE:}  # 월 보정 cron. 비우면 꺼짐. 제안: 0 0 2 1 * *
```

- 날짜 형식 기본값 `yyyyMMdd`는 응답의 `HNDL_YMD`(예: `20140801`)에서 추정한 값이다. 배포 시 확인한다.
- `LandChangeMapper.connSvcId()`는 `service-id`가 비어 있으면 지금처럼 `UnsupportedOperationException`을 던진다. 그러면 기존 readiness 검사(`KrasHistoryService.readiness`)가 그대로 "미확정"으로 막아준다. 새 검사를 만들 필요가 없다.
- DB의 `kras.sync_dataset.service_code`도 같은 값으로 맞춰야 한다. 이미 있는 검사("DB 서비스 ID와 매퍼의 호출 ID가 다릅니다")가 둘이 다르면 막는다.
- 날짜 파라미터는 매퍼가 만든다: `KrasDateRangeServiceMapper`에 `default Map<String,String> dateParams(LocalDate start, LocalDate end)`를 추가하고, `LandChangeMapper`는 설정된 필드명·형식으로 채운다. 수집 서비스는 이 값을 `extraParams`와 합쳐 보낸다(P-3). 화면에서 수동으로 넣은 `extraParams`가 우선한다.

## 3. 1회 기준선 (T0)

- 새 테이블을 만들지 않는다. 기존 `land_basic_file` 수집 이력에서 구한다.

```
T0 = (가장 최근에 반영까지 끝난 land_basic_file 수집 건의 window_start) - 1일
```

- `window_start`는 TXT를 내려받은 날이다(`KrasTxtIngestService.createRunAndItem`).
- **1일을 빼는 이유**: TXT의 기준 시각이 내려받은 시각보다 이를 수 있다. 하루를 겹쳐 조회해도 중복 방지 키 `UNIQUE(org_cd, land_mov_no, hndl_ymd)`가 같은 사건을 한 번만 넣는다.
- "반영까지 끝남"은 `ui_operation_log`에 `action='PROMOTE'`, `status IN ('SUCCESS','WARNING')` 기록이 있는 것으로 판단한다. 현황판의 반영 대기 판정과 같은 기준이다. 스케줄 경로에서 반영할 때도 이 로그를 남긴다.
- T0가 없으면 일일 증분은 실행하지 않는다(게이트).

## 4. 일일 증분

### 4.1 실행 순서

```
1. 기관 단위 advisory lock 획득 (실패 → 이번 회차 건너뜀)
2. 게이트: readiness(land_change) 통과 + T0 존재
3. 윈도우 = [커서, 어제]   (커서 = 아래 4.2, 비어 있으면 종료)
4. 10일 조각으로 분할 (기존 splitWindows)
5. 조각마다: 수집 → 바로 반영 (같은 job 안에서 순서대로)
   - 실패하면 그 조각에서 멈춘다. 앞 조각의 결과는 유지한다.
6. lock 해제
```

- 10-07 설계는 "수집과 반영을 같은 트랜잭션"으로 묶자고 했다. 기존 구조가 수집 트랜잭션과 승격 트랜잭션을 분리하고 있으므로, **분리는 유지하고 순서만 붙인다.** 반영이 실패하면 그 조각의 수집 건은 "반영 안 됨"으로 남고, 커서가 그 조각을 다시 가리킨다(4.2). 그래서 같은 보장을 얻는다.
- `sync_run.job_kind='DAILY'`, `triggered_by='SCHEDULE'`로 남긴다. `findOrCreateTodayRun`에 jobKind 인자를 추가한다(P-6).

### 4.2 커서 — "반영까지 끝난" 구간 기준 (P-5)

```
커서 = max(window_end_exclusive)
       where dataset_code='land_change' and org_cd=? and status='SUCCESS'
         and (rows_valid = 0                                    -- 이동 없는 구간: 반영할 것 없음
              or 해당 item의 PROMOTE 성공 로그 존재)
커서가 없으면 = T0
```

- 반영에 실패한 조각은 커서에 포함되지 않는다. 다음 실행은 그 구간을 **새로 수집해서** 반영한다. 예전 수집 건을 다시 반영하지 않는 이유는, 그 사이 다른 반영이 끼었으면 트리거가 `Older collection cannot overwrite`로 막기 때문이다.
- 남는 예전 수집 건(반영 안 된 stage)은 지우지 않는다. 이력으로만 남긴다.
- 수동 화면(`/kras-db/ingest-range-auto`)도 같은 커서 함수를 쓰므로 함께 바뀐다.

### 4.3 이동 없는 날 (P-4)

| 응답 | 처리 |
|---|---|
| `LAND_MOV_CHG_SET`은 있고 `LAND_MOV_CHG`가 0건 | 정상. 0건 SUCCESS로 끝내고 반영은 생략한다 |
| 둘 다 없음 | 지금처럼 예외. 응답 구조가 문서와 다르다는 뜻이다 |

- 0건 응답의 실제 모양은 배포 시 확인해야 한다(V-3).

### 4.4 현재 상태 반영 수정 (P-1, P-2)

- **스키마 마이그레이션**(새 파일 `docs/database/2026-10-14/...-land-change-business-dataset.sql`):
  `INSERT INTO kras.business_dataset VALUES ('parcel','land_change'),('land_basic','land_change');`
- **반영 SQL**: 갱신할 때 `source_item_id`와 `last_seen_item_id`를 **둘 다** 이번 land_change 수집 건으로 바꾼다. 그러면 트리거의 "같은 데이터셋·scope" 검사를 통과한다.
- `source_item_id`는 항상 커지는 방향으로만 바뀌어야 한다(트리거의 `Older collection` 검사). 일일 증분과 월 보정이 같은 advisory lock을 쓰고 수집 직후 바로 반영하므로, 스케줄 경로에서는 이 순서가 보장된다.
- **남는 위험**: 수동 화면에서 수집만 해 두고 나중에 반영하면 위 검사에 걸릴 수 있다. 막지 않는다. 현황판의 실패 사유 안내("더 최신 수집 건…")가 이미 이 경우를 설명한다.

## 5. 월 1회 보정

### 5.1 실행 순서

```
1. 같은 advisory lock 획득
2. 게이트: readiness(land_basic_file) 통과
3. TXT 수집 (기존 ingestLandBasic, job_kind='MONTHLY')
4. 경고 없으면 바로 반영 (기존 promoteLandBasic)
5. 같은 트랜잭션에서 상태 보정:
   - 이번 TXT에 있는 필지 → record_status='ACTIVE' (SOURCE_CLOSED였던 것도 되살림)
   - 이번 TXT에 없고 ACTIVE였던 필지 → 'NOT_OBSERVED' (삭제하지 않음)
   - 두 경우 모두 source_item_id = last_seen_item_id = 이번 보정 수집 건
6. 바뀐 건수(활성화 n, 미관측 n)를 실행 로그에 남김
```

- 경고가 있으면 반영하지 않고 끝낸다. 수동 확인 대상으로 남는다(현황판 반영 대기에 표시됨).
- 지금의 반영(자연키 UPSERT)은 `record_status`를 건드리지 않는다. 그래서 5단계 보정이 따로 필요하다.
- **안전장치**: 미관측으로 바뀌는 필지가 전체의 일정 비율(제안 5%)을 넘으면 보정 단계만 롤백한다. 반영은 유지하고 경고를 남긴다. 파일이 잘렸거나 일부 기관 데이터가 빠진 경우를 막기 위해서다.

### 5.2 기준선과의 관계

- 맨 처음 기준선 적재는 지금처럼 화면에서 수동으로 수집하고 반영한다.
- 월 보정이 한 번 돌면 T0가 그 날짜로 자동 갱신된다. 커서가 이미 있으므로 일일 증분에는 영향이 없다.

## 6. 스케줄러 위치

- `kras` 패키지에 새 클래스 1개(`KrasIncrementalScheduler`)를 둔다. 기존 `SyncScheduler`/`DynamicScheduleManager`는 수정하지 않는다(10-07 설계 §6 원칙).
- Spring `TaskScheduler` + `CronTrigger`로 두 cron(일일, 월 보정)을 등록한다. 비어 있으면 등록하지 않는다.
- 설정 값은 시작 시에 한 번만 읽는다. 바꾸면 서비스를 재시작한다. 화면에서 바꾸는 기능은 만들지 않는다.
- 현황판의 `land_change` 행에서 "수동" 대신 다음 실행 시각을 보여준다(기존 `legacyNextRun` 표시 방식 재사용).

## 7. 개발 환경에서 하는 검증 (API 없이)

| 대상 | 방법 |
|---|---|
| 매퍼 파싱, 0건 응답 | kras.md §10 샘플 XML로 단위 테스트 |
| 날짜 파라미터 조립 | 설정 값 → `dateParams()` 결과 단위 테스트 |
| 커서·T0 계산 | SQL 문자열 + mock 단위 테스트 (기존 방식) |
| **P-1·P-2 트리거 통과** | mock으로는 못 잡는다. 로컬 PostgreSQL에 `kras-schema-create.sql`을 올리고 통합 테스트로 확인한다. 공유 원격 DB에는 넣지 않는다 |
| 스케줄 전체 흐름 | mock gateway(`mock.gateway.enabled=true`)에 §10 샘플 응답을 추가해 한 번 돌려 본다 |

## 8. 배포 시 확인할 것

| ID | 항목 | 반영 위치 |
|---|---|---|
| V-2 | land_change 서비스 ID | `kras-incremental.land-change.service-id` + `sync_dataset.service_code` |
| V-7 | 처리일자 시작·종료 요청 필드명 | `start-param`, `end-param` |
| V-8 | 날짜 형식 | `date-format` |
| V-3 | 이동 0건일 때 응답 모양 | 4.3 판정이 맞는지 |
| V-1 | `land_mov_no`+`hndl_ymd`가 기관 내에서 유일한지 | 중복 방지 키 (10-07 설계 §5) |
| V-9 | 응답의 BF/AF 필지가 항상 요청 기관 소속인지 | `LandChangeMapper.buildPnu` 가정 |
| V-10 | 토지기본정보 TXT의 기준 시각 | T0 "-1일"이 충분한지 |

## 9. 구현 순서 (승인 후)

1. P-1·P-2 수정 + 마이그레이션 SQL + 로컬 DB 통합 테스트 — 서비스 ID와 무관하고, 지금도 반영을 실패하게 만드는 결함
2. 설정 주입(서비스 ID, 날짜 파라미터) + P-3·P-4
3. 커서 수정(P-5) + T0
4. 일일 증분 스케줄러 + P-6
5. 월 보정 + 미관측 표시 + 안전장치
6. 현황판 다음 실행 표시

## 10. 범위 밖

- `land_owner_change`, `unit_owner_change`(§11·§12): 10-07 설계와 같은 이유로 제외한다(문서 내용이 §10 복사본).
- 기존 KrasWorker의 용도지역·연속지적 매일 전체 교체: 그대로 둔다. SHP는 시군구 전체 파일이라 증분 개념이 없다.
- 설정을 화면에서 바꾸는 기능.

## 11. 구현 결과와 설계에서 바뀐 점 (2026-10-14)

검증: 원격 서버의 테스트 전용 DB `geosync_it`(운영 DB와 별개, 작업 후 DROP 예정)에 `kras-schema-create.sql`을 올리고
`KrasLandChangeDbIntegrationTest`로 실제 트리거 위에서 기준선 → 일일 → 월 보정을 돌렸다. KRAS API만 mock.

| 항목 | 설계 | 구현 |
|---|---|---|
| P-2 반영 SQL | source·last_seen 둘 다 이번 건으로 | **source_item_id만 바꾸고 last_seen_item_id는 쓰지 않음.** last_seen에 land_change 건이 남으면 이후 월 보정 TXT 반영(자연키 UPSERT, source만 바꿈)이 트리거의 "같은 데이터셋" 검사에 걸린다 |
| 커서(4.2) | PROMOTE 로그 기준 | **"0건 구간 또는 그 수집 건의 행이 land_change_event에 있음"** — 로그에 의존하지 않아 화면·스케줄 어느 쪽 반영이든 같게 본다 |
| 시작일 | 커서, 없으면 T0 | **max(커서, T0)** — 월 보정으로 기준선이 커서보다 새로워지면, 그 이전 사건을 다시 적용해 최신 상태를 과거로 되돌리는 것을 막는다 |
| T0 | PROMOTE 로그 기준 | 설계대로 (`ui_operation_log`). 스케줄 실행도 같은 로그에 남긴다 |
| 동시 실행 | advisory lock | JVM 락(`ReentrantLock`) — 서버 1대 전제, `ponytail:` 주석으로 상한 표시 |

구현 중 추가로 찾아 고친 실 DB 결함(mock 테스트로는 안 잡히던 것):

- **P-7 토지기본정보 TXT 반영이 1행만 들어감**: `promoteNaturalKeyUpsert`가 `row_no=1`만 반영한다(PNU 단건용).
  전체 파일용 패턴 `NATURAL_KEY_UPSERT_ALL`을 추가해 `land_basic_file`에 적용, 반영 건수 ≠ stage 건수면 실패시킨다.
- **P-8 land_change_event 승격 실패**: `payload_hash`·`record_no`(NOT NULL, sync_record FK)를 복사하지 않았고,
  해시를 Java JSON 문자열로 계산해 DB CHECK(`sha256(payload::text)`, jsonb 정규화 텍스트)와 달랐다.

### 배포 시 설정 (결정 2026-10-14)

- 운영은 `--spring.config.location=conf/application.yml`만 읽는다. **서버 환경변수는 쓰지 않는다.**
- 설정 화면 저장(`SettingsFileService.save`)은 `kras`·`ods`·`sync` 블록을 화면 항목으로 **통째로 교체**하고 나머지 최상위
  블록은 보존한다. 그래서 증분 설정은 `kras:` 안이 아니라 **최상위 `kras-incremental:`** 블록에 둔다(§2의 `kras.land-change.*`에서 변경).
- 기동 시 한 번 읽으므로 변경 후 재시작한다. 운영 안내: `docs/database/2026-10-14/2026-10-14-kras-incremental-sync-rules.md` §3.
