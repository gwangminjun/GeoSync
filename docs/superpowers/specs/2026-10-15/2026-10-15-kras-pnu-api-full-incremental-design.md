# 차세대 PNU 단위 API 데이터 전체·보정 적재 설계

- 작성일: 2026-10-15
- 상태: **구현 완료(미커밋, 2026-10-15)** — 구현 결과·설계와 달라진 점은 §12
- 선행 문서
  - `2026-10-14/2026-10-14-kras-nextgen-schedule-menu-design.md` (차세대 스케줄 메뉴, 구현 완료)
  - `docs/database/2026-10-14/2026-10-14-kras-incremental-sync-rules.md` (토지 증분 규칙 R1–R8)
- 범위: 차세대(kras 스키마) 경로만. 기존 연계(`/conn`·`/svc` 즉시 중계, KrasWorker)는 바꾸지 않는다.

## 1. 배경

기존 연계는 KRAS 14종과 KOREPS 5종, 모두 19종의 서비스를 PNU 단위 API로 제공한다. 이 서비스들은 요청이 오면 바로 중계하고 DB에는 쌓지 않는다.

차세대 전체·증분 적재(스케줄 메뉴)에 들어간 것은 아래뿐이다.
- 연속지적 SHP, 용도지역 SHP
- 공시지가 TXT, 토지기본정보 TXT
- 토지이동내역

나머지 19종은 저장할 준비만 되어 있다.
- `kras.sync_dataset` 등록, 매퍼(응답 해석), stage·업무 테이블이 이미 있다.
- 다만 수집(`KrasPnuIngestService.ingest`)을 부르는 곳은 `/kras-db` 화면의 "PNU 한 건 수동 수집" 버튼뿐이다.

이 문서는 다음 두 가지를 정한다.
- 19종을 기관 전체 필지에 대해 **전체 적재**하고, 그 뒤 바뀐 것만 **보정 적재**하는 구조
- 화면을 "전체 적재 / 보정 적재" 두 묶음으로 단순화하는 것

## 2. 대상 서비스

| 묶음 | dataset_code | 서비스 | 호출 단위 | 업무 테이블 |
|---|---|---|---|---|
| 토지 | land_info | KRAS000002 토지(임야)대장 | PNU | parcel, land_register, land_owner |
| 토지 | shr_ymb | KRAS000003 공유지연명부 | PNU | land_share |
| 토지 | land_mov_hist | KRAS000006 토지이동연혁 | PNU | land_movement_history(+relation) |
| 토지 | own_rgt_hist | KRAS000007 소유권변동연혁 | PNU | land_ownership_history |
| 토지 | land_bldg_check | KRAS000101 토지·건물 존재 | PNU | land_presence |
| 토지이용 | land_use_plan_attr | KRAS000025 | PNU | land_use_attribute |
| 토지이용 | land_use_plan_info | KRAS000026 | PNU | land_use_plan(+restriction) |
| 토지이용 | use_zone | KRAS000027 | PNU | 용도지역 속성 |
| 건물 | bldg_dong_info | KRAS000102 동 목록 | PNU | building_register |
| 건물 | bldg_ledg_gen_hds_info | KRAS000017 총괄표제부 | PNU | building_summary |
| 건물 | bldg_hds_info | KRAS000014 표제부(일반) | PNU+건물번호 | building_title |
| 건물 | cbldg_hds_info | KRAS000015 표제부(집합) | PNU+건물번호 | building_title |
| 건물 | bldg_ho_info | KRAS000103 호 목록 | PNU+건물번호 | building_unit |
| 건물 | cbldg_dfhs_info | KRAS000016 집합 전유부 | PNU+건물번호 | building_exclusive |
| 가격 | land_jiga | KOREPS00011 개별공시지가 | PNU | *_price |
| 가격 | house_info | KOREPS00033 주택가격 | PNU | *_price |
| 가격 | fin_dec_jiga / read_dec_jiga | KOREPS00034/35 확정·열람 공시지가 | PNU | *_price |
| 가격 | land_attr | KOREPS00047 토지특성 | PNU | *_price |

이번 범위에서 빠지는 것: 서비스 ID가 확정되지 않은 데이터셋.
- collective_building, collective_unit, land_right, unit_ownership_history, integrated_building, building_image
- land_owner_change, unit_owner_change (§11·§12 규격서가 §10 복사본)

서비스 ID가 확정되면 같은 큐에 데이터셋만 추가하면 된다(§4).

## 3. 규모와 제약

- 필지 수: 기존 TXT 적재(`public.land_frst_ledg`) 기준 약 25.7만 필지.
- 필지당 호출 수:
  - 토지·토지이용·가격 13건
  - 건물이 있는 필지는 동 목록 1건에, 건물·호마다 추가 호출
- 전체 적재 호출량은 **330만 건 이상**으로 추정한다.
  - 건당 0.3초를 쉬지 않고 돌려도 약 11일이 걸린다.
  - 야간 8시간만 쓰면 약 1개월이 걸린다.
  - 그래서 전체 적재는 **한 번에 끝내는 작업이 아니라 큐에 쌓고 매일 할당량만큼 소진**한다.
