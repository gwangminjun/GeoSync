# kras 스키마 적재 — 현재 구현 구조

작성일: 2026-09-22
상태: **as-built**. 실제 코드를 읽고 확인한 현재 동작만 적는다.

이 문서는 설계안이 아니다. 설계 의도는 아래 두 문서에 있고, 이 문서는 **그중 실제로 구현된 것이
지금 어떤 모양인지**만 설명한다.

| 문서 | 성격 |
|---|---|
| `docs/superpowers/specs/2026-09-11/2026-09-11-kras-database-sync-design.md` | DB 스키마 설계(kras 스키마 전체) |
| `docs/superpowers/specs/2026-09-18/2026-09-18-kras-ingest-implementation-design.md` | 적재 로직 설계안(구현 전 작성) |
| `docs/database/2026-09-18/kras-schema-create.sql` | 실제 DDL — 가드 트리거·함수 포함 |
| `docs/reference/2026-09-18/kras.md` | KRAS 연계 규격서(§1~16) |
| **이 문서** | **위 설계 중 구현 완료된 부분의 실제 구조** |
| `docs/reference/2026-09-22/kras-contract-verification-runbook.md` | 17개 데이터셋 계약 검증 절차(운영 런북) |
| `docs/reference/2026-09-22/kras-service-catalog.md` | 서비스 ID 카탈로그 — 어떤 데이터가 어느 API에 있나 |

설계 문서와 어긋나는 부분이 있으면 **이 문서가 맞다**(설계 문서는 구현 전 시점에 멈춰 있다).

---

## 1. 큰 그림 — 신규 경로와 기존 경로는 완전히 분리돼 있다

```text
[기존 경로 — 한 줄도 안 건드림]
  KrasWorker ─ SHP/TXT 배치 ─→ public.*, ods.*        (매번 전체 삭제 후 재삽입)
  KrasConnController  /conn/*  ─→ KRAS 실시간 패스스루  (DB 저장 없음)
  KrasGmxController   /svc/*   ─→ /conn 병렬 조합       (DB 저장 없음)
  SyncScheduler / DynamicScheduleManager               (기존 스케줄 그대로)

[신규 경로 — /kras-db 탭]
  KrasSchemaController
    ├─ KrasCadastralIngestService   연속지적 SHP    → kras.cadastral_feature  → public.lp_pa_cbnd
    ├─ KrasUsezoneIngestService     용도지역 SHP    → kras.usezone_feature    → public.lt_c_uzone
    ├─ KrasPnuIngestService         PNU 단건 XML    → kras.stage_*            → 업무 테이블
    ├─ KrasDateRangeIngestService   기간 조회 XML   → kras.stage_*            → 업무 테이블
    └─ KrasTxtIngestService         전체 TXT        → kras.stage_* / 원본표     → 업무 테이블
```

신규 경로는 **새 클래스 + 새 컨트롤러 + 새 화면**으로만 존재한다. `KrasWorker`, `OdsRepository`,
`SyncScheduler`, `KrasConnController`, `KrasGmxController`는 지금까지 한 줄도 수정되지 않았다 —
신규 적재에서 문제가 나도 돌고 있는 기존 동기화와 실시간 조회에는 영향이 없다.

**공유하는 것**은 읽기 전용 재사용뿐이다: `KrasApiClient`(HTTP/GPKI 인증/파싱),
`KrasWorkspaceScanner.loadShpFile()`(SHP 파싱), `TableMapper`(base-tables.xml 정의),
`UsezoneCodeService`(용도지역 코드 테이블). 이들 자체는 수정하지 않고 호출만 한다.

기존 `KrasTxtLoaderService`(TXT → `ods.*`)는 **재사용하지 않고 참고만** 했다 — 그쪽
`detectDelimiter`는 파이프/탭/콤마만 보고 kras.md §16의 ASCII 11을 모른다. 컬럼 순서는 가져오되
파싱은 신규 서비스에 따로 썼다.

---

## 2. 클래스 구성

### 2.1 적재 서비스 5개

