# KRAS 연계 관리 2차 구현 계획

**Goal:** 검증·수집 카드 31개가 한 페이지에 나열된 `/kras-db`를 분류·검색·상세 화면 중심으로 재구성하고, 검증 버튼을 누른 근거(응답 샘플·담당자·매퍼 버전)를 기록하며, 업무 반영 직전에 건수 변화를 미리 보여준다.

**Architecture:** 1차가 세운 원칙을 그대로 잇는다 — 기존 수집/승격 서비스, DB 가드, `kras.ui_operation_log`는 그대로 재사용하고 새 테이블 하나만 더 추가한다(`kras.ui_verification_evidence`). 화면은 카드 나열 대신 목록(왼쪽) + 상세(오른쪽) 레이아웃으로 바꾸되, 실행 버튼 자체(수집/승격/검증전환)는 지금 카드에 있는 것을 그대로 상세 패널로 옮긴다 — 버튼 동작 로직은 재작성하지 않는다.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, PostgreSQL, Thymeleaf, JavaScript(`kras-history.js` 확장).

**Spec:** `docs/reference/2026-09-22/kras-integration-improvement-recommendations.md`의 2차 항목 2·3 (항목 1 비동기 실행은 완료, 커밋 `0d21d18`).

**선행 완료 상태(2026-09-22 기준):** 1차(실행 이력·반영 이어하기·사전 점검) + 2차-1(비동기 실행) 완료. 서비스 데이터셋 31개 구현, 검증(VERIFIED)된 것은 0개 — 실 연계가 아직 안 붙어 `/api-test`로 응답을 확인할 수 없는 상태다.

## 범위와 결정

- 이 계획은 2차 항목 2(목록·상세 화면)와 3(검증 근거 + 반영 전 비교)만 다룬다. 3차(자동화)는 검증된 데이터셋이 하나도 없는 지금 시점에 설계해도 착수할 수 없어 범위 밖이다.
- 목록·상세 개편은 **기존 카드의 HTML을 지우고 다시 만들지 않는다** — 카드 31개 각각의 버튼·입력 필드·JS 함수(`runPnuIngest`, `runTxtAction`, `runUsezoneAction` 등)는 그대로 두고, 그걸 감싸는 **바깥 레이아웃**(목록 필터 → 클릭 시 해당 카드만 보이기)만 새로 만든다. 버튼 동작을 다시 짜면 지금까지 검증된 31개 데이터셋의 회귀 위험이 커진다.
- 검증 근거는 `kras.sync_dataset`을 확장하지 않는다(운영 데이터 스키마 변경 최소화 원칙, `kras.ui_operation_log`와 같은 선례). 별도 테이블 `kras.ui_verification_evidence`를 새로 추가하고 최초 저장 시 없으면 생성한다.
- 반영 전 비교는 데이터셋마다 반영 방식(UPSERT/범위 교체/추가 전용)이 달라 **공통 로직 하나로 만들지 않는다** — `KrasStagePromotionService.StagePromotionSpec.pattern()`으로 이미 패턴이 선언돼 있으니 그 값을 그대로 분기 기준으로 쓴다.
- 공간 데이터(연속지적·용도지역)의 public 반영 전 비교는 이미 있는 건수·급감 차단 로직(`kras-schema-public-sync.sql`)을 그대로 보여주기만 한다 — 새 검사 로직을 만들지 않는다.

## 검토할 실패 조건

1. 분류/검색으로 카드를 찾았는데 클릭해도 기존 카드가 정확히 대응하지 않아야 한다(슬러그 매핑 누락).
2. 검증 근거를 기록하는 도중 실패해도 검증 전환(VERIFIED) 자체는 막히지 않아야 한다(1차의 "로그 실패는 데이터 작업 실패로 안내하지 않는다" 원칙 재사용) — 단, 근거 없이 전환됐다는 사실은 화면에 남아야 한다.
3. 반영 전 비교의 "현재 건수" 조회가 대상 DB 접속 실패로 죽어도 반영 버튼 자체는 (서버 재검증을 거쳐) 여전히 동작해야 한다 — 비교는 참고 정보이지 게이트가 아니다.
4. 목록 필터가 검증 상태별로 걸려 있을 때 새로고침해도 필터가 유지돼야 한다(사용성 회귀 방지).
5. 검증 근거의 응답 샘플에 개인정보(소유자명·주소 등)가 그대로 남을 수 있다 — 저장 전 마스킹 여부를 결정해야 한다(§4.2에서 다룸, 미확정 항목).

## 작업

### 1. 목록·상세 화면 재구성

