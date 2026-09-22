# KRAS 연계 관리 2차 구현 결과

- 작성일: 2026-09-22
- 기준: [기능 추가 및 개선 제안](./kras-integration-improvement-recommendations.md)의 2차 항목 2·3,
  구현 계획: [2차 구현 계획](../../superpowers/plans/2026-09-22-kras-integration-phase2.md)
  (2차 항목 1 비동기 실행·진행상태 조회는 이전 커밋에서 완료)
- 상태: 코드 구현 및 아래 검증 완료. 실제 KRAS 신규 수집·운영 반영은 실행하지 않았다.

## 1. 구현한 기능

| 항목 | 구현 내용 |
|---|---|
| 목록·상세 사이드바 | `/kras-db` 상단에 검색·상태 필터가 달린 연계 목록을 추가했다. 데이터셋 31개를 토지/건물/공간(SHP)/가격/전체TXT/기간조회 6개 그룹으로 분류하고, 클릭하면 팝업으로 해당 데이터셋의 카드를 보여준다. 데이터셋 카드는 **기본적으로 숨겨져 있고**(`kras-hide` 클래스) 팝업을 열 때만 나타난다 — 스크롤 이동+강조 → "카드는 그대로 두고 팝업만 추가" 순으로 시도했으나 둘 다 "스크롤로 다 훑어야 한다"는 원래 문제가 남아있어 기본 숨김으로 바꿨다. |
| 카드 재사용(복제 아님) | 기존 카드 31개의 HTML·버튼·JS 함수는 수정하지 않았다. 각 카드에 `id="card-<slug>"`만 추가하고, 팝업을 열 때 카드 DOM 노드를 **복제가 아니라 실제로 옮긴다**(주석 노드를 원래 자리 표시자로 남겨 닫을 때 되돌림) — 그래야 카드 안의 `id` 참조 버튼/폼(`runPnuIngest` 등)이 그대로 동작한다. 카드 2~3개가 한 데이터셋을 이루는 경우(연속지적 4장, land_info 등 PNU 서비스 7종 2장, 용도지역 3장)는 `data-companion-of="<slug>"`로 표시해 팝업을 열 때 함께 옮긴다. |
| 검증 근거 저장 | 검증 전환 폼마다 담당자·매퍼 버전(수동 입력)·응답 샘플·비고 입력을 추가했다. 신규 테이블 `kras.ui_verification_evidence`에 저장하고, 목록 항목에 마우스를 올리면 마지막 검증 일시·담당자를 보여준다. |
| 응답 샘플 자동 마스킹 | 저장 전 서버에서 `OWNER_NM`/`OWNER_ADDR` 태그 값과 주민등록번호 형식 문자열을 치환한다. 원문은 어디에도 남기지 않는다. |
| 근거 저장 실패 격리 | 근거 저장이 실패해도 검증 전환(VERIFIED)은 이미 커밋된 상태를 유지한다. 실패 시에만 안내 문구에 사유를 덧붙인다. |
| 반영 전 비교 | 수집 이력에서 건을 선택하면 등록된 승격 패턴(자연키 UPSERT/신원 매칭/범위 교체/드릴다운/추가 전용)에 따라 서로 다른 문구로 stage 건수와 업무 테이블의 기존 상태를 보여준다. |
| 비교 실패 격리 | 비교 조회가 실패해도(연결 끊김 등) 반영 버튼은 그대로 동작한다. 실패한 항목만 "계산할 수 없습니다" 문구로 표시한다. |

목록의 상태 배지·필터는 기존 `kras.sync_dataset` 조회 결과를 그대로 쓴다. 비교 정보는 부모 stage/업무 테이블 수준까지만 계산하며, `ChildSpec`으로 딸린 자식 테이블은 다루지 않는다 — 반영 여부 판단에는 부모 수준 비교로 충분하다고 판단했다(계획 §3 범위 결정).

## 2. 배포 및 DB 영향

### 추가 테이블

[`conf/sql/kras-ui-verification-evidence.sql`](../../../conf/sql/kras-ui-verification-evidence.sql)에 추가 테이블 DDL을 제공한다. 기존 업무 테이블, DB 가드, `kras.sync_dataset` 스키마는 변경하지 않는다.

- 첫 검증 근거 저장 요청 시 테이블이 없으면 애플리케이션이 생성한다(`kras.ui_operation_log`와 동일한 패턴).
- DDL 권한이 없는 운영 계정은 관리자가 SQL을 먼저 적용하고 SELECT/INSERT 및 시퀀스 USAGE 권한을 부여해야 한다.
- 근거 저장 실패는 검증 전환 실패로 이어지지 않는다 — 이미 커밋된 `sync_dataset` 갱신은 그대로 유지되고, 화면에만 저장 실패 사실이 남는다.

