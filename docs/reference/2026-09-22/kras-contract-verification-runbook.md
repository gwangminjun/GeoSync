# kras 데이터셋 계약 검증 절차 (운영 런북)

작성일: 2026-09-22
대상: 현재 구현된 17개 데이터셋을 `UNVERIFIED` → `VERIFIED`로 전환하는 사람

구조 설명은 [kras-ingest-structure.md](./kras-ingest-structure.md)에 있다. 이 문서는
**실제로 무엇을 눌러야 하고, 무엇을 눈으로 확인해야 하는가**만 다룬다.

---

## 0. 시작 전에 알아야 할 것 — 순서가 거꾸로다

`guard_item_transition`은 `sync_dataset`이 `VERIFIED` + `enabled`가 **아니면 item을 SUCCESS로
올리지 못하게** 막는다. 그런데 수집(ingest)은 마지막에 SUCCESS 전환을 같은 트랜잭션에서 시도한다.

즉 **미검증 상태에서는 "일단 수집해서 값을 보고 판단"이 안 된다.** 파싱이 깨끗할수록 오히려
다음 에러로 전체가 롤백된다:

```
Dataset must be verified/enabled before SUCCESS
```

(파싱 경고가 있으면 SUCCESS를 시도하지 않으므로 stage에는 남는다 — 하지만 이건 "경고가 난 경우"에만
해당하는 우연한 경로지 검증 수단이 아니다.)

따라서 검증은 **kras 스키마 바깥에서 먼저** 해야 한다.

```
① kras를 건드리지 않는 도구로 원본을 받아 눈으로 확인   ← 진짜 검증
② [계약 확인 → VERIFIED로 전환] 버튼                    ← 확인 결과를 DB에 기록만
③ 테스트 수집 → 파싱된 값 미리보기 확인
④ 업무 테이블로 승격
```

②번 버튼은 검증을 대신하지 않는다. ①을 건너뛰고 ②를 누르면 "검증했다"는 기록만 거짓으로 남는다.

---

## 1. 지금 검증 가능한 것 10개 / 막혀 있는 것 7개

검증의 전제는 **API를 실제로 호출할 수 있어야 한다**는 것이다. `kras.sync_dataset.service_code`가
NULL인 데이터셋은 호출 자체가 불가능해 검증을 시작할 수 없다.

### 검증 가능 (service_code 확정) — 10개

| dataset_code | service_code | 원본 확인 방법 |
|---|---|---|
| `land_info` | KRAS000002 | `/api-test?api=conn/land_info` |
| `shr_ymb` | KRAS000003 | `/api-test?api=conn/shr_ymb` |
| `land_mov_hist` | KRAS000006 | `/api-test?api=conn/land_mov_hist` |
| `own_rgt_hist` | KRAS000007 | `/api-test?api=conn/own_rgt_hist` |
| `land_bldg_check` | KRAS000101 | `/api-test?api=conn/land_bldg_check` |
| `layer_list` | KRAS000037 | `/api-test` 레이어 목록 |
| `cadastral_file` | KRAS000038 | 파일 다운로드 화면 → SHP 열어보기 |
| `usezone_file` | KRAS000038 | 파일 다운로드 화면 → 레이어별 SHP |
| `land_price_file` | KRAS000039 | 파일 다운로드 화면 → TXT 열어보기 |
| `land_basic_file` | KRAS000040 | 파일 다운로드 화면 → TXT 열어보기 |

`/kras-db` 화면의 각 카드에 `/api-test` 딥링크가 이미 걸려 있다.

### 검증 불가 (service_code = NULL) — 7개

`collective_building`, `collective_unit`, `land_right`, `unit_ownership_history`,
`integrated_building`, `building_image`, `land_change`

매퍼·승격 로직은 완성돼 있고 `connSvcId()`만 `UnsupportedOperationException`을 던진다.
**KRAS 연계 담당자에게 서비스 ID를 받아야 검증을 시작할 수 있다** — 추측해서 채우면 안 된다.

---

## 2. 권장 순서 — 의존성이 있다

임의 순서로 하면 안 된다. FK와 선행 데이터 때문에 아래 순서를 지킨다.

```
[1단계] 기반 — 서로 독립, 병렬 가능
  land_basic_file   ← 기관 전체 kras.parcel을 한 번에 채운다. 사실상 가장 먼저 하는 게 이득
  cadastral_file    ← 도형 계열, parcel과 무관(FK 없음)
  land_price_file   ← 독립(원본 보존 테이블 직행)

[2단계] 용도지역 — layer_list가 반드시 먼저
  layer_list  →  usezone_file

[3단계] PNU 단건 — 자기가 parcel도 같이 만들어서 서로 순서 무관
  land_info / land_bldg_check / shr_ymb / own_rgt_hist

[4단계] parcel 선행이 필요한 것
  land_mov_hist     ← 응답에 필지 식별 태그가 없어 parcel을 스스로 못 만든다.
                       해당 PNU가 kras.parcel에 이미 있어야 승격이 FK에서 막히지 않는다.

[5단계] service_code 확보 후 (7개)
  collective_building → collective_unit → land_right / unit_ownership_history
  integrated_building, building_image, land_change
```