- KRAS·KOREPS 호출 한도: **없음**(2026-10-15 사용자 확인). 그래도 게이트웨이 부하를 고려해 동시 호출 수(`parallelism`)와 하루 한도를 설정으로 둔다.
- 예상 소요 시간 (330만 건, 건당 시간 = 게이트웨이 응답 + DB 수집·반영):

  | 건당 시간 | 동시 1 | 동시 4 | 동시 8 |
  |---|---|---|---|
  | 0.1초 | 3.8일 | 23시간 | 11.5시간 |
  | 0.3초 | 11.5일 | 2.9일 | 1.4일 |
  | 0.5초 | 19일 | 4.8일 | 2.4일 |

  - 실제 건당 시간은 아직 모른다. 개발 서버는 게이트웨이(110.20.1.12)에 연결되지 않고, 중계 로그 11건은 모두 21초 연결 실패다. 운영 서버에서 1,000필지 시범 적재로 측정한 뒤 `parallelism`을 정한다.
- **330만 건은 최초 1회 비용이다.**
  - 매월 전체 적재에서 PNU 큐는 `refresh-days` 안에 받은 행을 제외한다. 그래서 매월 330만 건을 다시 부르지 않는다.
  - 평상시 하루 호출량은 330만 ÷ `refresh-days`(90) ≈ **하루 3.7만 건**이다. 건당 0.3초, 동시 1이면 약 3시간이다.
  - 여기에 토지이동 변경 필지 × 약 9건이 더해진다. 기관당 하루 수십~수백 필지 규모다.
  - 파일 4종(SHP 2, TXT 2)은 매월 다시 받는다. 이것은 호출 수가 적어 대기열과 무관하다.
- 저장량:
  - 현재 수집 함수는 PNU·서비스 1건마다 `sync_item` 1행과 stage 행을 만든다.
  - 전체 적재 1회에 sync_item 약 330만 행이 생긴다.
  - SUCCESS 수집 건은 트리거상 지울 수 없으므로, 보존 정책은 별도 과제로 둔다(§9).

## 4. 큐 — 신규 테이블 `kras.pnu_work`

기존 `kras.sync_work`는 쓰지 않는다. 이유는 두 가지다.
- `item_id NOT NULL` FK와 `UNIQUE(item_id, …)` 때문에 "한 수집 건 안의 하위 작업(드릴다운)" 전용이다.
- 날짜를 넘어서 "이 필지·서비스를 마지막으로 언제 받았나"를 담을 수 없다.

신규 테이블은 대기열과 최신성 기록을 한 행으로 겸한다.

```sql
CREATE TABLE kras.pnu_work (
    org_cd          kras.org_code NOT NULL,
    dataset_code    varchar(80)   NOT NULL REFERENCES kras.sync_dataset(dataset_code),
    pnu             char(19)      NOT NULL CHECK (pnu ~ '^\d{19}$'),
    extra_key       varchar(64)   NOT NULL DEFAULT '',          -- 건물번호 등(없으면 '')
    extra_params    jsonb         NOT NULL DEFAULT '{}',
    reason          varchar(10)   NOT NULL CHECK (reason IN ('CHANGE','FULL','ROTATE','ANNUAL','CHILD')),
    status          varchar(10)   NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','RETRY','DONE','FAILED','SKIPPED')),
    attempt_count   integer       NOT NULL DEFAULT 0,
    next_attempt_at timestamptz   NOT NULL DEFAULT now(),
    lease_until     timestamptz,                               -- 처리 중 표시. 지나면 다른 스레드가 다시 가져감
    pending_item_id bigint,                                    -- 수집은 됐지만 아직 반영 전인 sync_item
    queued_at       timestamptz   NOT NULL DEFAULT now(),
    last_success_at timestamptz,
    last_item_id    bigint,
    last_error      text,                                      -- 마스킹된 요약만, 응답 원문 금지
    PRIMARY KEY (org_cd, dataset_code, pnu, extra_key)
);
CREATE INDEX ON kras.pnu_work (org_cd, status, next_attempt_at);
CREATE INDEX ON kras.pnu_work (org_cd, dataset_code, last_success_at);
```

- 같은 필지·서비스는 한 행이다.
  - 다시 넣으면 `ON CONFLICT DO UPDATE`가 status를 PENDING으로 되돌린다.
  - reason은 더 급한 쪽으로 바꾼다.
- 꺼내는 순서는 **CHANGE → CHILD → FULL → ANNUAL → ROTATE**이다. 같은 순위 안에서는 `queued_at` 순이다.
  - 순위는 생성 열 `priority`(reason에서 계산)로 두고 부분 인덱스 `(org_cd, priority, queued_at) WHERE status IN ('PENDING','RETRY')`를 탄다. 330만 행에서 꺼낼 때마다 정렬하지 않기 위해서다.
- 실패하면 RETRY로 바꾸고 `next_attempt_at`을 늘린다(1회 실패 1h, 2회 4h, 3회 1d).
  - 재시도 3번 뒤에도 실패하면(4번째 실패) FAILED로 바꾸고 화면에 표시한다.
- 말소된 필지는 SKIPPED로 바꾼다.
  - 말소 판단: 토지이동내역의 말소, 또는 월 TXT 대조에서 NOT_OBSERVED.
