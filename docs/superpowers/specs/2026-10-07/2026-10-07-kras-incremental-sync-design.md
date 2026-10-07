# KRAS 연계 — 기준선 + 변경분 증분 동기화 설계

작성일: 2026-10-07
상태: **설계안(미구현)**. 구현 전 작성. 실제 코드·규격서를 확인한 사실과 **가정**을 구분해 적는다. 가정은 "확인 필요" 절에 모은다.

관련 문서:
- `docs/reference/2026-09-18/kras.md` — KRAS 규격서 (§10 토지이동내역, §15 SHAPE, §16 토지기본정보)
- `docs/reference/2026-09-22/kras-ingest-structure.md` — 현재 구현 구조 (as-built)
- `docs/reference/2026-09-22/kras-service-catalog.md` — 서비스 ID 카탈로그
- `docs/superpowers/plans/2026-09-22-kras-integration-phase3.md` — 3차 계획 (예약 수집·기간 분할)

---

## 1. 목적

필지 데이터를 **초기 1회 전체 적재 → 이후 매일 변경분만 조회해 반영**하는 방식으로 유지한다.
매일 전체를 다시 받지 않는다. 전체 재적재는 드문 주기의 **보정** 용도로만 쓴다.

## 2. 근거 (kras.md 기준)

| 서비스 | 조회 조건 | 이 설계에서의 역할 |
|---|---|---|
| 토지기본정보 다운로드 (§16, KRAS000040) | 행정구역코드만 필수. 날짜 조건 없음 | **기준선** (1회) + 월 1회 **보정** |
| 토지이동 변동내역 (§10) | 처리일자 시작·종료, 종료는 시작 기준 10일 이내 | **일일 증분** |
| SHAPE 다운로드 (§15) | "매일 갱신, 시군구 전체" | 기존 용도지역·연속지적 경로 재사용 (이 설계에서 신규 구현 없음) |

토지이동내역은 이동 전(`BF_*`)·이동 후(`AF_*`) 값을 함께 준다 (`kras.md:848` 이하).
§11(소유권 변동내역)은 §10과 동일한 응답표가 복사되어 있어 **이 설계 범위에서 제외**한다 (§12 참고).

## 3. 현재 상태 (as-built)

- `KrasDateRangeIngestService`: 기간 조회 → stage → 승격. `maxQueryDays` 초과는 **거부만** 하고 분할하지 않는다.
- `LandChangeMapper`: `maxQueryDays=10`, `connSvcId()`가 **예외를 던짐** (서비스 ID 미확정).
- `land_change_event`: 승격 패턴 D(`APPEND_ONLY`). 현재 상태(필지 마스터)에는 **반영하지 않는다**. 이벤트 원본 보관만 한다.
- `kras.parcel`: 키 `pnu`, `record_status` 컬럼이 이미 `ACTIVE / SOURCE_CLOSED / NOT_OBSERVED`를 가진다.
- 지목·면적은 `kras.land_basic`에 있다 (`jimok`, `parea`, `schema-create.sql:247~250`).
- 스케줄: `kras.schedule`(04:30), `ods.schedule`(05:30) 등 cron 설정이 있고 `DynamicScheduleManager`가 관리한다. `land_change` 스케줄은 없다.
- `sync_run.job_kind`는 `DAILY / MONTHLY / MANUAL`을 이미 허용한다 (`schema-create.sql:63`).

**핵심 공백**: 지금은 이벤트를 쌓기만 하고 **필지 현재 상태에 반영하는 단계가 없다.** 증분의 실질 가치는 이 반영 단계에서 나온다.

## 4. 전체 흐름

```
[1회 기준선]   토지기본정보 TXT ──→ parcel + land_basic (기준일 T0 기록)
                                    │
[매일 증분]    T0 이후 윈도우 ──→ 10일 단위 청크로 분할
                 ├─ 청크별 land_change 조회 (sync_item 1건)
                 ├─ land_change_event 적재 (원본, 불변)
                 └─ 이벤트를 parcel/land_basic에 반영 (파생 상태)
                                    │
[월 1회 보정]  토지기본정보 TXT 재수집 → 현재 상태와 대조
                 ├─ 응답에 있는 필지: 갱신 (ACTIVE)
                 └─ 없는 필지: NOT_OBSERVED 표시 (삭제하지 않음)
```

### 4.1 기준선 (1회)

- 기존 `land_basic_file` 경로를 그대로 쓴다 (`KrasTxtIngestService`). 신규 구현 없음.
- 조회 시각을 **T0**로 기록한다. 증분 커서의 시작점이 된다.
- 게이트: `land_basic_file`이 `VERIFIED` + `enabled`여야 실행된다 (기존 계약 검증 규칙과 동일).

### 4.2 일일 증분

**커서**: 새 테이블을 만들지 않는다. 기존 `kras.sync_item`을 쓴다.

```
커서 = max(window_end_exclusive)  where dataset_code='land_change' and status='SUCCESS' and org_cd=?
커서가 없으면 = T0 날짜
```

