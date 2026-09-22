# KRAS 연계 관리 3차 구현 계획

**Goal:** 검증된 서비스에 한해 PNU 다건·기간 분할 수집을 예약/일괄 실행할 수 있게 하고, 서비스별 입력을
구조화하며, 배치 실행에 속도 제한·재시도·중단 후 재개를 붙인다.

**Architecture:** 새 큐 테이블을 만들지 않는다 — `kras.sync_run`/`kras.sync_item`이 이미
`PLANNED/COLLECTING/VALIDATING/READY/APPLYING/SUCCESS/FAILED/BLOCKED/INTERRUPTED` 상태와
`heartbeat_at`/`attempt_no` 컬럼을 갖고 있다(`docs/database/2026-09-18/kras-schema-create.sql:59-105`).
그런데 지금 코드(`KrasPnuIngestService`/`KrasDateRangeIngestService`)는 이 중 기본값 `PLANNED`와
`SUCCESS`만 쓴다 — 나머지 상태·컬럼은 이미 설계돼 있는데 배선이 안 된 상태다. 배치 제출 1건 =
`kras.sync_run` 1건(`job_kind='MANUAL'`, `triggered_by='BATCH-UI'`), 그 안의 PNU/기간 단위 각각이
`kras.sync_item` 1건이다. 큐 처리는 `KrasOperationLogService.runAsync`가 이미 쓰는 "외부 빈에서
호출해야 `@Async` 프록시가 걸린다" 패턴을 그대로 쓰는 단일 워커 루프(순차 처리, 속도 제한은 호출
사이 고정 지연)로 만든다 — `SyncApplication`은 `@EnableAsync`만 있고 커스텀 `TaskExecutor`가 없어
기본 `SimpleAsyncTaskExecutor`(스레드풀 없음, 무제한 동시 실행)로 동작한다. 반복문을 병렬로 돌리면
KRAS에 무제한 동시 호출이 나가므로 반드시 순차 처리한다.

**Tech Stack:** 기존과 동일 — Java 17, Spring Boot, JdbcTemplate, PostgreSQL, Thymeleaf, JavaScript.

**Spec:** `docs/reference/2026-09-22/kras-integration-improvement-recommendations.md`의 3차 3개 항목(§6).

**선행 완료 상태(2026-09-22 기준):** 1차·2차 전부 완료(비동기 실행, 목록·상세 팝업, 검증 근거, 반영 전
비교, `04908ef`까지 커밋됨). VERIFIED 데이터셋은 아직 0개다. 이 계획은 코드·스키마 배선까지 진행하고,
"운영에서 배치를 실제로 켜는 것"은 최소 1개 데이터셋이 VERIFIED된 뒤로 미룬다(권고안 §6의 3차 도입
조건과 동일). 그 전까지는 목 데이터/`test` 스키마로만 검증한다.

## 범위와 결정

- (핵심 발견) `kras.sync_run`/`kras.sync_item`은 이미 배치·재시도·재개에 필요한 상태값과 컬럼을 갖고
  있다 — 새 큐 테이블을 만들지 않는다.
- `kras.sync_work`(필지/건물 재조회 큐, `item_id` 필수 FK, `docs/database/2026-09-18/kras-schema-create.sql:157-172`)는
  이미 스키마에 있지만 애플리케이션 어디서도 참조하지 않는다. 원래 설계는 "한 item 처리 중 발견한
  후속 조회 대상을 큐에 등록"(예: `bldg_dong_info`로 찾은 동 목록을 `bldg_ho_info` 조회 큐에 자동
  등록)용이지, "운영자가 입력한 독립적인 PNU 목록"용이 아니다(`item_id`가 반드시 부모 item을 가리켜야
  해서 구조가 안 맞는다). 이번 3차 핵심 범위(PNU 다건/기간분할)에는 쓰지 않는다 — §1에 "나중에
  `sync_work`를 쓸 수 있다"는 메모만 남긴다.
- `KrasPnuIngestService.ingestInTransaction()`/`KrasDateRangeIngestService`는 지금 item 생성과 실제
  수집을 **한 트랜잭션 안에서** 처리한다 — 실패하면 롤백돼 item 자체가 안 남는다(1차에서
  `kras.ui_operation_log`를 트랜잭션 밖에 따로 둔 이유와 같은 문제, 이번엔 `sync_item` 쪽을 고친다).
  배치가 항목별로 실패를 추적하려면 **item 생성(PLANNED)과 실제 수집을 분리**해야 한다 — 이게 3차의
  필수 선행 리팩터다.