**`land_basic_file`을 맨 앞에 두는 이유**: 기관 전체 필지를 한 번에 `kras.parcel`에 채워 넣으므로,
이후 모든 PNU 단건 검증에서 "이 PNU가 parcel에 없어서 FK로 막힌다"는 상황이 아예 없어진다.
단, 전체 시군구를 한 트랜잭션으로 쓰므로 수집·승격이 수 분 걸릴 수 있다(테이블 잠금 주의).

5단계 안의 화살표는 **드릴다운**이라 순서가 강제된다 — `collective_unit`을 호출하려면
`collective_building`에서 얻은 집합건물순번이 필요하고, `land_right`/`unit_ownership_history`는
`collective_unit`이 승격돼 있어야 부모 ID를 찾는다.

---

## 3. 유형별 검증 절차

### 3.1 PNU 단건 XML (land_info, land_bldg_check, shr_ymb, own_rgt_hist, land_mov_hist)

1. **원본 확인** — `/kras-db` 해당 카드의 `/api-test` 링크를 눌러 **실제 운영 PNU 1건**으로 호출한다.
   kras 스키마를 전혀 건드리지 않는다.
2. **응답 XML을 kras.md와 대조** — 확인할 것:
   - `<CODE>0000</CODE>` 성공 코드가 문서와 같은가
   - 카드의 "응답 항목(XML 태그)" 표에 적힌 태그가 **실제 응답에 그 이름 그대로** 있는가
   - 반복 그룹이 있는 서비스(`shr_ymb`, `own_rgt_hist`, `land_mov_hist`)는 반복 단위 태그명이 맞는가
   - 날짜(`YYYYMMDD`)·숫자(면적 등) 형식이 예상과 같은가 — 콤마·공백이 섞여 있는지
3. **[계약 확인 → VERIFIED로 전환]** — 체크박스를 켜고 버튼을 누른다.
4. **[테스트 수집]** — 같은 PNU로 실행. 화면에 파싱된 stage 값 미리보기가 뜬다.
   - 파싱 경고가 뜨면 **승격하지 말 것.** 경고 메시지에 어느 필드가 왜 실패했는지 나온다.
   - 응답의 필지 식별 5태그와 요청 PNU가 다르면 여기서 예외로 막힌다(의도된 동작).
5. **미리보기 값을 원본 XML과 비교** — 지목·면적·소유자명 등이 XML 값과 일치하는지 눈으로 확인.
6. **[업무 테이블로 승격]** — 여기서 처음으로 업무 테이블이 바뀐다.
7. **재실행 확인** — 같은 PNU로 4~6을 한 번 더 돌려 중복 행이 안 생기는지(UPSERT/전체 교체가
   의도대로 도는지) 확인한다.

### 3.2 연속지적 SHP (cadastral_file)

1. **원본 확인** — 파일 다운로드 화면에서 연속지적 SHP를 받아 건수와 좌표계를 확인한다.
2. VERIFIED 전환.
3. **[수집 실행]** — `kras.cadastral_feature`까지만 채운다.
4. **카드의 건수 비교를 확인** — `kras.lp_pa_cbnd`(게시본) 건수가 SHP 건수와 맞는가.
5. **[승격 실행]** — 여기서 **살아있는 GeoServer 테이블** `public.lp_pa_cbnd`가 바뀐다.
   - 0건이거나 기존 대비 50% 미만이면 DB 함수가 거부한다(안전장치).
6. GeoServer에서 레이어가 정상 렌더링되는지 확인.

### 3.3 용도지역 SHP (layer_list → usezone_file)

`layer_list`와 `usezone_file` **둘 다** VERIFIED+enabled여야 한다.

1. `layer_list` 원본 확인 → VERIFIED 전환.
2. **[①카탈로그 수집]** — 레이어 목록이 동결된다. 화면에 레이어 개수가 뜬다.
   목록이 예상한 시군구 레이어 구성과 맞는지 확인.
3. `usezone_file` 원본 확인(레이어 1개 SHP를 받아 속성 확인) → VERIFIED 전환.
4. **[②레이어 순회 시작]** — 레이어별 상태 표에서 성공/실패를 본다.
   실패한 레이어가 있으면 원인을 고치고 **버튼을 다시 누른다**(완료분은 건너뛰고 실패분만 재시도).
5. **[③release 발행]** — 전체 레이어가 완전해야 통과한다. 하나라도 빠지면 여기서 막히는 게 정상이다.
6. **[④public 승격]** — `public.lt_c_uzone`이 바뀐다. 이 테이블 위에 필터 뷰가 다수 걸려 있으므로
   테이블 내용만 교체되고 뷰 재등록은 필요 없다.

### 3.4 전체 TXT (land_basic_file, land_price_file)

