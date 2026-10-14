# KRAS 증분 동기화 규칙

- 대상: 필지 현재 상태 `kras.parcel`, `kras.land_basic`
- 구현: `KrasIncrementalSyncService`, `KrasDateRangeIngestService`, `KrasLandChangeReflectionService`, `KrasTxtIngestService`
- 설계 배경: `docs/superpowers/specs/2026-10-14/2026-10-14-kras-incremental-sync-remaining-design.md`
- 적재 현황: `2026-10-14-db-load-status.md`
- 기존 연계(실시간 중계, KrasWorker 스케줄)와는 별개 경로다. 이 규칙은 그쪽에 적용되지 않는다.

## 1. 전체 흐름

```
[1회 기준선]  토지기본정보 TXT(KRAS000040) 수집 → 반영 ........ T0 = 수집일 - 1일
                     │
[매일 증분]   [시작일, 어제]를 10일 조각으로 분할
                ├─ 조각마다 토지이동내역(land_change) 수집
                ├─ land_change_event에 원본 추가(불변)
                └─ 이벤트를 parcel/land_basic 현재 상태에 반영
                     │
[월 1회 보정] 토지기본정보 TXT 재수집 → 반영 → 필지 상태를 파일과 대조
```

기준선은 첫 월 보정과 같은 동작이다. 처음에는 화면에서 수동으로 하거나, 월 보정 스케줄의 첫 실행에 맡긴다.

## 2. 규칙

### R1. 실행 조건 (하나라도 빠지면 실행하지 않고 사유만 남긴다)

| 작업 | 조건 |
|---|---|
| 일일 증분 | ① land_change 계약 `VERIFIED` + 활성<br>② 서비스 ID가 설정돼 있고 `kras.sync_dataset.service_code`와 같음<br>③ 처리일자 요청 필드명이 설정됨<br>④ 기준선(T0)이 있음 |
| 월 보정 | land_basic_file 계약 `VERIFIED` + 활성 + 서비스 ID |
| 공통 | `sync.enabled=true`. 일일 증분과 월 보정은 동시에 돌지 않는다(같은 락). 실행 중이면 이번 회차는 건너뛴다 |

### R2. 기준일 T0
- `T0 = (반영까지 끝난 가장 최근 토지기본정보 TXT 수집 건의 수집일) - 1일`
- "반영까지 끝남"은 `kras.ui_operation_log`에 `PROMOTE`가 `SUCCESS` 또는 `WARNING`으로 남은 것으로 판단한다.
- 하루를 빼는 이유: TXT의 기준 시각이 내려받은 시각보다 이를 수 있어 하루를 겹쳐 조회한다. 겹친 사건은 R6의 중복 방지 키가 한 번만 넣는다.

### R3. 조회 구간
- 커서 = "반영까지 끝난" land_change 수집 건 중 가장 늦은 종료일의 다음 날
  - 반영까지 끝난 건 = 0건 구간이거나, 그 수집 건의 행이 `kras.land_change_event`에 들어간 건
- **시작일 = max(커서, T0)**
  - 월 보정으로 기준선이 더 새로워졌다면, 그 이전 사건은 이미 TXT에 들어 있다.
  - 그 사건을 다시 적용하면 최신 상태가 과거 값으로 되돌아가므로 기준선부터 시작한다.
- **종료일 = 어제.** 오늘 처리분은 아직 확정되지 않았다고 본다.
- 시작일이 종료일보다 늦으면 할 일이 없다.

### R4. 조각 처리와 실패
- 구간을 10일 이하 조각으로 나눠(규격 §10 제한) 날짜 순서대로 처리한다.
- 조각마다 **수집 직후 바로 반영**한다. 수집과 반영은 각각 한 트랜잭션이다.
- 한 조각이라도 실패하면(수집 실패, 파싱 경고, 반영 실패) **그 조각에서 멈춘다.** 앞 조각의 결과는 유지한다.
- 실패한 조각은 커서에 들어가지 않는다. 다음 실행이 그 구간을 **새로 수집해서** 다시 반영한다.
  - 예전 수집 건을 나중에 반영하면 DB가 "과거 건으로 최신 데이터 덮어쓰기"로 막을 수 있어서다.
  - 남은 예전 수집 건은 지우지 않고 이력으로만 둔다.
- 이동이 없는 날: 응답에 `LAND_MOV_CHG_SET`은 있고 `LAND_MOV_CHG`가 0건이면 0건 정상(SUCCESS)으로 처리하고 커서를 진행한다.
  목록 태그 자체가 없으면 응답 구조 이상으로 보고 멈춘다.

### R5. 이벤트 → 현재 상태 반영

| 조건 | 처리 |
|---|---|
| 이동 후 필지(AF)가 있음 | `parcel` UPSERT + `land_basic`의 지목·면적 갱신, `record_status='ACTIVE'` |
| 이동 전 필지(BF)가 이번 조각의 어떤 AF에도 없음 | 분할·합병·이동으로 소멸 → `record_status='SOURCE_CLOSED'` |
| 같은 필지에 사건이 여러 개 | 처리일자·이동번호 순으로 적용, 마지막 값이 남는다 |

- **행을 지우지 않는다.** 상태값으로만 표시한다.
- 바뀐 행의 `source_item_id`는 이번 land_change 수집 건이 된다.
- `last_seen_item_id`는 쓰지 않는다. DB 가드가 두 값이 같은 데이터셋에서 왔는지 검사하므로, 여기에 land_change 건을 남기면 이후 TXT 반영이 막힌다.
- 원본 `land_change_event`는 수정하지 않는다. 현재 상태는 원본에서 다시 계산할 수 있어야 한다.