**윈도우**: `[커서, 어제]`. 오늘 데이터는 아직 확정되지 않았다고 보고 제외한다 (`가정 A-3`).

**청크 분할**: 윈도우를 10일 이하 조각으로 나눈다. 조각마다 `sync_item` 1건을 만든다. 조각 하나가 실패하면 그 조각부터 다시 시작한다. 앞 조각의 성공은 유지된다.

**재개**: 다음 실행은 "마지막으로 연속 성공한 끝 날짜" 다음부터 시작한다. `FAILED`/`INTERRUPTED` 조각이 있으면 그 조각을 먼저 재시도한다.

**실행 단위**: 실행 1회 = `sync_run` 1건 (`job_kind='DAILY'`). 기존 `findOrCreateTodayRun` 규칙을 따른다.

### 4.3 이벤트 → 현재 상태 반영

이벤트 한 건은 "이동 전(BF) → 이동 후(AF)"이다. 반영 규칙은 다음과 같다.

| 조건 | 처리 |
|---|---|
| `AF_PNU`가 있음 | `parcel` upsert, `record_status='ACTIVE'`. `land_basic`의 지목·면적 갱신 |
| `BF_PNU`가 이 배치의 어떤 `AF_PNU`에도 없음 | 해당 `parcel`을 `SOURCE_CLOSED`로 표시 (분할·합병·이동으로 소멸) |
| 같은 날 여러 이벤트 | `HNDL_YMD`, `LAND_MOV_NO` 오름차순으로 순서대로 적용 |

- **원본 불변**: `land_change_event`는 수정하지 않는다. 현재 상태는 원본에서 **다시 계산할 수 있어야** 한다.
- **삭제 금지**: 현재 상태에서 행을 지우지 않는다. `SOURCE_CLOSED`/`NOT_OBSERVED`로만 표시한다. 기존 가드(`guard_business_row`)와 같은 원칙이다.
- 반영 단계는 이벤트 적재와 **같은 실행 안에서** 수행하되, 실패하면 그 조각 전체를 롤백한다 (커서가 전진하지 않도록).

### 4.4 월 1회 보정

- `job_kind='MONTHLY'`로 `land_basic_file`을 다시 받는다.
- 응답에 없는 `parcel`은 `NOT_OBSERVED`로 표시한다. 바로 지우지 않는다.
- 이유: 증분에서 놓친 변경이나 응답 누락을 잡기 위함이다. 보정 결과로 생긴 차이는 로그에 남긴다.

## 5. 중복·멱등성

**문제**: `land_change_event`의 유일 제약은 `UNIQUE(source_item_id, record_no)`뿐이다 (`schema-create.sql:678`). 조각을 재시도하면 **같은 이벤트가 다른 `sync_item`으로 다시 들어와 중복 적재된다.** 현재 스키마의 결함이다.

**제안**: 자연 키를 추가한다.

```
UNIQUE(org_cd, land_mov_no, hndl_ymd)   ← 가정 (확인 필요 V-1)
```

- 이 키가 맞으면 재시도·겹친 윈도우에서도 같은 사건이 한 번만 들어간다.
- 키가 틀리면 (`land_mov_no`가 전역 유일하지 않으면) 설계를 바꿔야 한다. 그 전에는 구현하지 않는다.
- 반영 단계(4.3)도 같은 키로 멱등하게 만든다. 같은 이벤트를 두 번 적용해도 결과가 같아야 한다.

## 6. 스케줄과 안전 게이트

- 설정 키: `kras.change-schedule` (cron). 기존 `kras.schedule`과 같은 형식이다.
- **기본 시각 제안: 매일 06:00.** 기존 KRAS 04:30, ODS 05:30 이후로 잡아 DB 부하를 나눈다.
- 구현 위치: `kras` 패키지의 신규 스케줄러. 기존 `SyncScheduler`/`DynamicScheduleManager`는 수정하지 않는다 (as-built 문서의 "신규 경로 분리" 원칙).
- 실행 전 게이트 — 하나라도 불충족이면 **실행하지 않고** 로그에 사유를 남긴다:
  1. `land_change`의 `service_code`가 설정됨 (V-2 확인 후)
  2. `land_change` 계약 `VERIFIED` + `enabled=true`
  3. `land_basic_file` 기준선 존재 (T0 기록 있음)
- **동시 실행 방지**: 기관 단위 PostgreSQL advisory lock (`pg_try_advisory_lock`). 잡혀 있으면 이번 회차는 건너뛴다.

## 7. 실패 처리

- `attempt_no`를 올리고 재시도한다. 상한은 3회로 제안한다 (3차 계획의 재시도 상한과 맞춘다).
- `heartbeat_at`이 일정 시간 갱신되지 않으면 `INTERRUPTED`로 표시한다 (기존 상태값 재사용).
- 3회 실패한 조각은 `FAILED`로 남기고, 다음 실행에서 그 조각부터 다시 시도한다. 사람이 원인을 본 뒤 수동 재실행할 수 있다.
- 실패 알림은 기존 실행 로그(`KrasOperationLogService`)에 남긴다. 신규 알림 채널은 만들지 않는다.