- [x] `/kras-db` 상단에 검색·상태 필터가 달린 연계 목록을 추가. "선택한 카드만 보이는 상세"는 사이드 패널이 아니라 **팝업(모달)** 으로 구현했고, 데이터셋 카드는 **기본적으로 숨겨져 있다가**(`kras-hide` 클래스) 팝업을 열 때만 보인다. 시도 순서: ①스크롤 이동+강조(보기 힘들다는 피드백) → ②카드는 그대로 두고 팝업 추가(카드가 여전히 기본 화면에 다 보여서 스크롤 문제가 안 풀림) → ③카드 기본 숨김+팝업. 카드를 복제하지 않고 실제 DOM 노드를 모달로 옮기므로(주석 노드로 원래 자리 표시) 카드 내부 id 참조 버튼/폼이 그대로 동작하고, 닫으면 원래 자리로 돌아가며 다시 숨겨진다. 카드 2~4개가 한 데이터셋인 경우(`data-companion-of`)는 함께 옮긴다. 옛 스크롤+강조 코드(①)는 삭제하지 않고 주석으로 남겨뒀다.
- [x] 분류 기준: 토지/건물/공간(SHP)/가격/전체TXT/기간조회 6개 그룹. 기존 레지스트리(`IMPLEMENTED_SERVICES`, `DATE_RANGE_SERVICES`, `SPEC_SERVICES`)에 `group` 필드 추가 + 매퍼 없는 5개 고정 데이터셋용 `FIXED_DATASETS` 레지스트리 신설.
- [x] 검색: dataset_code/라벨 부분 일치, 클라이언트 JS 필터(서버 호출 없음).
- [x] 상태 필터 — 계획보다 범위를 줄여 VERIFIED/UNVERIFIED 두 값만 구현했다("실행 중"/"실패 이력 있음" 필터는 하지 않음, 후속 과제로 남김).
- [ ] `th:fragment` 카드 재사용은 하지 않았다 — Thymeleaf 조각 대신 JS로 실제 DOM 노드를 모달로 옮기는 방식을 썼다(접근 방식이 계획과 달라짐, 이유는 위 항목 참고).
- [x] 화면 렌더링 테스트: 기존 `KrasIntegrationViewTest`에 사이드바·앵커 검증 추가 + 신규 `kras-nav.browser.cjs`로 검색/필터/팝업 열기·닫기(백드롭 클릭 포함)·companion 카드 동반 이동을 브라우저에서 확인.

### 2. 검증 근거 관리

- [x] 신규 테이블 `kras.ui_verification_evidence`(최초 저장 시 자동 생성): `evidence_id, org_cd, dataset_code, verified_at, verified_by, mapper_version, response_sample, note`. `response_sample`은 4000자로 자른다. `mapper_version`은 계획대로 수동 입력.
- [x] `KrasSchemaController.verifyDataset()`에 응답 샘플·담당자·매퍼 버전·비고 입력 추가. VERIFIED 전환 UPDATE와 별개 try/catch로 근거를 저장해 **근거 저장 실패가 검증 전환을 막지 않는다**(실패 조건 #2 충족, 테스트로 확인).
- [x] 데이터셋별 최근 근거 조회(`KrasVerificationEvidenceService.latestByDataset`) 추가. 배지는 계획과 달리 상시 노출 칩이 아니라 목록 항목의 `title` 툴팁("마지막 검증: {일시}·{담당자}")으로 구현했다 — 항상 보이는 배지 UI는 후속 과제.
- [x] 응답 샘플은 저장 전 서버에서 자동 마스킹(`OWNER_NM`/`OWNER_ADDR` 정적 목록 치환 + 주민등록번호 형식 정규식 안전망).

### 3. 반영 전 비교

- [x] `KrasHistoryService.preview()`에 `comparison` 필드 추가. `StagePromotionSpec.pattern()`으로 분기해 자연키 UPSERT/신원 매칭/범위 교체/드릴다운/추가 전용마다 다른 문구를 낸다. 자식 테이블(`ChildSpec`)은 비교하지 않는다(부모 수준으로 범위를 좁힘, §1 범위 결정과 동일한 이유).
- [x] 공간 데이터 public 반영 비교 — 새로 만들지 않았다. `kras.lp_pa_cbnd`/`public.lp_pa_cbnd`, `kras.lt_c_uzone`/`public.lt_c_uzone` 건수는 이미 기존 카드(카드 2, 용도지역 릴리즈 카드)가 반영 버튼 옆에 보여주고 있어 이번 작업에서 손대지 않았다(계획이 의도한 "이미 있는 걸 보여주기만" 원칙을 기존 코드가 이미 충족).
- [x] `land_price_file`처럼 승격 단계가 없는 데이터셋은 `promotePath == null`로 이미 구분돼 비교 UI를 계산하지 않는다(기존 로직 그대로 확인).
- [ ] 파싱 경고·제외 레코드를 반영 전 비교 패널에 노출하는 작업은 하지 않았다 — 후속 과제로 남는다(현재는 수집 시점 결과 메시지와 이력 표의 rows_valid/rows_rejected로만 확인 가능).

### 4. 검증 및 마무리

- [x] `gradlew.bat test` 전체 실행 — 67개 중 기존 `DatabaseDefaultsTest` 1개만 실패(무관), 나머지 66개 통과, 신규 테스트 6개 모두 통과.
- [x] 화면 렌더링 + 브라우저(Playwright/Edge) 검증 — 기존 `kras-history.browser.cjs` 재확인 + 신규 `kras-nav.browser.cjs`.
- [x] 구현 결과를 [`kras-integration-phase2-implementation.md`](../../reference/2026-09-22/kras-integration-phase2-implementation.md)에 기록.

## 순서 요약

1(목록·상세) → 2(검증 근거)와 3(반영 전 비교)는 서로 독립적이라 순서 무관, 병렬 진행 가능 → 4(검증·문서화).

결정 완료(2026-09-22): 개인정보는 저장 전 자동 마스킹, `mapper_version`은 수동 입력.