| 클래스 | 담당 | 최상위 식별자 | 승격 대상 |
|---|---|---|---|
| `KrasCadastralIngestService` | 연속지적 SHP | `LAYER:LP_PA_CBND` | `public.lp_pa_cbnd` |
| `KrasUsezoneIngestService` | 용도지역 SHP(다중 레이어) | `LAYER:{레이어코드}` | `public.lt_c_uzone` |
| `KrasPnuIngestService` | PNU 단건 XML API | PNU(19자리) | kras 업무 테이블 |
| `KrasDateRangeIngestService` | 기간 조회 XML API | 시작일~종료일 | kras 업무 테이블 |
| `KrasTxtIngestService` | 전체 TXT 2종 | `ALL`(기관 전체) | kras 업무 테이블 / 원본 보존 테이블 |

**왜 나눠 뒀나** — 최상위 식별자(scope)가 서로 다르기 때문이다. PNU 단건과 기간 조회는 수집
절차의 모양이 거의 같지만 "무엇 하나를 가리키는가"가 다르고(PNU vs 날짜 범위), 이걸 한 서비스에
합치면 양쪽 모두에 `null` 파라미터가 생긴다. SHP/TXT 계열은 아예 XML이 아니라 파일이라 구조가 다르다.

### 2.2 매퍼 (XML → stage 행)

```text
KrasXmlServiceMapper          (인터페이스) ← PNU 단건 11개 매퍼
KrasDateRangeServiceMapper    (인터페이스) ← 기간 조회 1개 매퍼
```

### 2.3 공용 유틸

| 클래스 | 역할 |
|---|---|
| `KrasStagePromotionService` | stage → 업무 테이블 승격. 패턴 6종을 여기 한 곳에서만 구현 |
| `KrasFieldParsers` | 날짜/숫자 파싱. 실패 시 NULL 대신 원문을 `extra_attributes`에 보존 |
| `geosync.common.xml.XmlUtil` | `textOf`/`elementsOf` — Document/Element 양쪽 스코프 지원 |

---

## 3. 수집 → 승격 2단계 구조

모든 신규 적재는 **수집(ingest)과 승격(promote)을 별도 버튼·별도 트랜잭션**으로 나눈다.

```text
[수집]  API 호출 → 파싱 → kras.stage_* 적재 → sync_item을 SUCCESS로 전환
          ↓ (사람이 화면에서 파싱된 값을 눈으로 확인)
[승격]  kras.stage_* → 업무 테이블 (또는 public.* 게시)
```

합쳐서 자동 실행하지 않는 이유는 **승격이 실제 운영 테이블을 바꾸기 때문**이다. 특히 SHP 계열의
최종 승격은 살아있는 GeoServer 레이어(`public.lp_pa_cbnd`, `public.lt_c_uzone`)를 건드린다 —
그 직전에 사람이 한 번 끊는다.

### 3.1 트랜잭션 처리 방식

대상 DB가 런타임에 바뀔 수 있는 `JdbcTemplate`이라 Spring의 `@Transactional`을 쓸 수 없다.
대신 네 서비스 모두 동일한 패턴을 쓴다:

```java
targetJdbc.execute((ConnectionCallback<T>) conn -> {
    conn.setAutoCommit(false);
    try {
        JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
        T result = doWorkInTransaction(tx, ...);   // 안에서는 평소처럼 tx.update/queryForObject
        conn.commit();
        return result;
    } catch (Exception e) {
        conn.rollback();
        throw new IllegalStateException(..., e);
    } finally {
        conn.setAutoCommit(autoCommit);
    }
});
```

`DatabaseConnectionService`/`DatabaseSession`은 확장하지 않았다 — 각 서비스 내부의 지역적 처리로 끝난다.

---

## 4. DB가 강제하는 안전장치 (애플리케이션이 우회하지 않는다)

kras 스키마의 가드 트리거·함수가 이미 순서와 완전성을 강제한다. **애플리케이션 코드는 이 검사를
Java로 재구현하지 않고, 막히면 막히는 대로 예외를 그대로 올린다.**

| 가드 | 무엇을 막는가 |
|---|---|
| `guard_item_transition` | `sync_dataset`이 `VERIFIED`+`enabled`가 아니면 item을 SUCCESS로 못 올림. 건수 불일치·미검증 레코드도 차단 |
| `guard_business_row` | 업무 행의 기관/데이터셋 정합성 검사. `parcel`,`building_register`,`building_unit`,`collective_building`,`collective_unit` 5개는 **DELETE 자체를 금지** |
| `guard_geometry` | 도형 유효성 + 투영 테이블(`cadastral_feature`/`usezone_feature`)의 geom이 원본 `spatial_feature`와 `ST_Equals`로 정확히 같은지 검사 |
| `guard_item_content` | SUCCESS 처리된 item의 내용은 불변 |
| `guard_spatial_release` / `validate_spatial_release` | 용도지역은 기관 **전체 레이어**가 완전해야만 발행 가능 |
| `sync_public_cadastral()` / `sync_public_usezone()` | 0건이거나 기존 대비 50% 미만으로 급감하면 public 덮어쓰기 거부 |