- 배치는 시스템 전체에서 **한 번에 하나만** 실행한다(기존 `operationLocks`/`AtomicBoolean running`
  패턴 재사용) — 여러 배치를 동시에 큐잉·병렬 실행하는 기능은 범위 밖이다.
- 속도 제한은 토큰 버킷 같은 알고리즘 없이 **호출 사이 고정 지연**(설정값, 기본 800ms)으로 충분하다 —
  KRAS/KOREPS 쪽에 문서화된 TPS·쿼터가 없어(서비스 카탈로그·`kras.md` 확인 완료, 근거 없음) 그 이상
  정교하게 설계할 근거가 없다.
- 예약(스케줄) 수집은 기존 `DynamicScheduleManager`의 "데이터셋마다 `ScheduledFuture` 필드 하나씩
  하드코딩" 방식을 복제하지 않는다 — "배치 실행 요청을 몇 시에 한 번 큐에 넣을지"만 스케줄러가
  담당하고 실제 처리는 아래 단일 워커가 한다(새 스케줄 설정 키 1개만 추가).
- 입력 도우미는 지금 있는 `needsBno`/`dependsOn` 메타데이터를 그대로 쓴다 — 새 메타 모델을 만들지
  않는다. "선행 조회 결과 선택"은 이미 승격된 부모 업무 테이블(예: `kras.building_register`)에서
  후보를 조회하는 걸로 충분하다.

## 검토할 실패 조건

1. 배치 도중 서버가 재시작되거나 브라우저가 닫혀도, 재진입 시 남은 항목을 찾아 이어가야 한다(중단
   후 재개) — 워커가 시작할 때마다 `heartbeat_at`가 오래된 채로 RUNNING인 항목을 PLANNED로 되돌리는
   정리 단계가 없으면 영원히 안 끝난 것처럼 보인다.
2. 배치 항목 하나가 실패해도 전체 배치가 멈추면 안 된다 — 실패 항목은 FAILED로 남기고 다음 항목으로
   진행, 배치가 끝나면 실패 목록을 모아 보여준다.
3. 재시도 횟수를 무제한으로 두면 죽은 PNU 하나가 배치를 영원히 못 끝내게 한다 — `attempt_no`에
   상한을 둔다.
4. 속도 제한 지연이 0이면 실수로 운영 KRAS에 짧은 시간에 대량 요청을 보낼 수 있다 — 기본값은 반드시
   0보다 커야 하고, 0으로 설정하려면 명시적인 별도 확인이 있어야 한다.
5. 같은 PNU가 배치 목록에 중복으로 들어가도 같은 item이 중복 생성되면 안 된다 — 기존 유니크 제약
   (`kras.sync_item`의 `run_id,dataset_code,scope_key,window_start,window_end_exclusive,attempt_no`
   유니크)을 그대로 활용해 멱등하게 만든다.

## 작업

### 1. 입력 도우미 + 선행 조회 연결

- [ ] PNU/기간 서비스의 `extraParamsJson` 자유 입력 textarea를, 서비스별로 이미 아는 파라미터 이름
      (예: `bldg_gbn_no`, `cbldg_seqno`, `dong`, `flr`, `ho`, `sil`) 기준의 라벨 붙은 입력 필드로
      바꾼다. 파라미터 이름 목록은 새 메타 모델 없이 `IMPLEMENTED_SERVICES`/`SPEC_SERVICES`에 필드
      하나(`paramNames`)만 추가해 표현한다.
- [ ] `needsBno=true`인 서비스는 PNU 입력 옆에 "선행 조회에서 선택" 드롭다운을 추가한다 — 신규
      엔드포인트 `GET /kras-db/lookup/bldg-gbn-no?pnu=...`가 이미 승격된 `kras.building_register`에서
      후보를 조회해 반환한다(새 테이블 없음, 읽기 전용 조회).
- [ ] `dependsOn`이 있는 서비스는 부모 데이터셋이 실제로 VERIFIED이고 해당 PNU가 이미 승격돼 있는지
      **서버에서** 확인해 막는다(지금은 카드에 경고 문구만 있고 강제하지 않는다 — 이번에 강제로
      바꾼다).
- [ ] (참고, 이번 범위 아님) 부모 item 처리 중 발견한 자식 대상을 자동으로 큐잉하는 기능이 필요해지면
      그때 `kras.sync_work`를 쓴다.

