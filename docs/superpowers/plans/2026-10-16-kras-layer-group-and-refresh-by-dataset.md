# 레이어 적재 묶음 분리 + 서비스별 재조회 주기 구현 계획

**Goal:**
1. 연속지적·용도지역 SHP를 전체 적재(월 1회)에서 떼어 새 묶음 "레이어 적재"로 매일 07:30에 적재한다.
2. PNU 대기열 재조회 주기를 서비스별로 다르게 둔다(소유자 계열 14일, 기본 30일, 총괄표제부 90일, 하루 한도 20만).

**Spec:** `docs/superpowers/specs/2026-10-16/2026-10-16-kras-layer-schedule-design.md` §3·§4(레이어), §8(서비스별 주기).

**Tech Stack:** 기존과 같다. Java 17, Spring Boot, JdbcTemplate, PostgreSQL, Thymeleaf.

**선행 상태(2026-10-16, 미커밋):** 아래가 이미 반영돼 있다. 이 계획은 그 위에서 진행한다.
- `refresh-days` 30
- `rotate-limit` 200,000
- 공시지가 TXT 삭제
- 스케줄 실행이 겹치면 건너뛰지 않고 대기
- 전체 적재 제안 cron 00:00

**범위 밖:**
- 기존 연계(KrasWorker, `/conn`·`/svc`, synchronization 패키지)는 건드리지 않는다(메모리 규칙).
- GeoWebCache 비우기는 YG-Space 과제다.
- YG-Space 조회 필지 즉시 갱신은 하지 않기로 했다(2026-10-16).
- DB 스키마는 바꾸지 않는다. `kras.pnu_work`의 기존 칼럼만으로 된다.

---

## A. 레이어 적재 묶음 분리

### A1. 묶음 정의 — `KrasNextgenGroup`

- `FULL_LOAD`에서 `CADASTRAL`, `USEZONE`을 뺀다. 남는 단계: `LAND_BASIC → PNU_FULL`.
- `LAYER("layer", "레이어 적재", List.of(CADASTRAL, USEZONE))`를 추가한다. 선언 순서는 `FULL_LOAD, CORRECTION, LAYER`로 둔다(화면 카드 순서).
- `replacesPublic()`은 단계 기준이라 수정할 필요가 없다. LAYER는 true, FULL_LOAD는 false가 된다.
- 클래스 주석 "묶음 2개"를 "묶음 3개"로 고친다.

### A2. 설정 — `KrasNextgenSettingsService`

- **`OLD_SCHEDULE_KEYS`에 `"layer", List.of()`를 반드시 추가한다.** `parse()`가 묶음마다 `OLD_SCHEDULE_KEYS.get(g.id)`로 반복하므로, 키가 없으면 NPE가 난다.
- 이전 단계별 키(`cadastral-full`, `usezone-full`)는 지금 `full-load`로 옮겨진다. 이 동작은 그대로 둔다.
  - 그 키가 있던 설정을 `layer`로 옮기지는 않는다. 배포 전 키라서다.
  - `layer`는 꺼진 채로 시작한다(설계 §4.2).
- 클래스 주석 "묶음 2개(full-load, correction)"를 3개로 고친다.
- `src/main/resources/application.yml` `kras-nextgen.schedules`에 아래 줄을 추가한다.
  ```yaml
  layer: ""               # 레이어 적재: 연속지적 SHP → 용도지역 SHP(→ public 자동 승격). 제안 "0 30 7 * * *"(매일 07:30, 보정 적재 07:00 종료 뒤·업무 전)
  ```
- `full-load` 주석에서 "연속지적·용도지역 SHP(→ public 자동 승격) →"를 지운다.

### A3. 스케줄러 — `KrasNextgenScheduler`

- 묶음 enum을 순회하므로 등록·다음 실행·현황판 표시는 자동으로 된다.
- `step()`의 `CADASTRAL`/`USEZONE` 분기도 그대로 쓴다.
- `jobKindFor("0 30 7 * * *")`는 일(day-of-month)이 `*`라서 `DAILY`가 된다. 그대로 맞다.

### A4. 화면·컨트롤러 — `KrasScheduleController`, `kras-schedule.html`

- 카드는 묶음 목록을 그대로 그리므로 3장이 된다. 템플릿에서 묶음 이름을 하드코딩한 곳이 있는지 확인하고 고친다.
- 기존 연계 충돌 안내(`legacyConflict`)를 고친다.
  - 판정 기준을 `FULL_LOAD` → `LAYER` cron으로 바꾼다.
  - 문구를 설계 §4.3대로 바꾼다. "기존 연계도 매일 같은 레이어를 받습니다. 차세대로 전환하면 기존 KRAS 스케줄을 끄세요."
  - `ods.schema`가 `public`일 때만 "같은 테이블을 덮어씁니다"를 덧붙인다. 값은 `runtimeSettings.odsSchema()`로 읽는다(`TargetTableNameService`가 쓰는 것과 같은 값).