### R6. 중복 방지
- `land_change_event`: `UNIQUE(org_cd, land_mov_no, hndl_ymd)`. 이 키가 기관 안에서 유일한지는 배포 시 확인해야 한다(V-1).
- 원본 해시 `payload_hash = sha256(payload::text)`는 DB가 CHECK한다. `sync_record`와 `record_no`로 연결된다.

### R7. 월 보정
1. TXT 전체를 수집한다. 파싱 경고가 있으면 반영하지 않고 멈춘다(현황판에 반영 대기로 표시됨).
2. 파일의 **전체 행**을 `parcel`/`land_basic`에 자연키(pnu)로 UPSERT한다. 반영 건수가 수집 건수와 다르면 전부 되돌린다.
3. 같은 트랜잭션에서 상태를 보정한다:
   - 파일에 있는데 ACTIVE가 아닌 필지(소멸·미관측이었던 것)는 `ACTIVE`로 되돌린다.
   - 파일에 없는 ACTIVE 필지는 `NOT_OBSERVED`로 바꾼다. 삭제하지 않는다.
4. **안전장치:** 미관측으로 바뀔 필지가 현재 ACTIVE의 **5%를 넘으면** 3단계의 미관측 표시를 건너뛰고 WARNING으로 기록한다.
   2단계 반영은 유지한다. 파일이 잘렸거나 일부가 빠진 경우를 막기 위해서다.

### R8. 기록
- 수집·반영 한 번마다 `kras.ui_operation_log`에 남긴다. 화면 실행과 같은 표이고, 요청 요약은 `SCHEDULE …`이다.
- 실행 묶음은 `kras.sync_run.job_kind`로 구분한다: 일일 `DAILY`, 월 보정 `MONTHLY`, 화면 `MANUAL`.
- 현황판(`/kras-db`)에는 스케줄이 켜진 행에 "일일 증분 · cron"/"월 보정 · cron"과 다음 실행 시각이 표시된다.
  실패 사유와 반영 대기 건도 기존 화면에 그대로 보인다.

## 3. 배포 설정

운영 서버의 `conf/application.yml`(실행 시 이 파일만 읽음)에 **최상위 블록 `kras-incremental:`**으로 넣는다.
`kras:` 안에 넣지 않는다 — 설정 화면에서 저장하면 `kras:` 블록이 통째로 화면 항목으로 교체돼 지워진다.
`kras-incremental:`처럼 다른 최상위 블록은 저장해도 그대로 남는다(`SettingsFileServiceIncrementalKeysTest`로 확인).
서버 환경변수는 쓰지 않는다.

```yaml
kras-incremental:
  land-change:
    service-id: "KRAS0000xx"        # land_change 서비스 ID — kras.sync_dataset.service_code와 같은 값
    start-param: "필드명"            # 처리일자(시작일) 요청 필드명
    end-param: "필드명"              # 처리일자(종료일) 요청 필드명
    date-format: "yyyyMMdd"         # 요청 날짜 형식
    schedule: "0 0 6 * * *"         # 일일 증분 cron (비우면 꺼짐)
  land-basic:
    correction-schedule: "0 0 2 1 * *"   # 월 보정 cron (비우면 꺼짐)
```

| 키 | 비우면 |
|---|---|
| `land-change.service-id` | 호출하지 않음(R1에서 "미확정"으로 막힘) |
| `land-change.start-param` / `end-param` | 일일 증분이 실행되지 않음(화면 수동 수집은 추가 파라미터로 가능) |
| `land-change.date-format` | `yyyyMMdd` |
| `land-change.schedule` / `land-basic.correction-schedule` | 스케줄 꺼짐(현황판은 "수동") |

- 이 설정은 **기동할 때 한 번 읽는다.** 바꾸면 서비스를 재시작한다(설정 화면에는 나오지 않는다).
- 참고로 설정 화면 항목(KRAS url·스케줄, 기관코드 등)은 저장 즉시 적용되지만, `conf/application.yml`을 직접 고친 경우
  스케줄은 재시작해야 다시 등록된다.
- 배포 DB에는 마이그레이션 2개(적재 현황 문서 §3.4)를 먼저 적용한다.

## 4. 배포 시 확인할 것

| ID | 확인 | 틀리면 |
|---|---|---|
| V-2 | land_change 서비스 ID | 호출 불가(R1에서 막힘) |
| V-7, V-8 | 처리일자 요청 필드명·형식 | 기간 조건 없이 조회되거나 오류 |
| V-3 | 이동 0건일 때 응답 모양 | 이동 없는 날 진행이 멈춤(R4) |
| V-1 | `land_mov_no + hndl_ymd`가 기관 안에서 유일한지 | 다른 사건이 중복으로 걸러짐 → 중복 방지 키 재설계 |
| V-9 | 응답의 BF/AF 필지가 항상 요청 기관 소속인지 | PNU 조립 오류 |
| V-10 | TXT 기준 시각 | T0의 하루 겹침이 부족할 수 있음 |

## 5. 검증 (2026-10-14)
- 실제 kras 스키마를 올린 테스트 DB(`geosync_it`)에서 다음 시나리오가 통과했다. KRAS API만 mock이다(`KrasLandChangeDbIntegrationTest`).
  - 기준선 30필지 반영
  - 일일 증분: 분할로 소멸·신규, 지목 변경
  - 같은 날 재실행하면 할 일 없음
  - 월 보정: 재활성 1, 미관측 1
  - 5% 가드
  - 실패 구간에서 커서 유지, 0건 구간에서 커서 진행
- 전체 테스트 107개 중 실패 1건(기존부터 있던 무관한 `DatabaseDefaultsTest`).