**해시/키 계산은 항상 DB 함수로 한다.** `kras.entity_key()`, `kras.request_key()`, `sha256()`을
SQL에서 호출하고 Java에서 재구현하지 않는다 — 두 곳에 같은 규칙을 두면 언젠가 어긋난다.

---

## 5. PNU 단건 파이프라인 (`KrasPnuIngestService`)

### 5.1 매퍼 계약

```java
public interface KrasXmlServiceMapper {
    String datasetCode();                       // "land_info"
    String connSvcId();                         // "KRAS000002" — 미확인이면 예외를 던진다
    MappingResult map(Document xml, String pnu);
    default MappingResult map(Document xml, String pnu, Map<String,String> extraParams) {
        return map(xml, pnu);                   // 드릴다운 매퍼만 오버라이드
    }
    List<StagePromotionSpec> promotionSpecs();  // 부모→자식 순서로 반환
}

record StageRow(String stageTable, Map<String,Object> columns) {}
record MappingResult(List<StageRow> rows, List<String> fieldWarnings) {}
```

`fieldWarnings`가 비어있지 않으면 일부 필드 파싱이 실패했다는 뜻이라 **item을 SUCCESS로 올리지
않는다** — stage에는 원문이 남고, 사람이 화면에서 확인할 때까지 승격이 막힌다.

### 5.2 수집 절차

1. PNU 형식 검증(19자리 숫자)
2. 오늘자 `sync_run`(`job_kind='MANUAL'`) 재사용 또는 생성
3. `scope_key` 계산 — DB의 `kras.entity_key()`에 PNU와 extraParams를 같이 넘긴다.
   같은 PNU라도 추가 파라미터가 다르면 별개 item이 된다
4. `sync_item` 생성(기본 `PLANNED`)
5. `krasApiClient.query(connSvcId, pnu, extraParams)` → XML 파싱
6. 매퍼가 `StageRow` 목록으로 분해
7. stage 테이블별로 `row_no`를 1부터 매겨 INSERT
8. 경고가 없으면 `sync_item`을 SUCCESS로 UPDATE

### 5.3 승격 절차 (별도 트랜잭션)

1. item이 SUCCESS인지 확인 — 아니면 거부
2. 패턴 C/드릴다운이 하나라도 있으면 `pg_advisory_xact_lock`으로 직렬화
   (탐색→UPDATE/INSERT 사이 경쟁 상태 차단. 원자적 SQL 한 문장인 패턴 A/B는 불필요)
3. `promotionSpecs()`를 **선언된 순서대로**(부모→자식) 실행

---

## 6. 승격 패턴 6종 (`KrasStagePromotionService`)

39개 stage 테이블을 39가지로 승격시키지 않는다. DB 제약(어떤 테이블이 DELETE 금지인지, 후보
자연키에 UNIQUE가 있는지)에 따라 실제로는 6가지로 수렴한다.

| 패턴 | 판별 기준 | 동작 | 쓰는 곳 |
|---|---|---|---|
| **A** `NATURAL_KEY_UPSERT` | PK 자체가 자연키(`pnu` 등) | `INSERT ... ON CONFLICT DO UPDATE` 한 문장 | `parcel`, `land_register`, `land_owner`, `land_presence` |
| **B** `SCOPE_REPLACE` | surrogate PK, 참조하는 자식 없음, DELETE 허용 | scope(pnu 등) 전체 DELETE 후 INSERT | `land_share`, `land_ownership_history` |
| **B+자식** `SCOPE_REPLACE_WITH_CHILDREN` | B인데 부모-자식 2단 | 자식→부모 DELETE 후, 부모를 `RETURNING`으로 넣으며 새 ID로 자식 연결 | `land_movement_history`+`relation`, `integrated_building`+`building_parcel` |
| **C** `IDENTITY_MATCH_UPSERT` | surrogate PK이고 **DELETE 금지 목록**에 있음 | 후보 자연키로 검색 → 있으면 UPDATE, 없으면 INSERT (surrogate ID 보존) | `collective_building` |
| **드릴다운** `PARENT_LOOKUP_IDENTITY_MATCH_UPSERT` | 부모가 먼저 승격돼 있어야 자식 식별 가능 | 부모 테이블에서 surrogate ID를 조회해 자식 신원 매칭에 사용 | `collective_unit`, `land_right`, `unit_ownership_history` |
| **D** `APPEND_ONLY` | 자연키 없음, 매 수집이 새 사건 | INSERT만(`UNIQUE(source_item_id,record_no)`가 중복 차단) | `land_change_event`, `building_image` |