- 도움말과 실행 순서 안내에 묶음 2개를 전제한 문구가 있으면 함께 고친다(`grep "묶음\|전체 적재"`).

### A5. 테스트

- `KrasNextgenSchedulerTest.groupRunsEveryStepInOrderAndKeepsGoingAfterAFailedStep`
  - 지금 FULL_LOAD에서 연속지적 실패를 검증한다. 이 검증을 LAYER로 옮긴다: `runGroup(LAYER, false)` → 연속지적 실패 후에도 용도지역을 실행하는지 본다.
  - FULL_LOAD는 `LAND_BASIC`, `PNU_FULL`만 호출하고 `runCadastral`은 `never()`인지 확인한다.
- `KrasNextgenSettingsServiceTest`
  - `containsOnlyKeys("full-load", "correction")`를 `("full-load", "correction", "layer")`로 바꾼다.
  - `layer` 읽기·쓰기 왕복을 확인한다.
  - `layer` 키가 없는 기존 파일은 빈 값(꺼짐)으로 읽히는지 확인한다.
- `KrasScheduleViewTest`
  - `group("layer", "레이어 적재", "0 30 7 * * *", true, …)`를 추가한다.
  - `name="schedule_layer"`와 `data-job="layer"`를 확인한다.
  - 연속지적 단계 버튼(`data-job="cadastral"`)이 레이어 카드 안에 있는지 확인한다.
  - 기존 연계 안내 문구를 바꾼 문구로 확인한다.

---

## B. 서비스별 재조회 주기

### B1. 설정 레코드 — `KrasNextgenSettingsService.Pnu`

- 맨 뒤에 `Map<String, Integer> refreshDaysByDataset` 필드를 추가한다.
- 위치 기반 생성자(이전 10개 인자)를 그대로 받는 보조 생성자를 둔다. 새 필드는 `Map.of()`로 채운다.
  - 기존 호출 10곳을 고치지 않기 위해서다(테스트 7곳, 컨트롤러 1곳, 설정 서비스 2곳).
- `int refreshDays(String dataset)`: 맵에 있으면 그 값, 없으면 `refreshDays`.
- `defaults()`에 권장값을 넣는다(설계 §8.2).
  ```java
  Map.of("land_info", 14, "own_rgt_hist", 14, "shr_ymb", 14, "bldg_hds_info", 14,
         "cbldg_dfhs_info", 14, "bldg_ledg_gen_hds_info", 90)
  ```
- `parsePnu`
  - `refresh-days-by-dataset` 맵을 읽는다. 값이 숫자가 아니면 -1로 두고, 검증에서 걸린다.
  - **키가 없으면 기본 맵(권장값)을 쓴다.** 빈 맵 `{}`를 명시하면 "전부 기본 일수"가 된다.
- 저장(`toMap`)
  - `refresh-days-by-dataset`를 기록한다.
  - 기본 맵과 같으면 키를 생략한다. 기존 "기본과 같으면 빈 목록" 방식과 맞춰서, 기본값이 바뀌면 따라가게 하려는 것이다.
- `validate`
  - 값은 1 이상이어야 한다.
  - 키는 `ALL_DATASETS`에 있고 `PRICE_DATASETS`에는 없어야 한다.
  - 오류 문구: "서비스별 재조회 주기: {코드}는 …".

### B2. 대기열 SQL — `KrasPnuQueueService`

- 서비스별 일수를 jsonb 하나로 넘긴다. 쿼리는 지금처럼 하나로 둔다.
  ```sql
  make_interval(days => coalesce((?::jsonb ->> <dataset_code 칼럼>)::int, ?))
  ```
  - 바인딩: 맵의 JSON 문자열(Jackson `ObjectMapper`, 이미 의존성 있음), 기본 일수.
- `enqueueFull(jdbc, orgCd, datasets, refreshDays)`
  - 시그니처를 `enqueueFull(jdbc, orgCd, datasets, Pnu cfg)` 또는 `(…, int defaultDays, Map<String,Integer> byDataset)`로 바꾼다.
  - 제외 조건 `last_success_at < now() - make_interval(...)`의 일수를 `kras.pnu_work.dataset_code` 기준으로 고른다.
- `enqueueRotation(jdbc, orgCd, datasets, refreshDays, limit)`
  - 대상 조건에 같은 식을 쓴다(칼럼은 `dataset_code`).
  - 정렬을 `ORDER BY last_success_at`에서 `ORDER BY last_success_at + make_interval(days => …)`(마감일 순)로 바꾼다.