### 2. 신규 경로 전용 예약·다건 수집

- [ ] `KrasPnuIngestService`/`KrasDateRangeIngestService`에 "item 예약"과 "item 채우기"를 분리하는
      메서드를 추가한다: `reserveItem(...)`(PLANNED 상태로 즉시 커밋하는 짧은 트랜잭션)과
      `collectIntoItem(itemId, ...)`(기존 수집 로직 재사용, 실패해도 item 자체는 FAILED로 남도록
      커밋 경계를 item 생성 밖으로 옮긴다).
- [ ] `POST /kras-db/batch/{slug}` — PNU 목록(줄바꿈 구분 텍스트) 또는 기간+분할 일수를 받아
      `kras.sync_run`(`job_kind='MANUAL'`, `triggered_by='BATCH-UI'`) 1건 + 항목별
      `kras.sync_item`(PLANNED) N건을 만들고 즉시 `runId`를 반환한다(무거운 처리는 안 하고 항목
      생성만 한다).
- [ ] 단일 배치 워커(`KrasBatchWorkerService`, `@Async`, 외부 빈에서 호출): 해당 run의 PLANNED 항목을
      하나씩 `collectIntoItem`으로 처리 → 성공 SUCCESS, 실패 FAILED(`error_code`/`error_message`
      기록) → 다음 항목 전 설정된 지연만큼 대기. 처리 시작 시 오래된 RUNNING(`heartbeat_at` 기준)을
      PLANNED로 되돌리는 정리를 먼저 한다(실패 조건 #1).
- [ ] `GET /kras-db/batch/{runId}` — run 상태 + 항목별 진행(PLANNED/RUNNING/SUCCESS/FAILED 개수, 실패
      목록)을 폴링용으로 반환한다. 기존 `krasPollOperation` 패턴과 같은 모양으로 맞춰 프론트를 새로
      만들지 않는다.
- [ ] 예약: `application.yml`에 `kras.batch-schedule`(선택) 하나만 추가해 "매일 이 시각에 마지막으로
      저장해둔 배치 정의를 큐에 넣는다" 정도로 최소화한다 — 데이터셋별 스케줄 필드를 늘리지 않는다.

### 3. 속도 제한 / 재시도 / 재개

- [ ] 호출 사이 고정 지연(`kras.batch.rate-limit-ms`, 기본 800, 0으로 설정 시 경고 로그).
- [ ] `attempt_no` 상한(`kras.batch.max-attempts`, 기본 3) — 초과하면 FAILED로 확정하고 더 재시도
      하지 않는다.
- [ ] 재개: 배치 워커는 "이 run에 PLANNED 또는 오래된 RUNNING이 남아있는가"만 보고 이어간다 — 서버
      재시작, 수동 재실행 모두 같은 진입점을 쓴다.
- [ ] 실패 목록 화면: run 상세에 실패한 scope_key/PNU와 `error_message`를 나열하고, "실패만 재시도"
      버튼으로 해당 항목만 PLANNED로 되돌려 워커를 다시 돌린다.

### 4. 검증 및 마무리

- [ ] `gradlew.bat test` 전체 실행.
- [ ] item 예약/채우기 분리, 배치 run 생성, 순차 워커 진행, 실패 격리, 재시도 상한, 지연 적용을 단위
      테스트로 검증(DB는 테스트 대역 또는 `test` 스키마).
- [ ] Playwright로 배치 제출 → 진행 폴링 → 일부 실패 → 실패만 재시도 흐름을 확인.
- [ ] 구현 결과를 `docs/reference/2026-09-22/kras-integration-phase3-implementation.md`에 기록.

## 순서 요약

1(입력 도우미)은 독립적으로 먼저 끝낼 수 있다. 2(배치)와 3(속도제한/재시도/재개)은 사실상 하나의
작업이라 분리 순서가 의미 없다 — 2의 "item 예약/채우기 분리" 리팩터가 먼저 끝나야 3을 붙일 수 있다는
의존 관계만 있다. 4는 항상 마지막.

**운영 활성화 시점:** 이 계획은 코드까지 완성하되, 배치를 실제 KRAS에 대고 돌리는 건 최소 1개
데이터셋이 VERIFIED된 뒤로 미룬다(권고안 §6). 그 전까지는 목 데이터/`test` 스키마로만 검증한다.