### 마스킹 대상

`docs/reference/2026-09-18/kras.md`에서 개인정보로 확인된 필드(`OWNER_NM`, `OWNER_ADDR`)를 정적 목록으로 관리한다(`KrasVerificationEvidenceService.PII_TAGS`). 새 개인정보 필드가 발견되면 이 목록에 한 줄만 추가하면 된다. 목록에 없는 필드에 주민등록번호 형식 문자열이 들어간 경우를 대비해 정규식 안전망(`\d{6}-?\d{7}`)도 함께 적용한다.

## 3. 검증 결과

| 검증 | 결과 |
|---|---|
| `gradlew.bat test` | 67개 중 1개 실패(기존 `DatabaseDefaultsTest`, 이번 변경과 무관 — 로컬 환경의 Postgres 기본값 차이). 나머지 66개 통과 |
| 신규/확장 테스트 | `KrasVerificationEvidenceServiceTest`(마스킹 4건), `KrasHistoryServiceTest`(반영 전 비교 2건 추가), `KrasIntegrationViewTest`(사이드바·앵커 렌더링 확인 추가) |
| Spring 컨텍스트 로딩 | `KrasMapperRegistryTest`로 신규 빈(`KrasVerificationEvidenceService`) 배선 확인 |
| Thymeleaf 렌더링 | 실제 템플릿·레이아웃을 렌더링해 사이드바, 카드 앵커, 그룹 헤더 확인 |
| Playwright / Edge | 기존 흐름(이력 선택/반영, 용도지역 순회, TXT 재수집 차단)이 그대로 동작함을 `kras-history.browser.cjs`로 재확인. 신규 `kras-nav.browser.cjs`로 검색·상태 필터, 팝업 열기/닫기(닫기 버튼·백드롭 클릭), companion 카드 동반 이동·복원, 반영 전 비교 패널 렌더링을 검증 |

새 테스트는 PII 마스킹(태그 치환·정규식 안전망·비-PII 보존·null 처리), 패턴별 비교 문구(자연키 일치/불일치, 비교 실패 시 반영 유지), 사이드바 앵커·그룹 렌더링을 검증한다. DB 경계는 테스트 대역을 사용했다. 브라우저는 실제 렌더링 HTML을 사용하고 API 응답은 통제된 테스트 데이터로 제공했다. 운영 데이터로 전체 흐름을 검증한 것은 아니다.

브라우저 테스트 재실행 예시(PowerShell, 저장소 루트):

```powershell
.\gradlew.bat test --tests geosync.kras.KrasIntegrationViewTest
npm.cmd install --prefix build/kras-ui-tools playwright --no-audit --no-fund
$env:PLAYWRIGHT_MODULE = (Resolve-Path build/kras-ui-tools/node_modules/playwright).Path
node src/test/js/kras-history.browser.cjs
node src/test/js/kras-nav.browser.cjs
```

Microsoft Edge가 설치되어 있어야 한다. 테스트 도구와 HTML 출력은 `build/` 아래에만 생성된다.

## 4. 검토 및 남은 범위

- 개인정보 마스킹은 `kras.md`에 문서화된 필드 기준의 정적 목록 + 정규식 안전망이다. 목록에 없는 새로운 개인정보 필드는 자동으로 걸러지지 않는다 — 실제 응답 검증 과정에서 새 필드가 발견되면 목록에 추가해야 한다.
- `mapper_version`은 계획대로 수동 입력으로 시작했다. 커밋 해시 자동 채움 등은 배포 방식이 정해진 뒤 후속 과제다.
- 반영 전 비교는 부모 stage/업무 테이블 수준까지만 계산한다. 자식 테이블(층별/소유자/변동 등)의 건수 비교는 범위 밖이다.
- 목록·상세는 팝업(모달) 방식이다. 처음에는 "클릭 시 해당 카드로 스크롤 이동 + 2초 강조"로 구현했으나 확인하기 어렵다는 피드백을 받아 팝업으로 바꿨다. 카드를 복제하지 않고 실제 DOM 노드를 모달로 옮기는 방식이라 카드 내부 버튼/폼의 `id` 참조가 그대로 살아있다 — 닫으면 원래 있던 자리(주석 노드 표시자)로 되돌아간다.
- 3차 항목(입력 도우미, PNU 다건/기간분할/예약수집, 속도제한/재시도/재개)은 검증된 데이터셋이 아직 없어 범위 밖이다.