**B와 C를 가르는 기준은 "이 surrogate ID를 참조하는 자식이 있는가"** 다. `collective_building_id`는
`collective_unit`이, `unit_id`는 `land_right`가 참조한다 — ID가 매번 바뀌면 자식 관계가 끊긴다.
`guard_business_row`가 DELETE를 막는 5개 테이블이 정확히 "누군가 참조하는 부모" 목록과 일치한다.

새 패턴은 그 패턴이 실제로 필요한 첫 서비스를 붙일 때만 추가했다 — 미리 만들어두지 않았다.

---

## 7. `extra_attributes` 사용 규약

모든 stage/업무 테이블에 있는 `extra_attributes`(jsonb)를 세 가지 용도로 쓴다.

| 용도 | 키 모양 | 누가 넣나 |
|---|---|---|
| 파싱 실패 원문 보존 | `{컬럼명}_raw` | `KrasFieldParsers` (자동) |
| 요청 컨텍스트 기록 | `_pnu`, `_extra_params` | `KrasPnuIngestService` (모든 행에 자동) |
| 응답 echo 값 보관 | `_cbldg_seqno`, `_dong`, `_flr` 등 | 드릴다운 매퍼가 직접 |

**밑줄(`_`)로 시작하면 최상위 키, 아니면 `_extra_params` 안의 키**로 취급한다(드릴다운 조회 규약).

세 번째 용도가 중요하다 — 드릴다운 승격이 부모 ID를 찾을 때 **운영자가 입력한 요청 파라미터 키
이름에 의존하지 않고**, 응답이 그대로 되돌려주는 값을 매퍼가 직접 stage에 남겨 그걸 쓴다.
"KRAS에 무엇을 보내는가"(불투명, 운영자 입력)와 "우리 코드가 무엇을 알아야 하는가"(확실, 응답 기반)를
분리한 것이다.

---

## 8. 드릴다운 — 부모를 먼저 알아야 자식을 조회할 수 있는 경우

집합건물 계열은 PNU만으로 조회가 안 된다. 예를 들어 전유부(`collective_unit`)를 조회하려면
집합건물순번(`CBLDG_SEQNO`)이 필요한데, 그 값은 `collective_building`을 먼저 조회해야 알 수 있다.

```text
① collective_building 수집/승격  →  화면에서 cbldg_seqno 확인
② collective_unit 수집  ─ 추가 파라미터(JSON)에 그 값을 입력해 호출
                        ─ 승격 시 pnu + cbldg_seqno로 collective_building_id를 조회해 채움
③ land_right / unit_ownership_history  ─ ②의 unit_id를 2-hop JOIN으로 조회
```

`ParentLookup.parentTable`은 단순 테이블명이 아니라 **SQL 조각**이라, 2-hop이 필요한 경우
`kras.collective_unit cu JOIN kras.collective_building cb ON ...` 같은 JOIN 식을 그대로 넣는다 —
프레임워크를 고칠 필요가 없었다.

드릴다운 매퍼는 2-인자 `map(xml, pnu)`에서 예외를 던진다. extraParams 없이는 호출 자체가
성립하지 않는다는 걸 컴파일이 아니라 런타임에라도 분명히 하기 위해서다.

---

## 9. 기간 조회 파이프라인 (`KrasDateRangeIngestService`)

PNU 단건과 다른 점만 적는다.

- **`maxQueryDays` 사전 검사** — `sync_dataset.max_query_days`(land_change는 10일)를 넘는 요청은
  API를 호출하기 전에 막는다