- 신규 설치용 `kras-schema-create.sql`에 반영한다.
- 기존 DB용 마이그레이션 `docs/database/2026-10-15/…-pnu-work.sql`을 함께 둔다.

## 5. 처리기 — `KrasPnuQueueWorker` (신규)

한 번 실행하면 아래를 반복한다.
1. `pnu_work`에서 다음 행을 꺼낸다. 이때 `attempt_count+1`, `lease_until = now()+10분`을 기록하고 바로 커밋한다.
   - 대상: status가 PENDING·RETRY이고, `next_attempt_at <= now()`이고, lease가 없거나 지난 행
   - `FOR UPDATE SKIP LOCKED`로 꺼낸다
2. 데이터셋이 VERIFIED이고 enabled인지 확인한다. 아니면 lease만 풀고 행은 그대로 둔다.
3. `pending_item_id`가 있으면(지난번에 수집까지만 되고 반영 전에 끊긴 경우) 4로 가지 않고 그 item을 반영만 한다.
4. 기존 `KrasPnuIngestService.ingest(…, extraParams, trigger)`를 호출한다. 수집이 커밋되면 `pending_item_id`를 기록한다.
5. 수집 결과가 promotable이면 이어서 `promote`를 호출한다(자동 반영, D-1 결정과 같은 원칙).
6. 행을 DONE으로 바꾼다.
   - `last_success_at`과 `last_item_id`를 기록한다.
   - `pending_item_id`와 `lease_until`을 비운다.
   - `attempt_count`를 0으로 되돌린다.
6. 다음 중 하나가 되면 멈춘다.
   - **하루 호출 한도**(`daily-call-limit`, 당일 누적. 0이면 무제한)
   - **종료 시각**(`stop-at`)
   - 큐가 빔

동작 규칙:
- **동시 호출:** `parallelism`개 스레드가 같은 큐에서 `FOR UPDATE SKIP LOCKED`로 서로 다른 행을 꺼낸다. 필지 단위 advisory lock은 기존 수집 함수가 이미 잡는다.
- **호출 간격:** 스레드마다 호출 사이에 `call-interval-ms`만큼 쉰다(0 허용).
- **연속 실패 차단:**
  - 게이트웨이 연결 실패가 연속 `circuit-break`회(기본 20) 나면 그날 실행을 중단하고 사유를 남긴다.
  - 남은 행은 그대로 두므로 다음 날 이어서 처리한다.
- **실행 기록:**
  - 지금 `findOrCreateTodayRun`은 `job_kind='MANUAL'`로 고정되어 있다. 처리기가 DAILY/MANUAL을 넘길 수 있도록 오버로드를 추가한다.
  - 이 변경은 kras 패키지 안이며 기존 연계에는 영향이 없다.

### 5.1 중단·실패 시 복구

DB 쓰기는 모두 짧은 트랜잭션 단위이다. 끊긴 지점과 상관없이 **다음 실행이 큐에서 그대로 이어 받는다**. 처음부터 다시 하지 않는다.

| 끊긴 지점 | 남는 상태 | 다음 실행의 처리 |
|---|---|---|
| 꺼낸 직후 ~ API 호출 중 (서버 종료, 정전) | 행이 PENDING/RETRY이고 lease가 남음 | 10분 뒤 lease가 지나면 다시 꺼내 처음부터 수집 |
| 수집 트랜잭션 도중 | 수집 함수가 롤백 → stage·sync_item이 남지 않음 | 위와 같음 |
| 수집 커밋 후 ~ 반영 전 | `pending_item_id`가 있음 | **다시 호출하지 않고** 그 item을 반영만 함 |
| 반영 트랜잭션 도중 | 반영 함수가 롤백 → 업무 테이블은 이전 상태 그대로 | `pending_item_id`로 반영 재시도 |
| 반영 커밋 후 ~ DONE 기록 전 | `pending_item_id`가 있으나 이미 반영됨 | 같은 item을 한 번 더 반영. 대상 19종의 반영 방식(A·B·C·D: UPSERT·범위 교체)은 같은 item을 두 번 반영해도 결과가 같다. 추가만 하는 방식(E)인 building_image는 이번 범위 밖이다 |

실패 종류별 처리:

| 실패 | 처리 |
|---|---|
| 게이트웨이 연결 실패·타임아웃·5xx | RETRY. 1시간 → 4시간 → 1일 뒤 재시도. 재시도 3번 뒤에도 실패하면 FAILED. 연속 `circuit-break`회면 그날 실행 중단 |
| 응답 HEADER 오류 코드 | RETRY와 같은 규칙. 오류 코드를 `last_error`에 기록 |
| "자료 없음" 응답(필지에 건물 없음 등) | 설정 `no-data-codes`에 든 응답 코드면 DONE(0행, 반영 없음). 코드 값은 실응답 확인 전이라 기본은 비어 있다 — 그 전까지는 위 RETRY 규칙을 따른다 |
| 응답은 정상인데 필드 검증 경고(promotable=false) | FAILED로 표시하고 반영하지 않음. 사람이 `/kras-db`에서 확인한다. 매퍼 수정 후 "실패 재시도" 버튼으로 다시 PENDING |
| 데이터셋이 UNVERIFIED·비활성 | 행을 건드리지 않고 건너뜀 |