## 8. 구현 범위

| 항목 | 상태 | 작업 |
|---|---|---|
| 기준선 적재 (`land_basic_file`) | 있음 | 없음 |
| 10일 청크 분할 | 없음 | `KrasDateRangeIngestService`에 분할 루프 추가 (거부 → 분할) |
| 커서 계산 | 없음 | `sync_item` 조회 쿼리 1개 |
| 이벤트 → parcel 반영 | 없음 | 신규 반영 서비스. 패턴 A(upsert) + `SOURCE_CLOSED` 규칙 |
| 중복 방지 키 | 없음 | 마이그레이션: `land_change_event` 유일 키 추가 (V-1 확인 후) |
| 증분 스케줄러 | 없음 | `kras` 패키지 신규 컴포넌트 + `kras.change-schedule` |
| 월 보정 | 없음 | `MONTHLY` 잡 (기존 TXT 경로 재사용) |
| `LandChangeMapper.connSvcId()` | 미확정 | V-2 확인 후 값 입력 |

## 9. 테스트 계획

- **단위**: 윈도우 → 청크 분할 (경계: 10일, 11일, 1일, 역순 날짜), 커서 계산, 반영 규칙 3가지(ACTIVE / SOURCE_CLOSED / 순서), 중복 키.
- **멱등성**: 같은 이벤트 묶음을 두 번 반영해도 `parcel`·`land_basic` 상태가 같은지 확인.
- **실패 재개**: 2번째 청크에서 실패시키고 다음 실행이 2번째부터 재개하는지 확인. 1번째 청크 결과는 유지되어야 한다.
- **목 게이트웨이**: 기존 `MockGatewayController` 응답으로 land_change 응답 구조를 흉내 낸다. 실제 응답 구조는 V-3~V-4로 검증한다.
- 기존 테스트 `KrasDateRangeIngest`, `KrasMapperRegistryTest`가 깨지지 않아야 한다.

## 10. 범위 밖

- §11 소유권 변동내역: 규격서 응답표가 §10과 동일하게 복사되어 있다. 실제 응답을 받은 뒤 별도 설계한다.
- §12 집합건물 소유권 변동내역: §11과 같은 이유.
- 건물통합정보(§13)·건물통합도면(§14)의 증분: 서비스 ID 미확정. 기준선 조회 방식(날짜 조건 유무)을 먼저 확인해야 한다.
- SHAPE(§15)의 증분화: 규격상 시군구 전체 파일이므로 증분 개념이 없다. 기존 릴리즈 흐름을 유지한다.

## 11. 확인 필요 (구현 전 필수)

| ID | 확인 내용 | 이유 | 확인 방법 |
|---|---|---|---|
| V-1 | `land_mov_no`가 기관 내에서 `hndl_ymd`와 함께 유일한가 | 중복 방지 키(5절)의 전제 | 운영 응답 샘플 2개 기간 비교 |
| V-2 | 토지이동내역의 `conn_svc_id` | 호출 자체가 막혀 있음 | KRAS 연계 담당자 |
| V-3 | 응답에 행정구역코드가 있는지 | 현재 `before_pnu`/`after_pnu`를 요청의 `org_cd`로 조립함 (가정) | 운영 응답 |
| V-4 | 한 이벤트의 BF/AF가 1:1인지, 분할·합병은 여러 행으로 오는지 | 4.3 반영 규칙의 전제 | 분할/합병이 있었던 기간으로 실제 응답 확인 |
| V-5 | 같은 날 이벤트의 순서 보장 여부 (`LAND_MOV_NO` 순서가 처리 순서인지) | 4.3 순서 규칙 | 운영 응답 |
| V-6 | 소유자 변경이 토지이동내역에도 포함되는지 | 범위 판단 (10절) | 운영 응답 + 담당자 |
| V-7 | 운영 서버에서 KRAS 기준 "하루 지연" 여부 (당일 데이터 확정 시각) | 윈도우 끝을 어제로 잡은 이유 (A-3) | 담당자 |

### 가정 (구현 전 검증)

- A-1: 청크 크기 10일은 `sync_dataset.max_query_days`로 읽는다. 하드코딩하지 않는다.
- A-2: 토지기본 TXT의 지목·면적 값은 이벤트 반영값과 같은 단위다 (면적 m² 등). 단위 차이가 있으면 반영 전에 변환한다.
- A-3: 당일 데이터는 확정되지 않을 수 있으므로 윈도우 끝은 어제로 한다.

## 12. 결정이 필요한 것

1. **스케줄 시각**: 06:00 제안. 운영 부하에 따라 조정.
2. **월 보정 주기**: 월 1회 제안. 주간으로 바꿀지 결정 필요.
3. **보정 결과 처리**: `NOT_OBSERVED`만 표시할지, 일정 기간 이상이면 `SOURCE_CLOSED`로 올릴지.
4. **구현 순서**: 제안은 "V-1~V-5 확인 → 중복 키 마이그레이션 → 청크 분할 → 반영 서비스 → 스케줄러 → 보정" 순서.