- **`scope_key`** — PNU 대신 시작일/종료일 + extraParams로 계산
- **`sync_record` 동반 적재** — StageRow에 `payload` 컬럼이 있으면 stage 삽입과 동시에
  `kras.sync_record`에도 원본을 남긴다. `land_change_event`가 `sync_record(item_id, record_no)`를
  FK로 참조하기 때문. `payload_hash`는 DB의 `sha256()`으로 계산

---

## 10. SHP 파이프라인 두 개의 차이

### 10.1 연속지적 (`KrasCadastralIngestService`) — 단일 레이어, 2버튼

`spatial_layer` 등록 → `sync_run`/`sync_item` → `spatial_feature` batch INSERT →
`cadastral_feature`를 **`spatial_feature`에서 SELECT로** 채움 → SUCCESS 전환 → (별도) `sync_publication`
upsert → `sync_public_cadastral()`.

WKT를 두 번 파싱하지 않고 SELECT로 복사하는 이유는 `guard_geometry`가 `ST_Equals`로 두 테이블의
geom이 정확히 같은지 검사하기 때문이다 — 부동소수점 재파싱 오차를 원천 차단한다.

### 10.2 용도지역 (`KrasUsezoneIngestService`) — 다중 레이어, 4버튼

기관 **전체 레이어가 한 번에 완전해야** 게시된다. 그래서 단계를 4개로 쪼갰다.

| 버튼 | 하는 일 |
|---|---|
| ①카탈로그 수집 | `layerList()` 결과를 `layer_list` item + `sync_record`로 **동결**. 이후 매니페스트 검증의 기준 |
| ②레이어 순회 시작 | `spatial_release`(DRAFT) 생성 → `spatial_release_expected` 등록 → `seal_spatial_release()`(READY) → 레이어별 SHP 수집 → `spatial_release_member` 등록 |
| ③release 발행 | `publish_spatial_release()` — 전체 레이어 완전성 검증. 하나라도 미완료면 여기서 막힘 |
| ④public 승격 | `sync_public_usezone()` → `public.lt_c_uzone` |

**②는 레이어당 별도 트랜잭션**이다. 28개 중 3개가 실패해도 성공한 25개는 유지되고, 버튼을 다시
누르면 이미 완료된 레이어는 건너뛰고 실패분만 재시도한다. release는 DRAFT/READY 상태이므로
`spatial_release_member`를 계속 갱신할 수 있다.

`theme_code`/`theme_name`은 SHP 속성에 없다 — 레거시 `KrasWorker.deriveUsezoneFields()`와 **동일한
규칙**(mnum 21~26번째 자리 파생 + `mt_usezone_cd` 코드 테이블 조회)을 그대로 쓴다. 추측이 아니라
이미 운영에서 검증된 규칙의 재사용이다.

---

## 10.3 전체 TXT 두 개 (`KrasTxtIngestService`)

기관 전체 토지를 한 파일로 받는 FULL 수집모드. `scope_key`는 `ALL`이다.
인코딩은 EUC-KR, 구분자는 **ASCII 11**(kras.md §16의 `♂`, DDL `sync_file.delimiter_code=11`)을
정본으로 보고, 없으면 파이프/탭/콤마 순으로 내려간다.

| dataset | 파일 항목 | 적재 대상 | 승격 |
|---|---|---|---|
| `land_basic_file`(KRAS000040) | 8개 — 앞 5개를 이어붙이면 19자리 PNU, 나머지가 지목·면적·소유구분 | `stage_parcel` + `stage_land_basic` | 있음(패턴 A×2) |
| `land_price_file`(KRAS000039) | 5개 — land_cd, base_year, jiga, base_mon, pyo_yn | `land_price_file_row` 직행 | **없음** |

`land_price_file`에 승격이 없는 이유는 `kras.land_price_file_row`가 업무 테이블이 아니라
`spatial_feature`와 같은 **원본 보존 테이블**(`guard_item_content`가 SUCCESS 후 불변을 강제)이고,
`business_dataset`에 대응 업무 테이블이 등록돼 있지 않기 때문이다. 대장 공시지가 `kras.land_price`는
`land_info` 데이터셋 소유라 이 파일에서 채우지 않는다.