1. **원본 확인** — 파일 다운로드 화면에서 TXT를 받아 텍스트 편집기로 앞 몇 줄을 본다. 확인할 것:
   - **구분자가 ASCII 11인가** (편집기에서 보이지 않는 문자로 나온다). 파이프/탭/콤마면
     적재 코드가 폴백으로 처리하지만, 문서(§16)와 다르다는 뜻이므로 기록해 둘 것
   - 인코딩이 EUC-KR인가 (한글이 깨지지 않는가)
   - 컬럼 개수와 순서가 카드의 표와 같은가 — 특히 `land_price_file`은 kras.md에 규격이 없고
     **레거시 코드의 순서(land_cd, base_year, jiga, base_mon, pyo_yn)를 그대로 따르고 있으므로**
     실제 파일로 반드시 확인해야 한다
2. VERIFIED 전환.
3. **[전체 TXT 수집]** — 전체 파일이라 시간이 걸린다.
   - 한 줄이라도 파싱에 실패하면 SUCCESS로 안 올라가고 경고가 뜬다. 조용히 건너뛰지 않는다.
4. `land_basic_file`만 **[업무 테이블로 승격]**이 있다. `land_price_file`은 원본 보존 테이블 직행이라
   승격 단계가 없다(카드에 버튼이 없는 게 정상).
5. 카드의 건수를 파일 줄 수와 대조한다.

### 3.5 기간 조회 (land_change) — service_code 확보 후

1. 원본 확인(운영 응답 XML).
2. **§11·§12 문서 오류 확인** — kras.md에서 §11(소유권변동내역)·§12(집합건물소유권변동내역)이
   §10과 내용이 완전히 같게 중복 기재돼 있다. 실제 응답이 §10과 정말 같은 구조인지,
   아니면 별개 서비스인지 이때 같이 확인해서 문서를 바로잡아야 한다.
3. VERIFIED 전환 → 시작일/종료일(최대 10일)로 테스트 수집 → 미리보기 확인 → 승격.

---

## 4. 데이터셋별 체크리스트

| # | dataset_code | 선행 조건 | 검증 상태 |
|---|---|---|---|
| 1 | `land_basic_file` | 없음 | 가능 |
| 2 | `cadastral_file` | 없음 | 가능 |
| 3 | `land_price_file` | 없음 | 가능 |
| 4 | `layer_list` | 없음 | 가능 |
| 5 | `usezone_file` | `layer_list` 수집 완료 | 가능 |
| 6 | `land_info` | 없음 | 가능 |
| 7 | `land_bldg_check` | 없음 | 가능 |
| 8 | `shr_ymb` | 없음 | 가능 |
| 9 | `own_rgt_hist` | 없음 | 가능 |
| 10 | `land_mov_hist` | 해당 PNU가 `kras.parcel`에 존재 | 가능 |
| 11 | `collective_building` | service_code | **막힘** |
| 12 | `collective_unit` | service_code + 11번 승격 | **막힘** |
| 13 | `land_right` | service_code + 12번 승격 | **막힘** |
| 14 | `unit_ownership_history` | service_code + 12번 승격 | **막힘** |
| 15 | `integrated_building` | service_code | **막힘** |
| 16 | `building_image` | service_code + 해당 PNU가 `kras.parcel`에 존재 | **막힘** |
| 17 | `land_change` | service_code + §11·§12 문서 확인 | **막힘** |

---

## 5. 잘못됐을 때

### 검증을 되돌리기
각 카드의 **[검증 원복]** 버튼 — `UNVERIFIED` + `disabled`로 되돌린다.
**앞으로의 신규 수집만 다시 막을 뿐, 이미 적재된 데이터는 지우지 않는다.**

### 이미 잘못된 데이터가 업무 테이블에 들어갔다면
버튼으로는 지울 수 없다. `kras.parcel`·`building_register`·`building_unit`·
`collective_building`·`collective_unit`은 DB가 DELETE 자체를 금지하고 있어
`record_status`를 바꾸는 방식으로만 처리해야 한다 — DBA와 상의할 일이지 화면에서 할 일이 아니다.

`public.lp_pa_cbnd`/`public.lt_c_uzone`은 이전 정상 수집분으로 다시 승격하면 복구된다.

### 자주 만나게 될 에러

| 메시지 | 뜻 | 조치 |
|---|---|---|
| `Dataset must be verified/enabled before SUCCESS` | 아직 UNVERIFIED | §0 순서대로 — `/api-test`로 먼저 확인 후 VERIFIED 전환 |
| `... conn_svc_id가 아직 확인되지 않았습니다` | service_code=NULL인 7개 | 연계 담당자 확인 필요 |
| 응답 필지 식별자가 요청 PNU와 다릅니다 | 응답이 다른 필지를 반환 | 정상 차단. 요청 PNU나 기관 설정을 확인 |
| `Incomplete layer or feature count mismatch` | 용도지역 레이어 일부 미완료 | ②레이어 순회를 다시 눌러 실패분 재시도 |
| `... is less than half of current public...` | public 건수 급감 | 부분 수집일 가능성. 수집 건수부터 확인 |

---

## 6. 검증이 전부 끝난 뒤

17개가 모두 VERIFIED되고 재실행까지 문제없이 돌면, 그때 **스케줄 자동화**를 착수한다.
검증 안 된 매퍼로 기관 전체를 자동 순회시키지 않는다는 원칙이 이 순서의 이유다.