- **재시도 시점:** 실패한 행은 바로 다시 부르지 않는다. 정한 시각(lease 10분 / RETRY 1h·4h·1d)이 지난 뒤 꺼낼 수 있게 된다.
  - 그때 처리기가 돌고 있으면 같은 실행 안에서 다시 꺼낸다. 최초 적재처럼 실행이 오래가는 경우가 여기에 해당한다.
  - 처리기가 이미 끝났거나 서버가 내려가 있었으면 **다음 보정 적재 실행**에서 꺼낸다. 기본은 다음 날 01:00이고, "지금 실행"을 누르면 바로 꺼낸다.
  - 처리기는 시각이 지나 꺼낼 수 있는 행이 없으면 끝낸다. 남은 RETRY 행을 기다리며 대기하지 않는다.
- 서버가 재시작돼 끝나지 못한 실행은 실행 기록(`kras.ui_operation_log`, dataset `pnu_queue`, action `QUEUE`)에 RUNNING으로 남는다. 화면은 이것을 "중단"으로 안내한다(§12 참고).
- **대기열 등록은 몇 번을 다시 해도 같은 결과다.** `ON CONFLICT`로 처리하므로, 전체 적재를 도중에 다시 눌러도 행이 중복되지 않는다.
- 화면의 "실패 재시도"는 FAILED 행을 PENDING으로 되돌린다. 이때 `attempt_count`를 0으로 바꾼다.

**건물 드릴다운(CHILD):**
1. ~~`land_bldg_check`가 "건물 없음"이면 건물 계열 행을 SKIPPED~~ → 구현하지 않음(§12). 건물 하위 조회는 동 목록 응답에 나온 건물만 넣으므로, 건물 없는 필지는 하위 행이 생기지 않는다.
2. `bldg_dong_info` 응답에 건물마다 `BLDG_GBN_NO`와 `BLDG_KIND_CD`가 있다(샘플: `mock/sample/daejang/BLDG_DONG_LIST.XML`). 종류별로 하위 행을 CHILD로 넣는다.
   - 1 총괄: 추가 없음(017은 PNU 단위로 이미 대상)
   - 2 일반: 014
   - 집합: 015와 103 → 103 응답의 호마다 016
3. **확인 필요:** 집합 건물의 종류 코드 값과 016의 요청 파라미터. 실응답으로 확인하기 전에는 014/015만 켜고 103/016은 설정에서 끈다.

## 6. 무엇을 언제 큐에 넣나

| 신호 | 넣는 대상 | reason |
|---|---|---|
| 전체 적재 실행 | `kras.parcel`의 현재 필지 × 켜진 PNU 데이터셋 전부. 이미 `refresh-days` 안에 받은 행은 제외 | FULL |
| 토지이동내역 반영(일일) | 그 이벤트에 나온 PNU(신·구 지번 모두) × 토지·토지이용·건물존재 계열 | CHANGE |
| 월 TXT 대조에서 새로 생긴 필지 | 그 PNU × 전부 | CHANGE |
| 순환 재조회(보정 적재 실행 시) | `last_success_at`이 `refresh-days`보다 오래된 행, 오래된 순으로 하루 `rotate-limit`건 | ROTATE |
| 가격 공시 후(연 1회) | `price-refresh-after`(기본 `06-01`) 이후 아직 받지 않은 KOREPS 계열 행 | ANNUAL |

- 소유자 정보에는 변경 신호가 없다. 해당 데이터는 토지대장 소유자, 공유지, 소유권연혁이다.
  - 소유권변경내역(§11) 서비스 ID가 확정되면 CHANGE 신호로 추가한다.
  - 그 전에는 순환 재조회에 맡긴다.
- 건물과 토지이용계획에도 변경 피드가 없으므로 순환 재조회에 맡긴다.
- **FULL 잔량이 남아 있는 동안에는 ROTATE를 넣지 않는다.** 초기 적재가 먼저 끝나야 한다.

## 7. 화면 — "전체 적재 / 보정 적재" 두 묶음

`/kras-schedule`의 작업 5개 개별 스케줄을 **스케줄 2개**로 바꾼다.

```
┌ 전체 적재 ───────────────────────────── [지금 실행] ┐
│ 스케줄: (매월 1일 01:00 ▼)   다음 실행: 2026-11-01 01:00      │
│ 실행 내용                                                    │
│  1. 토지기본정보 TXT  — 기준선이 없으면 기준선, 있으면 전체 대조 │
│  2. 연속지적 SHP → public 반영                               │
│  3. 용도지역 SHP → public 반영                               │
│  4. 공시지가 TXT                                             │
│  5. PNU 서비스 전체 적재 대기열 등록 (필지 × 켜진 서비스)      │
│ 마지막 결과: …                                               │
└─────────────────────────────────────────────────────────────┘
┌ 보정 적재 ───────────────────────────── [지금 실행] ┐
│ 스케줄: (매일 01:00 ▼)   종료 시각: 07:00                     │
│ 실행 내용                                                    │
│  1. 토지이동내역 수집 → 바뀐 필지 대기열 등록                   │
│  2. 순환 재조회·연 1회 가격 갱신 대기열 등록                     │
│  3. 대기열 처리 (동시 8, 하루 한도 없음)               │
│ 상태: ● 처리 중 (09:12 시작, 스레드 8/8)  또는 ○ 대기 / ✕ 중단  │
│ 전체 적재 진행률: 71% (예상 완료 2026-11-02)                   │
└─────────────────────────────────────────────────────────────┘
(아래 §7.1 대기열 현황 패널)
▸ 고급 (접힘): 서비스별 켜기/끄기, 단계별 개별 실행 버튼, 배포 설정(서비스 ID 등)
```