**한 줄이라도 파싱에 실패하면 SUCCESS로 올리지 않는다.** 전체 파일이라 조용히 건너뛰면 어느 필지가
빠졌는지 알 수 없다 — 경고(최대 20건까지 보관)를 화면에 띄우고 승격을 막는다.

파싱만 따로 검증하는 `KrasTxtIngestServiceParseTest`가 있다(DB·API 없이 도는 단위 테스트) —
구분자 판정과 헤더 스킵이 틀리면 수십만 행이 통째로 잘못 들어가기 때문이다.

---

## 11. 컨트롤러 레지스트리 — 서비스를 추가하는 방법

`KrasSchemaController`는 매퍼가 늘어날 때마다 필드·엔드포인트 쌍을 복붙하지 않는다.
Spring이 `List<KrasXmlServiceMapper>`를 통째로 주입하고, 컨트롤러는 레지스트리 두 개로만 관리한다.

```java
// slug(URL/템플릿용) → dataset_code(DB용) → modelPrefix(Thymeleaf 변수 접두어)
private static final List<ImplementedService> IMPLEMENTED_SERVICES = List.of(
    new ImplementedService("land-info", "land_info", "landInfo"),
    ...
);
private static final List<DateRangeService> DATE_RANGE_SERVICES = List.of(
    new DateRangeService("land-change", "land_change", "landChange")
);
```

`modelPrefix`는 `landInfoStatus`/`landInfoEnabled`/`landInfoRunning`/`lastLandInfoResult` 형태로
조립돼 기존 템플릿 변수명과 그대로 맞는다.

**새 PNU 서비스를 추가할 때 해야 할 일은 세 가지뿐이다:**
1. `KrasXmlServiceMapper` 구현체 하나 작성(`@Component`)
2. `IMPLEMENTED_SERVICES`에 한 줄 추가
3. `kras-db.html`에 카드 추가(JS는 `runPnuIngest(slug)`/`runPnuPromote(slug)` 재사용)

컨트롤러 엔드포인트는 건드리지 않는다 — `/kras-db/ingest/{slug}`가 이미 범용이다.

### 엔드포인트 목록

| 경로 | 용도 |
|---|---|
| `GET  /kras-db` | 화면 |
| `POST /kras-db/verify-dataset` · `revert-dataset` | 계약 검증 전환/원복 |
| `POST /kras-db/ingest/cadastral` · `promote/cadastral` | 연속지적 |
| `POST /kras-db/usezone/collect-catalog` · `sweep` · `publish` · `sync-public` | 용도지역 4단계 |
| `POST /kras-db/ingest/{slug}` · `promote/{slug}` | PNU 단건 공용 |
| `POST /kras-db/ingest-range/{slug}` · `promote-range/{slug}` | 기간 조회 공용 |
| `POST /kras-db/ingest/land-basic-file` · `promote/land-basic-file` | 토지기본정보 전체 TXT |
| `POST /kras-db/ingest/land-price-file` | 공시지가 전체 TXT(승격 없음) |

`{slug}`는 레지스트리에 등록된 것만 허용한다 — 클라이언트가 임의 `dataset_code`로 다른 매퍼를
부를 수 없다.

---

## 12. 계약 검증(VERIFIED) 게이트

`kras.sync_dataset`의 `contract_status`(UNVERIFIED/VERIFIED)와 `enabled`는 **데이터셋마다 한 번**
사람이 켜는 스위치다. 꺼져 있으면 `guard_item_transition`이 item의 SUCCESS 전환을 막는다.

- 33개를 일괄 전환하지 않는다. 각 API 응답이 문서와 실제로 일치하는지는 데이터셋마다 따로 확인해야 한다
- 화면의 "계약 확인" 버튼은 **검증을 대신하지 않는다** — 사람이 실제 운영 응답으로 먼저 확인하고,
  버튼은 그 결과를 DB에 반영만 한다(체크박스 확인 필수)
- "검증 원복" 버튼은 앞으로의 신규 수집만 다시 막는다. 과거 적재 결과는 건드리지 않는다

---

## 13. 현재 구현된 데이터셋 17개

### SHP/파일 계열 (4)

| dataset_code | 서비스 | 상태 |
|---|---|---|
| `cadastral_file` | KRAS000038 (연속지적) | 구현 완료 |
| `usezone_file` + `layer_list` | KRAS000038/37 (용도지역) | 구현 완료 |
| `land_basic_file` | KRAS000040 (토지기본정보 전체 TXT) | 구현 완료 |
| `land_price_file` | KRAS000039 (공시지가 전체 TXT) | 구현 완료 — 승격 단계 없음 |