- 호출부는 `KrasNextgenScheduler.step()`의 `PNU_FULL`, `PNU_REFRESH` 분기다.
- `enqueueChanged`·`enqueueAnnual`·`enqueueChildren`은 바꾸지 않는다.
- 밀림 안내용 메서드 `long overdue(jdbc, orgCd, datasets, cfg)`를 추가한다. 마감이 지난 DONE 행 수(가격 제외)를 센다.

### B3. 대기열 안내 — `KrasScheduleController.notices`

- `queue.overdue(...) > cfg.rotateLimit()`이면 한 줄 추가한다.
  - "ⓘ 재조회 마감이 지난 N건이 하루 한도 M건보다 많아 주기가 밀립니다. 고급 > 하루 순환 재조회 건수를 늘리거나 주기를 늘리세요."
- 전체 적재 잔량(`fullBacklog > 0`)이 있으면 순환 재조회 자체를 하지 않는다. 그동안은 안내도 띄우지 않는다.

### B4. 화면 — 고급 > PNU 서비스 표

- `pnuDatasetRows`가 행마다 `refreshDays` 값을 넣는다. 서비스별 값이 없으면 빈 칸이고, placeholder로 기본값을 보인다. 가격 서비스는 "연 1회"로 표시한다.
- 표에 "재조회(일)" 칸을 추가한다. `<input type="number" min="1" name="refreshDays_{code}">`를 쓰고, 가격 서비스는 입력칸 대신 글자로 둔다.
- `pnuFrom(params)`이 `refreshDays_*` 파라미터를 읽어 맵을 만든다.
  - 빈 칸은 맵에 넣지 않는다(기본 일수 사용).
  - 결과가 기본 맵과 같으면 B1 규칙대로 키를 생략한다.
- 안내 문구: "비우면 재조회 주기(일) 기본값을 씁니다. 소유자 정보가 들어 있는 서비스는 14일을 권장합니다."

### B5. 테스트

- `KrasNextgenSettingsServiceTest`
  - 기본값에 권장 맵이 들어 있는지
  - 파일에 키가 없으면 기본 맵, `{}`면 빈 맵인지
  - 왕복 저장 시 기본과 같으면 키를 생략하는지
  - 가격 서비스·0·음수·없는 코드가 검증 오류인지
- `KrasPnuQueueDbIntegrationTest`(geosync_it)
  - `last_success_at`이 20일 전인 DONE 행 두 개(land_info=14일, land_bldg_check=30일)를 둔다. 순환 재조회 시 land_info만 대상이어야 한다.
  - limit=1일 때 마감일이 더 많이 지난 행이 먼저 뽑히는지 본다. 예: 90일 서비스 100일 전(10일 초과) vs 14일 서비스 30일 전(16일 초과) → 14일 서비스.
  - `enqueueFull`이 14일 안에 받은 land_info는 건너뛰고, 20일 전에 받은 것은 다시 넣는지 본다.
  - 이 테스트 클래스는 무작위 기관 코드 충돌로 가끔 실패한다(`sameParcelCanBeCollectedAgainOnTheSameDay`). 새 테스트도 같은 `org` 패턴을 쓰고, 자기 행만 센다.
- `KrasScheduleViewTest`: `name="refreshDays_land_info"`, 가격 행의 "연 1회".

---

## C. 진행 순서와 확인

1. A1–A3를 한 뒤 A5 중 스케줄러·설정 테스트를 실행한다.
2. A4와 화면 테스트를 진행한다. `node --check`로 렌더된 스크립트를 검사한다.
3. B1과 설정 테스트를 진행한다.
4. B2와 DB 통합 테스트를 진행한다(`itgradle.py test`).
5. B3–B4와 화면 테스트를 진행한다.
6. 전체 테스트를 돌린다. 개발 서버에서 `/kras-schedule`을 확인한다.
   - 카드 3장이 나오는지
   - 레이어 적재 [지금 실행]이 실행 전 확인(cadastral_file·usezone_file 미승인)에서 막히는지
   - 재조회(일) 저장 → `conf/application.yml`에 반영 → 다시 읽기
   - 개발 서버의 conf는 실제 파일이다. 저장 전후 해당 블록만 바뀌는지 diff로 확인한다.
7. 설계 문서 §11에 반영 결과를 적는다.

**운영 반영 시 사용자 작업:**
- 운영 `conf/application.yml`은 hook으로 수정이 막혀 있다.
  - `kras-nextgen.schedules.layer: "0 30 7 * * *"`은 화면에서 저장하거나 사용자가 직접 넣는다.
  - `refresh-days-by-dataset`은 넣지 않아도 기본 권장값으로 돈다.
- 기존 KRAS 스케줄(`kras.schedule`)을 끈다. 레이어 적재를 켤 때 같이 해야 같은 레이어를 하루 두 번 받지 않는다.
- KRAS 사업단에 일일 SHAPE 생성 완료 시각을 문의한다(설계 §4.1).