- 각 묶음은 단계를 순서대로 실행한다.
  - 한 단계가 막히면(검증 미완료, 응답 실패 등) 사유를 남기고 다음 단계로 넘어간다.
  - 다만 SHP 수집이 실패하면 그 SHP의 public 반영은 하지 않는다. 이는 기존 규칙과 같다.
- 두 묶음은 기존 스케줄러의 단일 잠금을 공유하므로 동시에 돌지 않는다.
  - 전체 적재가 도는 동안 보정 적재 시각이 오면 그 회차는 건너뛰고 사유를 남긴다.
- public을 덮어쓰는 단계(2·3)가 있으므로, 전체 적재 스케줄을 켤 때 기존과 같은 확인 창을 띄운다.
- 서비스별 켜기/끄기의 기본값은 VERIFIED이면서 enabled인 데이터셋이다. 끄면 큐에 넣지 않고 처리도 건너뛴다.

### 7.1 대기열 현황 패널

재시도·중단·실패가 언제 어떻게 처리되는지(§5.1)를 화면에서 바로 알 수 있게 한다.

```
┌ 대기열 현황 ─────────────────────────────── 갱신 09:41 [새로고침] ┐
│ 대기 183,240 │ 처리 중 8 │ 재시도 대기 12 │ 실패 3 │ 제외 1,021 │ 완료 2,104,330 │
│                                                                    │
│ ⓘ 처리기가 끝난 뒤 남은 재시도는 다음 보정 적재(2026-10-16 01:00)에서  │
│   처리됩니다. 바로 처리하려면 [지금 실행]을 누르세요.                  │
│ ⚠ 마지막 실행이 중단됐습니다: 2026-10-15 03:22 게이트웨이 연속 실패 20회 │
│   → 남은 대기열은 다음 실행에서 이어서 처리합니다.                     │
│                                                                    │
│ 서비스별   대기     재시도  실패  완료       마지막 성공               │
│ land_info  12,030   2       0     244,800    2026-10-15 09:40          │
│ bldg_hds…  30,112   9       3     81,220     2026-10-15 09:41          │
│ …                                                                  │
│                                                                    │
│ ▸ 재시도 대기 (12)                                                   │
│   PNU                  서비스      시도  다음 시도         사유        │
│   1283025021100010000  land_info   1/3   10:38 (57분 뒤)   연결 시간 초과 │
│   …                                                                │
│ ▸ 실패 (3)                                     [선택 재시도] [전체 재시도] │
│   PNU                  서비스      시도  마지막 시도       사유        │
│   1283025625111190010  bldg_hds…   3/3   10-15 08:10       필드 검증 경고: GAREA │
│ ▸ 처리 중 (8)  — 10분 넘게 끝나지 않은 행은 "멈춤 의심"으로 표시       │
└────────────────────────────────────────────────────────────────────┘
```

- **숫자 칸:** `pnu_work`를 status별로 센다.
  - 처리 중 = lease가 아직 유효한 PENDING/RETRY 행
  - 재시도 대기 = RETRY이고 `next_attempt_at`이 아직 오지 않은 행
- **안내 문구:** 상황에 따라 한 줄을 보여준다.
  - 처리기가 끝났는데 재시도 대기 행이 남아 있으면, 다음 보정 적재 시각을 안내한다.
  - 마지막 실행이 연속 실패 차단이나 서버 재시작(INTERRUPTED)으로 끝났으면, 그 시각과 사유를 안내한다.
  - lease가 지났는데 아직 PENDING/RETRY인 행(끊긴 행)이 있으면 "N건이 중단되어 다음 실행에서 다시 시도합니다"라고 보여준다.
- **재시도 대기 목록:** 다음 시도 시각을 절대 시각과 남은 시간으로 함께 보여준다. 시도 횟수는 n/3이다.
- **실패 목록:**
  - 사유는 `last_error`(마스킹된 요약)이다.
  - "선택 재시도"·"전체 재시도"는 PENDING으로 되돌리고 `attempt_count`를 0으로 한다. 처리는 다음 실행 또는 "지금 실행"에서 한다.
  - 필드 검증 경고 행은 PNU를 누르면 `/kras-db`의 해당 데이터셋 수집 이력 팝업을 연다.
- **목록 크기:** 각 목록은 최근 50건만 보여주고, PNU·서비스로 검색한다. 수십만 행을 한 번에 그리지 않는다.
- **진행률:** FULL로 넣은 행 중 DONE·SKIPPED의 비율이다. 예상 완료 시각은 최근 1시간 처리 속도로 계산한다.
- **갱신 주기:** 처리 중일 때만 30초마다 자동으로 새로 고친다(`GET /kras-schedule/status` 확장).
- 개인정보: 목록에는 PNU·서비스·상태·오류 요약만 보여주고 소유자 등 응답 내용은 보여주지 않는다.

엔드포인트 추가:

| 메서드·경로 | 용도 |
|---|---|
| `GET /kras-schedule/queue` | 숫자 칸, 서비스별 표, 안내 문구 (JSON) |
| `GET /kras-schedule/queue/rows?status=RETRY\|FAILED\|RUNNING&q=` | 목록(최근 50건, 검색) |
| `POST /kras-schedule/queue/retry` | 실패 행 재시도. 본문: 선택 키 목록 또는 `all` |

### 7.2 도움말 팝업

동작 설명은 첫 화면에 펼쳐 두지 않는다. 해당 영역 제목 옆에 작은 버튼 `ⓘ 끊겼을 때 어떻게 되나`를 두고, 누르면 팝업으로 보여준다.
- 화면의 기존 팝업(`/kras-db` 데이터셋 팝업)과 같은 방식으로 만든다.
- 내용은 서버 호출 없이 템플릿에 들어 있는 정적 설명이다.
- 도움말은 앞으로 더 늘어날 수 있다(사용자가 추후 추가 예정). 그래서 **팝업 1개 = 도움말 1항목** 구조로 두고, 항목을 추가하면 버튼이 하나 늘어나게 한다.

| # | 버튼 문구 | 버튼 위치 | 팝업 내용 |
|---|---|---|---|
| 1 | ⓘ 끊겼을 때 어떻게 되나 | 대기열 현황 패널(§7.1) 제목 옆 | 아래 1번 |
| (추가 예정) | | | |

**도움말 1 — 끊겼을 때 어떻게 되나** (팝업 문구, 운영자용 쉬운 말)

> 적재가 중간에 끊겨도 처음부터 다시 하지 않습니다. 대기열에 남은 것부터 이어서 적재합니다.
>
> | 끊긴 때 | 어떻게 되나 |
> |---|---|
> | 조회 도중 서버가 꺼짐 | 10분 뒤 다시 조회합니다 |
> | 저장 도중 끊김 | 반쯤 저장된 데이터는 남지 않고, 다시 조회합니다 |
> | 저장은 됐고 반영 전에 끊김 | 다시 조회하지 않고 반영만 합니다 |
> | 반영 도중 끊김 | 기존 데이터는 그대로이고, 반영만 다시 합니다 |
>
> **오류가 나면**
> - 연결 실패 등은 1시간 → 4시간 → 1일 뒤 다시 시도하고, 3번 실패하면 '실패'로 둡니다.
> - 연속 20번 실패하면 게이트웨이 장애로 보고 그날은 멈춥니다.
> - '자료 없음' 응답은 실패가 아니라 완료(0건)입니다.
> - 필드 검증 경고는 '실패'로 두고 반영하지 않습니다. 확인한 뒤 [재시도]를 누르세요.
>
> **언제 다시 하나**
> - 처리기가 돌고 있으면 시간이 되는 대로 그 안에서 다시 시도합니다.
> - 이미 끝났거나 서버가 꺼져 있었으면 다음 보정 적재(매일 01:00)에서 다시 시도합니다.
> - 바로 하려면 [지금 실행]을 누르세요.

- 팝업 안의 시간 값(10분, 1h·4h·1d, 3회, 20회, 01:00)은 고정 문구로 쓰지 않는다. 현재 설정값을 넣어서 보여준다. 설정을 바꿔도 설명이 틀리지 않게 하기 위해서다.

## 8. 설정 (`conf/application.yml`의 `kras-nextgen` 블록)

```yaml
kras-nextgen:
  schedules:
    full-load: "0 0 1 1 * *"      # 전체 적재 (비우면 끔)
    correction: "0 0 1 * * *"     # 보정 적재 (비우면 끔)
  land-change:                    # 기존 그대로
    service-id: ""
  pnu:
    datasets: []                  # 비우면 VERIFIED+enabled PNU 데이터셋 전부
    parallelism: 8                # 동시 호출 수 (2026-10-15 확정, 시범 측정 후 조정 가능)
    daily-call-limit: 0           # 0 = 무제한
    call-interval-ms: 0
    stop-at: "07:00"
    circuit-break: 20
    refresh-days: 90
    rotate-limit: 40000           # 330만 ÷ 90일 ≈ 3.7만 — 90일 주기를 지키려면 이 이상
    price-refresh-after: "06-01"
```

- 저장할 때는 기존과 같이 `kras-nextgen` 블록만 바꾼다.
- 기존 개별 키(`schedules.land-change-daily` 등 5개)는 이렇게 처리한다.
  - 읽을 때 옮긴다. 일일 토지이동 키가 있으면 `correction`으로, 연속지적 전체 키가 있으면 `full-load`로 옮긴다.
  - 저장할 때 지운다.
- 값의 범위를 검증한다. 예: 호출 간격 ≥ 50ms, 하루 한도 > 0, 종료 시각 형식.

## 9. 범위 밖 / 후속

- **`/conn`·`/svc`를 저장 데이터로 응답:** 2026-09-18 설계 §10에 해당한다. 기존 연계를 종료할 때 별도로 결정한다.
- **보존 정책:** sync_item·stage 행이 크게 늘어나는 문제는 별도 과제다. 예: 오래된 stage 행 정리. SUCCESS 수집 건을 지우지 못하게 막는 트리거와 함께 검토한다.
- **서비스 ID 미확정 데이터셋:** 확정되면 `pnu.datasets`에 추가만 하면 된다.