### PNU 단건 XML (11)

| dataset_code | kras.md | conn_svc_id | 승격 대상 | 패턴 |
|---|---|---|---|---|
| `land_info` | §1 | KRAS000002 | `parcel`, `land_register`, `land_owner` | A×3 |
| `shr_ymb` | §2 | KRAS000003 | `parcel`, `land_share` | A+B |
| `land_bldg_check` | §3 | KRAS000101 | `parcel`, `land_presence` | A×2 |
| `collective_building` | §4 | **미확인** | `parcel`, `collective_building` | A+C |
| `collective_unit` | §5 | **미확인** | `collective_unit` | 드릴다운 |
| `land_right` | §6 | **미확인** | `land_right`, `unit_ownership_history` | 드릴다운×2 |
| `land_mov_hist` | §7 | KRAS000006 | `land_movement_history`+`relation` | B+자식 |
| `own_rgt_hist` | §8 | KRAS000007 | `parcel`, `land_ownership_history` | A+B |
| `unit_ownership_history` | §9 | **미확인** | `unit_ownership_history` | 드릴다운(2-hop) |
| `integrated_building` | §13 | **미확인** | `integrated_building`+`building_parcel` | B+자식 |
| `building_image` | §14 | **미확인** | `sync_file`, `building_image` | D |

### 기간 조회 XML (1)

| dataset_code | kras.md | conn_svc_id | 승격 대상 | 패턴 |
|---|---|---|---|---|
| `land_change` | §10 | **미확인** | `land_change_event`(+`sync_record`) | D |

**"미확인"은 `kras.sync_dataset.service_code`가 NULL이라는 뜻이다.** 해당 매퍼의 `connSvcId()`는
`UnsupportedOperationException`을 던져 실제 호출만 막는다 — 파싱·승격 로직은 완성돼 있어서,
연계 담당자가 서비스 ID를 알려주면 그 한 줄만 채우면 동작한다.

---

## 14. 지키고 있는 원칙

1. **추측 금지.** XML 태그명, 요청 파라미터명, conn_svc_id를 문서 근거 없이 채우지 않는다.
   모르면 예외를 던지거나 운영자 입력(`extraParamsJson`)으로 받는다
2. **DB가 아는 건 DB에 묻는다.** 해시·키 계산(`entity_key`, `request_key`, `sha256`)과 완전성
   검증을 Java로 재구현하지 않는다
3. **기존 경로 불변.** 신규 적재는 기존 동기화·실시간 조회와 코드 레벨로 분리한다
4. **파싱 실패를 NULL로 뭉개지 않는다.** 원문을 `extra_attributes`에 남기고 승격을 보류한다
5. **응답 신원 대조.** 응답의 필지 식별 5태그 조합이 요청 PNU와 다르면 진행하지 않고 예외를 던진다
6. **패턴은 필요할 때 추가한다.** 승격 패턴 6종은 각각 그게 실제로 필요한 첫 서비스를 붙일 때 만들었다

---

## 15. 남은 작업

| 항목 | 상태 | 막는 것 |
|---|---|---|
| PNU 단건 14개 (`bldg_hds_info` 등 KRAS 9종 + KOREPS 5종) | 미착수 | **kras.md에 해당 서비스 규격이 없음.** 서비스 코드(KRAS000014~017/025~027/102/103, KOREPS 5종)가 §1~16 어디에도 등장하지 않는다 — 실제 응답 없이는 매퍼 작성 불가 |
| `land_owner_change`(§11), `unit_owner_change`(§12) | 미착수 | **kras.md 문서 오류.** §11·§12 내용이 §10과 필드명·샘플값까지 완전히 동일하게 중복 기재됨 |
| 스케줄 자동화 | 미착수 | 막는 것 없음. `kras.sync_work` 큐는 DDL에 이미 존재. 수동 트리거가 충분히 검증된 뒤 착수하는 게 원칙 |
| `/conn`,`/svc` 실서빙 전환 | 미착수 | `kras.api_response`/`api_bundle` 계층이 아직 안 채워짐. 운영 트래픽·지연 실측 후 판단할 문제 |