## 10. 결정 필요

1. ~~KRAS·KOREPS 호출 한도~~ → **없음**(2026-10-15 확정). 남은 것: 운영 서버 시범 적재로 건당 시간을 측정하고 `parallelism`을 정하는 것.
2. **건물 종류 코드와 016 요청 파라미터:** 실응답으로 확인해야 한다. 확인 전에는 103/016을 끈다.
3. **소유권변경내역(§11) 서비스 ID:** KRAS 담당자에게 확인해야 한다.
4. **전체 적재 주기:** 기본은 매월로 제안한다. 파일 4종은 매월 돌리지만, PNU 큐는 `refresh-days` 안에 받은 행을 빼므로 실제 재호출은 오래된 행만 일어난다.

## 11. 구현 순서 (승인 후)

1. `kras.pnu_work`를 추가한다(create.sql 반영, 마이그레이션, 통합 테스트 DB 적용).
2. `KrasPnuQueueWorker`를 만든다.
   - 꺼내기, 수집·반영, 한도·종료시각·차단, 재시도 처리
   - 실 DB 통합 테스트로 확인한다(geosync_it).
3. 대기열 등록을 구현한다.
   - FULL, CHANGE(토지이동내역 반영 후 훅), ROTATE, ANNUAL, CHILD(동 목록 응답)
4. 스케줄러를 두 묶음(`FULL_LOAD`, `CORRECTION`)으로 바꾸고 설정 키를 옮긴다.
5. 화면을 두 묶음으로 바꾸고 대기열 현황 패널(§7.1), 도움말 팝업(§7.2), 고급 영역을 넣는다.
6. 문서를 갱신한다(적재 현황, 증분 규칙).

## 12. 구현 결과 (2026-10-15)

### 12.1 파일

| 구분 | 파일 |
|---|---|
| DB | `docs/database/2026-10-15/2026-10-15-kras-schema-migration-pnu-work.sql` (신규), `docs/database/2026-09-18/kras-schema-create.sql` (끝에 같은 내용 추가) |
| 대기열 | `KrasPnuQueueService` (등록·꺼내기·결과·현황 SQL), `KrasPnuQueueWorker` (처리기) — 신규 |
| 스케줄 | `KrasNextgenGroup` (신규, 묶음 2개), `KrasNextgenJob` (단계 8개로 변경), `KrasNextgenScheduler`, `KrasNextgenSettingsService` (`pnu` 블록, 이전 키 옮김) |
| 수집 | `KrasPnuIngestService` — job_kind 오버로드, HEADER CODE 검사, 같은 날 재수집 `attempt_no` |
| 화면 | `KrasScheduleController`, `templates/kras-schedule.html` (두 묶음·대기열 패널·도움말 팝업·고급), `templates/kras-db.html` (`?hist=&item=` 수집 이력 팝업 바로 열기) |
| 설정 | `src/main/resources/application.yml` 의 `kras-nextgen` 템플릿 |
| 테스트 | `KrasPnuQueueDbIntegrationTest` (실 DB 6개, 신규), `KrasNextgenSettingsServiceTest`, `KrasNextgenSchedulerTest`, `KrasScheduleViewTest` (갱신) |

### 12.2 설계와 달라진 점

- **HEADER CODE ≠ 0000 응답은 수집하지 않는다(수동 `/kras-db` 수집도 같음).** 오류 응답을 0건으로 받아 반영하면 범위 교체(B) 테이블의 기존 행이 지워지기 때문이다. 수집 트랜잭션이 롤백되고, 대기열은 RETRY로 처리한다. "자료 없음" 코드는 `no-data-codes`로 지정한다.
- **같은 날 같은 PNU 재수집 결함을 고쳤다.** 실 DB 확인 중 재시도 행이 `sync_item` UNIQUE(run_id, dataset_code, scope_key, window_start, window_end_exclusive, attempt_no) 위반으로 실패했다. 지금은 같은 run 안에서 `attempt_no`를 하나씩 늘린다. 수동 `/kras-db` 수집도 같은 PNU를 하루에 두 번 받을 때 이 오류가 났었다.
- **"건물 없음" SKIPPED는 넣지 않았다.** 토지·건물 존재 응답(`REAL_GBN`)의 값 체계가 확인되지 않았다. 동 목록에 나온 건물만 하위 행으로 넣으므로 결과는 같다.
- **건물 종류 코드:** 1=총괄(추가 없음), 2=일반(014), 그 외=집합(015+103). 103 반영 뒤 호마다 016이다. 집합 코드 값은 실응답으로 확인해야 하고, 103·016은 기본으로 꺼져 있다.
- **중단 표시:** `sync_run` INTERRUPTED 대신 대기열 실행 기록(`ui_operation_log`의 RUNNING·FAILED)과 lease가 지난 행 수로 안내한다. PNU 수집은 `sync_run` 상태를 쓰지 않기 때문이다.
- **스케줄 스레드 2개:** 한 묶음이 오래 돌 때 다른 묶음 시각에 바로 "건너뜀"을 남기기 위해서다. 스레드가 1개면 늦게 실행돼 버린다.
- **재시도하는 오류와 바로 실패로 두는 오류를 나눴다(2026-10-15 mock 데이터 적재에서 발견).**
  - 재시도·연속 실패 차단 대상: 게이트웨이 호출 실패·오류 코드, DB 연결 끊김 같은 일시 오류.
  - 바로 FAILED(재시도 안 함, 차단 횟수에 안 셈): 저장 제약 위반·매핑·반영 오류. 다시 불러도 결과가 같아서다. 이전에는 이런 오류 20건에 "게이트웨이 연속 실패"로 실행이 멈췄다.
  - 오류 요약(`last_error`)은 SQL 전문 대신 단계 + 가장 안쪽 원인 메시지로 남긴다(500자에서 잘려 원인이 안 보이던 문제).
- **mock 데이터 전 서비스 적재에서 찾아 고친 기존 차세대 결함(수동 `/kras-db` 반영에도 해당):**
  - `kras.business_dataset`에 (parcel, shr_ymb)·(parcel, own_rgt_hist)·(parcel, collective_building)이 없어 반영이 트리거에 막혔다 → `docs/database/2026-10-15/2026-10-15-kras-schema-migration-parcel-business-dataset.sql`, create.sql 반영. 매퍼·권한 대조 테스트 `KrasMapperBusinessGrantDbIntegrationTest` 추가.
  - 선언형 매퍼가 고정값 칼럼(`request_key`)을 응답 태그(없음)로 덮어 NULL이 됐다(land_use_plan_info, cbldg_dfhs_info) → `KrasSpecMapper` 수정, `KrasSpecMapperConstantsTest`.
  - 자식 테이블 PK가 GENERATED ALWAYS인데 순번 값을 넣었다(land_use_restriction, building_floor·title_owner·title_change, building_exclusive_area·owner·price) → 순번 컬럼 null이면 넣지 않음.
  - 건물 표제부(014/015)·집합 전유부(016)가 부모를 PNU만으로 찾아, 건물이 여러 동인 필지에서 "expected 1, actual 2"로 실패 → PNU + 요청한 건물번호로 찾는다.
  - mock 템플릿 `KRAS000002.xml`의 축척 `SCALE`이 1200(실제 샘플은 12)이라 varchar(2)를 넘었다 → 샘플 값으로 수정(개발용 mock만).
- **parcel을 함께 쓰는 서비스는 같은 필지에서 한 번에 하나씩(개발 DB 동시 8 확인에서 발견).**
  - 대상: land_info·shr_ymb·own_rgt_hist·land_bldg_check·collective_building. 매퍼 선언에서 자동으로 고른다.
  - 증상: 동시에 처리하면 먼저 수집한 건이 나중에 반영되고, guard_business_row가 "Older collection cannot overwrite newer business data"로 막았다.
  - 지금: 이 서비스들은 필지별 줄무늬 락(JVM, 서버 1대 전제) 안에서 수집→반영한다. 다른 서비스는 그대로 병렬이다.
  - 회귀 테스트 `parcelWritingServicesDoNotOverwriteEachOtherOutOfOrderUnderParallelism`.
- **미해결(기존 2026-09-18 구조):** land_use_plan_info의 범위 교체(B)는 기존 land_use_plan 행을 지운다. 그런데 `land_use_plan_asset`이 그 행을 참조하면 FK로 실패한다. 지금은 개발 DB 더미 필지에서만 난다. 첨부를 채우는 경로가 생기기 전에 교체 방식(자식 먼저 정리 또는 신원 매칭)을 정해야 한다.
- **하루 호출 한도 카운터는 메모리에 있다.** 재시작하면 그날 0부터 센다. 기본값이 0(무제한)이라 영향이 없다.

### 12.3 확인

- 전체 테스트 133개 중 실패 1건이다. 예전부터 있던 무관한 `DatabaseDefaultsTest`이다. 원격 테스트 DB(`geosync_it`) 통합 테스트를 포함한다.
- 실서버 확인: 앱을 `geosync_it` + 앱 안의 mock 게이트웨이(`/mock/estateGateway`)로 띄웠다(설정 사본, 포트 8080).
  - 전체 대기열 등록 450건 → 대기열 처리(동시 8) 60건 완료, 건물 하위 행 등록.
  - 처리 도중 끊긴 행 2·재시도 대기 1·실패 1을 만든 뒤 패널 숫자·안내·목록이 맞게 나왔다. 보정 적재 실행 → 재시도 버튼 → 다시 처리에서 4건 모두 완료됐다. 이 과정에서 위 attempt_no 결함을 찾아 고쳤다.
  - 설정 저장: 잘못된 값은 거부되고 파일이 그대로였다. 정상 저장은 `kras-nextgen` 블록만 바꾸고(이전 단계별 키 제거), 다른 블록은 보존했다. 매분 cron으로 보정 적재가 스케줄 실행되는 것을 확인했다.
  - 개발 서버(개발 DB, 18080): `pnu_work` 테이블이 없어서 대기열 패널이 "마이그레이션을 적용하세요"를 표시한다. 기존 연계(`/conn`) 코드는 바뀌지 않았다.
- 배포 전: 운영 DB에 2026-10-15 마이그레이션을 적용해야 한다(2026-10-07·10-14 마이그레이션과 함께).
