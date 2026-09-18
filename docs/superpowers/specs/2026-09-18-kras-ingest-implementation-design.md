# 신규 KRAS API → kras 스키마 적재 로직 설계

작성일: 2026-09-18
상태: 설계안. 코드 변경 없음.

## 1. 현재 구현 확인 결과

코드를 직접 읽고 확인한 사실만 적는다.

| 구성요소 | 실제 동작 | 근거 |
|---|---|---|
| `KrasApiClient` | KRAS estateGateway HTTP 클라이언트. `query(svcId,pnu,extra)`(PNU 단건), `downloadLayer`(SHP/DBF/SHX), `layerList()`, `downloadJigaTxt/LandTxt()`(TXT) 보유 | `kras/KrasApiClient.java` |
| `KrasConnController`(`/conn/*`) | 요청마다 `krasApiClient.query()`를 **실시간 호출**해 그대로 반환. DB 저장 없음, `ConnRequestLogService`로 메타(경로·PNU·상태·소요시간)만 기록 | `gateway/KrasConnController.java:93-108` |
| `KrasGmxController`(`/svc/*`) | 여러 `/conn` 호출을 병렬로 묶어 `<GMX>`로 래핑. 역시 실시간 패스스루, DB 저장 없음 | `gateway/KrasGmxController.java:253-299` |
| `KrasWorker` | SHAPE 배치 로더. `directLoadTable()`이 `apiClient.downloadLayer()`로 SHP 받아 `workspaceScanner`로 파싱 후 `odsRepository.replaceAllTo()`로 `public`/`ods` 스키마에 적재 | `synchronization/KrasWorker.java:505-555` |
| `KrasTxtLoaderService` | TXT 배치 로더. `krasApiClient.downloadJigaTxt/LandTxt()` → 파싱 → `ods.anvm_jiga`/`ods.land_frst_ledg`에 적재 | `kras/KrasTxtLoaderService.java` |
| `OdsRepository.replaceAllTo` | **기관 컬럼 있으면 `DELETE WHERE org_cd=?`, 없으면 테이블 전체 `DELETE`, 그 다음 batch INSERT.** `@Transactional` 없음 — DELETE와 INSERT가 원자적이지 않음 | `ods/OdsRepository.java:154-165` |
| `SyncScheduler`/`DynamicScheduleManager` | `kras.schedule` cron이 `KrasWorker.runScheduledLoad()`(SHAPE)를 호출. TXT/PNU 단건은 스케줄 없음 | `synchronization/DynamicScheduleManager.java:62-69` |
| `DatabaseConnectionService` | 대상 DB를 런타임에 바꿀 수 있는 커넥션 풀 홀더. `acquire()`로 `DatabaseSession`(`JdbcTemplate` 래퍼) 획득 | `database/DatabaseConnectionService.java` |

**결론: `kras` 스키마에 쓰는 코드는 이 프로젝트에 하나도 없다.** SHAPE/TXT는 옛날 방식(`public`/`ods`에 전체 삭제 후 재삽입)으로 배치가 있고, PNU 단건 19개 서비스는 배치 자체가 없다 — `/conn`,`/svc`는 캐시나 저장 없이 매번 KRAS를 직접 호출한다.

## 2. 설계 범위와 판단

19개 PNU 서비스 + SHAPE 2종 + TXT 2종을 한 번에 설계·구현하는 건 비현실적이다. **연속지적(SHAPE) 한 종을 1단계로 끝까지 관통시키고, 나머지는 같은 패턴을 반복 적용하는 청사진만 제시한다.** 연속지적을 고른 이유:

- DB 쪽(`kras.spatial_feature → cadastral_feature → sync_publication → kras.sync_public_cadastral()`)이 이미 이번 세션에서 만들어지고 검증됨
- `public.lp_pa_cbnd`는 의존 뷰가 없어 GeoServer 리스크가 가장 낮음(`lt_c_uzone`은 306개 뷰라 2단계로 미룸)
- `KrasWorker`가 이미 SHP 다운로드·파싱 코드를 갖고 있어 재사용 폭이 큼

## 3. 공통 원칙

1. **`KrasApiClient`는 그대로 재사용한다.** HTTP 호출·인증·파싱은 이미 있는 걸 쓰고, 새로 만드는 건 "받은 데이터를 kras 스키마 가드 파이프라인에 맞게 적재하는" 계층뿐이다.
2. **대상 DB는 항상 `DatabaseSession`을 통해 얻는다.** `OdsRepository`처럼 `JdbcTemplate`을 파라미터로 받는 패턴을 유지하되, 이 DB가 애플리케이션이 런타임에 바꿀 수 있는 대상이라는 전제를 유지한다(`DatabaseConnectionService.acquire()`).
3. **`sync_run`→`sync_item`→(원본/스테이징)→업무 테이블→게시**의 순서는 절대 건너뛰지 않는다. DB 쪽 가드 트리거(`guard_business_row`, `guard_item_transition`, `guard_sync_publication` 등)가 이미 순서를 강제하므로 애플리케이션 코드가 이 순서를 지키지 않으면 예외로 막힌다 — 이게 의도된 동작이다.
4. **한 데이터셋의 적재는 하나의 DB 트랜잭션**이어야 한다. 지금 `OdsRepository.replaceAllTo`는 `@Transactional`이 없어 DELETE와 INSERT 사이에 크래시하면 테이블이 빈 채로 남는다 — kras 파이프라인에서는 이 문제를 반복하지 않는다. 다만 대상 DB가 런타임에 바뀌는 `JdbcTemplate`이라 Spring의 `@Transactional`(기본 트랜잭션 매니저 기준)을 그대로 못 쓴다.
   `DatabaseSession`을 확장할 필요는 없다 — `JdbcTemplate.execute(ConnectionCallback<T>)`가 콜백 1회 동안 원본 `java.sql.Connection`을 그대로 넘겨준다는 걸 재확인했다. 콜백 안에서 `conn.setAutoCommit(false)` 하고, 그 `conn`으로 `new JdbcTemplate(new SingleConnectionDataSource(conn, true))`를 하나 더 만들어 안에서는 평소처럼 `jdbc.update(...)`/`queryForObject(...)`를 쓰다가 성공하면 `conn.commit()`, 예외 시 `conn.rollback()`하면 된다. 이렇게 하면 `KrasCadastralIngestService` 내부에만 있는 지역적인 처리로 끝나고, 기존 `DatabaseConnectionService`/`DatabaseSession`은 한 줄도 안 건드린다.
5. **기존 동기화 경로는 절대 건드리지 않는다.** `KrasWorker`, `OdsRepository`, `SyncScheduler`, `DynamicScheduleManager`의 기존 메서드·스케줄·호출부는 전부 지금 상태 그대로 둔다. 신규 kras 적재는 이 경로들과 코드 레벨에서 아예 분리된 **새 클래스 + 새 컨트롤러 + 새 화면**으로만 존재한다 — 연계 중 문제가 생겨도 기존에 돌고 있는 SHAPE/TXT 동기화나 `/conn`,`/svc` 실시간 조회에는 영향이 없다.

## 4. 1단계: 연속지적(SHAPE) 적재 — `KrasCadastralIngestService`

새 클래스 `geosync.kras.KrasCadastralIngestService`. **`KrasWorker`는 건드리지 않는다** — SHP 다운로드·파싱 코드(`apiClient.downloadLayer()`, `workspaceScanner.loadShpFile()`)만 재사용하고, 저장 대상(`odsRepository.replaceAllTo(...)`)은 그대로 둔 채 이 신규 서비스가 **같은 원본 파일을 별도로 한 번 더 읽어 kras 스키마 쪽에 독립적으로 적재**한다. 기존 스케줄이 만든 로컬 SHP 파일(`settings.krasWorkDir()/{orgCode}/*.shp`)을 그대로 활용하거나, 신규 탭에서 누른 "수집" 버튼이 그 시점에 다시 다운로드한다 — 어느 쪽이든 기존 `directLoadTable()` 호출 흐름 안에는 이 신규 서비스 호출을 넣지 않는다.

### 입력
`KrasWorker`가 이미 갖고 있는 것과 같은 모양: `List<Map<String,Object>> rows`(`workspaceScanner.loadShpFile()` 결과), `orgCode`, `layerCode`, 소스 EPSG(5174, `KrasWorker.KRAS_EPSG`), 대상 `JdbcTemplate`(`TargetDbService.getConfiguredTargets().get(0).jdbc()` — 기존 `KrasWorker`/`ConnRequestLogService`/`SyncExecutionLogService`와 동일한 DB, `TargetDbService`가 사실상 `DatabaseConnectionService.acquire()`의 얇은 파사드임을 코드로 확인했다).

### 절차 (한 트랜잭션)

```
1. kras.spatial_layer 존재 확인/등록
   INSERT ... ON CONFLICT(layer_code) DO NOTHING
   (dataset_code='cadastral_file', source_epsg=5174, target_epsg=5186)

2. kras.sync_run 생성 → run_id
   (org_cd, job_kind='SCHEDULE'|'MANUAL', period_start/end = 오늘 하루)

3. kras.sync_item 생성 → item_id
   (run_id, org_cd, dataset_code='cadastral_file',
    scope_key='LAYER:'+layerCode, window_start/end = 오늘 하루)
   → 기본 status='PLANNED' (guard_item_transition이 INSERT 시 SUCCESS 직접 지정을 막음)

4. rows를 kras.spatial_feature에 batch INSERT
   (item_id, feature_no=1..N, org_cd, layer_code, pnu, properties=row 전체를 jsonb로,
    geom=ST_Transform(ST_GeomFromText(?,5174),5186))

5. kras.cadastral_feature를 spatial_feature에서 SELECT로 채운다 (WKT를 두 번 파싱하지 않음 —
   guard_geometry가 ST_Equals로 두 테이블의 geom이 "정확히" 같은지 검사하므로,
   부동소수점 재파싱으로 인한 미세한 불일치를 원천 차단하기 위함)
   INSERT INTO kras.cadastral_feature(item_id,feature_no,org_cd,layer_code,pnu,jibun,bchk,geom)
     SELECT item_id,feature_no,org_cd,layer_code,pnu, <row.jibun>, <row.bchk>, geom
     FROM kras.spatial_feature WHERE item_id=?

6. kras.sync_item을 UPDATE로 SUCCESS 전환
   SET status='SUCCESS', rows_received=N, rows_valid=N, rows_rejected=0, is_complete=true
   (전제조건: kras.sync_dataset.dataset_code='cadastral_file'가 enabled=true,
    contract_status='VERIFIED'여야 guard_item_transition을 통과한다 — 지금은 UNVERIFIED라 여기서 막힘)

7. kras.sync_publication upsert
   INSERT ... ON CONFLICT(org_cd,dataset_code,scope_key) DO UPDATE SET item_id=EXCLUDED.item_id
   (guard_sync_publication이 완전성·역행 여부를 검증)

8. SELECT kras.sync_public_cadastral();
   → public.lp_pa_cbnd로 승격 (0건이면 예외 — 이미 검증된 안전장치)

COMMIT
```

실패 시 전체 롤백 — `sync_item`도 같이 사라지므로 "반쯤 성공한 item"이 남지 않는다. 실패한 시도의 흔적이 필요하면(재시도 추적용) 4~8을 트랜잭션으로 묶고 1~3은 별도 트랜잭션으로 먼저 커밋하는 방식도 가능하지만, 지금 단계에서는 "전부 성공 아니면 전부 없음"이 단순하고 안전하다.

### 이 단계를 실제로 돌리기 전에 필요한 것

`kras.sync_dataset`에서 `cadastral_file`을 `contract_status='VERIFIED', enabled=true`로 바꿔야 한다(6번 단계 전제조건). 지금은 33개 전부 `UNVERIFIED`다 — 이건 코드 변경이 아니라 **운영 계약 확인 후의 DB 설정 변경**이다.

## 5. UI: 신규 탭 "kras 적재(신규)"

기존 화면(`schedule.html`, `ods.html` 등)은 전혀 안 건드리고, 완전히 새 경로·새 컨트롤러·새 템플릿으로만 추가한다.

### 5.1 nav 추가

`layout.html`의 nav 목록(46~71행) 끝에 기존 탭과 동일한 패턴으로 한 줄 추가:

```html
<a th:href="@{/kras-db}"
   th:style="${currentPage == 'kras-db'} ? 'padding:8px 14px;border-radius:8px;font-weight:600;color:#66c4be;background:rgba(14,155,148,0.16);' : 'padding:8px 14px;border-radius:8px;font-weight:500;color:#8a919c;'">
  kras 적재(신규)</a>
```

기존 상단의 "KRAS 실행중/대기" 배지(`layout.html:36-44`)는 `statusService.isRunning("KRAS")`/`"KRAS_LOAD"`만 본다 — 신규 탭의 실행 상태는 이 배지와 **별개**로 관리한다(아래 5.2). 신규 작업이 돌아도 기존 배지에는 안 뜨고, 반대로 기존 작업이 돌아도 신규 탭 안에서는 안 뜬다 — 서로 상태를 안 섞는다.

### 5.2 신규 컨트롤러: `geosync.kras.KrasSchemaController`

```
@GetMapping("/kras-db")            페이지 렌더
@PostMapping("/kras-db/verify-dataset")   cadastral_file을 VERIFIED+enabled로 전환 (확인 필수)
@PostMapping("/kras-db/ingest/cadastral") 수집만 (spatial_layer~sync_item SUCCESS, 절차 1~6)
@PostMapping("/kras-db/promote/cadastral") 승격만 (sync_publication~sync_public_cadastral(), 절차 7~8)
```

**수집과 승격을 버튼 2개로 나눈다.** 절차 1~6(수집)과 7~8(승격)을 하나로 합쳐 자동 실행하지 않고, 운영자가 "kras.lp_pa_cbnd 건수가 이 정도면 맞다"를 눈으로 확인한 뒤에만 `public.lp_pa_cbnd`(실제 GeoServer 테이블)를 건드리는 승격 버튼을 누르게 한다. 신규 경로에서 연계 오류가 날 수 있다는 우려를 정확히 이 지점 — **살아있는 GeoServer 테이블을 건드리기 직전** — 에서 사람이 한 번 끊는다.

`GET /kras-db` 모델 속성:

| 속성 | 내용 | 조회 |
|---|---|---|
| `datasetVerified` / `datasetEnabled` | `cadastral_file`의 `contract_status`/`enabled` | `SELECT contract_status,enabled FROM kras.sync_dataset WHERE dataset_code='cadastral_file'` |
| `krasCount` | `kras.lp_pa_cbnd` 현재 건수 | `SELECT count(*) FROM kras.lp_pa_cbnd` |
| `publicCount` | `public.lp_pa_cbnd` 현재 건수 | `SELECT count(*) FROM public.lp_pa_cbnd` |
| `recentRuns` | 최근 실행 이력 | `kras.sync_item WHERE dataset_code='cadastral_file' ORDER BY item_id DESC LIMIT 20` |
| `ingestRunning` | 신규 탭 전용 실행중 플래그 | 컨트롤러의 `AtomicBoolean`(신규, `SyncStatusService`와 무관) |

실행 중복 방지는 기존 `SyncScheduler`의 `krasLoadRunning` 패턴을 그대로 따르되(§SyncScheduler.java:23-25 참고) **별개의 `AtomicBoolean`**을 새로 둔다 — 기존 `KRAS_LOAD` 실행 여부와 신규 탭 실행 여부가 서로를 막지 않게(둘 다 같은 DB에 쓰지만 대상 테이블이 다르므로 동시 실행 자체는 안전하다).

### 5.3 신규 템플릿: `kras-db.html`

`schedule.html`과 같은 데코레이터·카드 스타일(`layout:decorate="~{layout}"`, `.card`)을 그대로 재사용해 화면 톤을 맞춘다. 카드 4개:

1. **데이터셋 계약 상태** — VERIFIED/enabled 배지. 미검증이면 "실제 운영 응답으로 계약을 확인했음" 체크박스 + 활성화 버튼 (이 버튼은 사람이 계약을 확인했다는 선언이라 설명 문구를 명확히 둔다).
2. **kras ↔ public 건수 비교** — `krasCount` / `publicCount`를 나란히 두고 차이를 강조. 승격 전 상태를 한눈에 보게 한다.
3. **실행** — [수집 실행] [승격 실행] 버튼. `ingestRunning`이면 버튼 비활성화. 직전 실행 결과(성공/실패, item_id, 처리 건수, 오류 메시지)를 그 아래에 표시.
4. **최근 실행 이력** — `recentRuns`를 표로. `item_id, status, rows_valid, rows_rejected, started_at`.

버튼 클릭은 `fetch()`로 POST 후 JSON 응답을 같은 페이지에서 렌더 — `SyncController.mockLoad()`가 이미 쓰는 "POST → `@ResponseBody` JSON → JS로 결과 표시" 패턴을 그대로 따른다(새 패턴을 안 만든다).

## 6. 2단계: PNU 단건 19개 서비스 — `land_info` 하나를 실제 필드까지 관통

DB 쪽 준비(`kras.stage_*`, `business_dataset`, `guard_business_row`)는 이미 되어 있고, 애플리케이션 쪽은 완전히 새로 만들어야 한다. 19개를 한 번에 설계하는 대신 **§1 `land_info`(→ `parcel`/`land_register`/`land_owner`) 하나를 태그 레벨까지 구체화**하고, 나머지 16개(§11·12 제외)는 같은 틀을 반복 적용한다.

### 6.1 PNU 전수 목록 소스

기존 `public.land_frst_ledg`(TXT 적재 결과)를 최초 PNU census로 쓴다 — 이미 매일 갱신되는 기관 전체 목록이라 새로 만들 필요가 없다. `kras.parcel`이 채워지기 시작한 뒤에는 거기서 순회해도 되지만, 최초 구축 단계에서는 `land_frst_ledg`가 더 빠르다.

### 6.2 `KrasXmlServiceMapper` — `land_info` 구현 예시

```java
public interface KrasXmlServiceMapper {
    String datasetCode();                 // "land_info"
    String connSvcId();                   // "KRAS000002" (GatewayPaths.KRAS.get("land_info")와 동일 값)
    List<StageRow> map(Document xml, String pnu);
}
public record StageRow(String stageTable, Map<String, Object> columns) {}
```

`TableMapper`/`SyncTableDef`(SHP용)는 컬럼 나열 방식이라 중첩 XML(`LAND_RGT_SET > LAND_RGT` 같은 반복 구조)엔 안 맞는다 — 재사용하지 않고 서비스별 Java 클래스로 만든다. `land_info`(§1)는 반복 구조가 없는 평평한 응답이라 첫 구현 대상으로 적합하다.

`kras.md` §1 결과 XML(154~199행)을 그대로 태그 단위로 매핑:

| XML 태그 | stage 테이블 | 컬럼 | 비고 |
|---|---|---|---|
| `ADM_SECT_CD`,`LAND_LOC_CD`,`LEDG_GBN`,`BOBN`,`BUBN` | `stage_parcel` | `adm_sect_cd,land_loc_cd,ledg_gbn,bobn,bubn` | `pnu`는 XML에 없음 — 요청 파라미터의 PNU를 그대로 사용, 5개 태그 조합과 일치하는지 승격 전에 대조 |
| `JIMOK`,`JIMOK_NM`,`PAREA`,`GRD` | `stage_land_register` | `jimok,jimok_nm,parea,grd` | `PAREA`는 numeric 파싱 실패 시 stage에는 원문 그대로 두고 검증 단계에서 걸러냄 |
| `GRD_YMD`,`LAND_MOV_YMD` | `stage_land_register` | `grd_ymd,land_mov_ymd` | 빈 태그(`<GRD_YMD/>`)는 NULL. `YYYYMMDD`/`YYYY-MM-DD` 둘 다 허용, 그 외 형식은 파싱 실패로 stage에 원문 보존 + 검증 실패 처리(design doc §3.2 "잘못된 날짜를 정상 NULL로 처리하지 않음"과 동일 원칙) |
| `LAND_MOV_RSN_CD`,`LAND_MOV_RSN_CD_NM` | `stage_land_register` | `land_mov_rsn_cd,land_mov_rsn_cd_nm` | |
| `LEDG_CNTRST_CNF_GBN`,`BIZ_ACT_NTC_GBN`,`MAP_GBN` | `stage_land_register` | `ledg_cntrst_cnf_gbn,biz_act_ntc_gbn,map_gbn` | |
| `LAND_LAST_HIST_ODRNO`,`OWN_RGT_LAST_HIST_ODRNO` | `stage_land_register` | `land_last_hist_odrno,own_rgt_last_hist_odrno` | |
| `SCALE`,`SCALE_NM`,`DOHO`,`JIGA_BASE_MON`,`PANN_JIGA` | `stage_land_register` | `scale,scale_nm,doho,jiga_base_mon,pann_jiga` | |
| `LAST_JIBN`,`LAST_BU`,`LASTBOBN`,`LASTBUBN` | `stage_land_register` | `last_jibn,last_bu,lastbobn,lastbubn` | |
| `LAND_MOV_CHRG_MAN_ID`,`OWN_RGT_CHG_CHRG_MAN_ID` | `stage_land_register` | `land_mov_chrg_man_id,own_rgt_chg_chrg_man_id` | |
| `BLDG_GBN_NO` | `stage_land_register` | `bldg_gbn_no` | HWP `NUMBER(28)`이지만 `varchar(28)`로 저장(§2 검증에서 이미 확인한 원칙) — Java에서도 문자열로만 다루고 숫자 변환 금지 |
| `LAND_MOVE_RELL_JIBN` | `stage_land_register` | `land_move_rell_jibn` | |
| `OWNER_NM`,`DREGNO`,`OWN_GBN`,`OWN_GBN_NM` | `stage_land_owner` | `owner_nm,dregno,own_gbn,own_gbn_nm` | |
| `SHR_CNT`,`OWNER_ADDR` | `stage_land_owner` | `shr_cnt,owner_addr` | |
| `OWN_RGT_CHG_RSN_CD`,`OWN_RGT_CHG_RSN_CD_NM`,`OWNDYMD` | `stage_land_owner` | `own_rgt_chg_rsn_cd,own_rgt_chg_rsn_cd_nm,owndymd` | |

**`land_owner.availability` 판정은 이번 1차 구현에서 확정하지 않는다.** §1 항목표의 "소유내역 포함/불포함 옵션"이 실제 요청 파라미터로 뭔지 `KrasApiClient.query()`의 `extraParams`에 아직 없다 — 지금은 응답에 `OWNER_NM`이 비어있으면 `UNKNOWN`, 있으면 `PROVIDED`로만 채운다(design doc의 "명시적 근거 없이 상태 확정 금지" 원칙에 따라 `MASKED`/`NOT_REQUESTED`는 이 요청 옵션이 실제로 붙기 전까진 쓰지 않는다). 이 옵션 파라미터를 확인해 `extraParams`에 추가하는 건 열린 질문으로 남긴다(§8).

### 6.3 `KrasPnuIngestService` — 실행 절차

```
입력: pnu, mapper(land_info면 위 매퍼), 대상 JdbcTemplate

1. normalized_params 구성 (land_info는 옵션 없음 → '{}')
2. scope_key = SELECT kras.entity_key(normalized_params)
   (DB 함수를 그대로 호출 — 해시 로직을 Java에서 재구현해 계산이 어긋나는 걸 방지)
3. 오늘자 kras.sync_run이 이미 있으면 재사용(여러 PNU를 한 run에 모음), 없으면 생성
4. kras.sync_item 생성 (run_id, org_cd, dataset_code='land_info', scope_key, window=오늘)
5. krasApiClient.query("KRAS000002", pnu, null) 호출 (GatewayPaths.KRAS.get("land_info")와 동일 서비스 ID,
   기존 /conn/land_info가 쓰는 것과 완전히 같은 메서드 — 여기서도 KrasApiClient는 안 건드림)
6. mapper.map(xml, pnu) → StageRow 목록
7. 각 StageRow를 kras.stage_parcel / stage_land_register / stage_land_owner에 INSERT
   (item_id, row_no=1 — PNU 단건이라 항상 1행)
8. kras.sync_item을 UPDATE로 SUCCESS 전환
   (전제조건: sync_dataset.dataset_code='land_info'가 VERIFIED+enabled — 5.3 계약 확인 버튼과 동일한 개념,
    land_info 전용으로 하나 더 필요)
COMMIT (1~8)

--- 여기서 멈춘다. 승격은 별도 호출 ---

9. (승격, 별도 트랜잭션) 부모→자식 순서로 stage → 업무 테이블
   a. kras.parcel: INSERT ... ON CONFLICT(pnu) DO UPDATE SET ... FROM kras.stage_parcel WHERE item_id=?
   b. kras.land_register: INSERT ... ON CONFLICT(pnu) DO UPDATE ... (PK가 pnu라 1:1 upsert 가능 — 이미 DDL에서 확인)
   c. kras.land_owner: 위와 동일하게 ON CONFLICT(pnu) DO UPDATE
COMMIT (9)
```

7~8(수집)과 9(승격)를 분리한 이유는 5.2와 같다 — PNU 하나짜리 테스트라 블라스트 반경은 작지만, 같은 원칙을 처음부터 지켜서 배치로 커질 때도 그대로 재사용한다.

### 6.4 나머지 16개 서비스

같은 틀(`KrasXmlServiceMapper` 구현 + `business_dataset` 매핑 확인 + stage 테이블 target)을 반복한다. 설계문서 §9.1 매핑표(19개 경로 ↔ 업무 모델)가 이미 있으므로 서비스별로 새로 조사할 건 "반복 구조가 있는지"(예: §6 대지권등록부는 `LAND_RGT_SET`/`OWN_HIST` 두 그룹이 한 응답에 같이 옴 — `land_info`보다 매퍼가 복잡함) 정도다. §11·12(소유권 변동)는 여전히 제외 — 원문 계약 미확보 상태가 안 바뀌었다.

### 6.5 UI 확장

같은 `kras-db.html`에 카드 하나만 추가한다(새 탭을 또 만들지 않는다):

**카드 "PNU 단건 테스트 (land_info)"**
- PNU 입력창(19자리) + [테스트 수집] 버튼 → `KrasPnuIngestService` 1~8단계만 실행, 응답으로 파싱된 stage 값(파싱 결과 미리보기: JIMOK, PAREA, OWNER_NM 등)을 그대로 화면에 보여준다 — 승격 전에 사람이 값을 눈으로 확인하게.
- [업무 테이블로 승격] 버튼 → 9단계만 실행.
- 서비스가 늘어나면(land_register 외 추가 매퍼) 같은 카드에 드롭다운으로 서비스 선택을 추가 — 지금은 `land_info` 하나뿐이라 드롭다운을 미리 만들지 않는다.

**전체 PNU 순회(배치)는 이번에도 설계하지 않는다.** PNU 단건 테스트가 여러 번 문제없이 돌아간 뒤에, "몇 건씩 나눠서 순회 + 재시작 가능한 큐"를 별도로 설계한다 — 지금 만들면 검증 안 된 매퍼로 기관 전체를 훑는 리스크를 안게 된다.

## 7. 이번 설계에서 다루지 않은 것 (남은 것만)

§9~14에서 대부분을 마저 설계했다. 진짜로 이 문서 밖에 남는 건 세 가지뿐이다:

- **실제 코드 작성** — 이 문서는 여전히 설계안이고 코드 변경은 없다.
- **운영 응답 실물 확인** — `api_contract`의 `success_code`/실패 코드, 소유내역 옵션 파라미터명 등 "실제 KRAS가 뭐라고 응답하는지" 자체는 문서 작업으로 알아낼 수 없다(§15에 목록화).
- **19개 서비스 각각의 태그 매핑표 전체 작성** — §13에서 난이도만 분류하고, 실제 표는 구현 직전에 서비스 하나씩 추가하기로 했다(지금 16개를 한 번에 쓰면 문서가 감당 안 됨).

## 8. stage_* → 업무 테이블 승격 범용 설계

39개 stage 테이블을 39가지 방식으로 승격시킬 순 없다. DB가 이미 강제하는 제약(어떤 테이블이 DELETE 금지인지 — `guard_business_row`, 어떤 테이블이 후보 자연키에 UNIQUE가 없는지 — design 원칙)에 따라 실제로는 **패턴 4가지**로 수렴한다.

### 8.1 패턴 분류

| 패턴 | 판별 기준 | 승격 방식 | 대상(예) |
|---|---|---|---|
| A. 자연키 UPSERT | PK 자체가 자연키(`pnu` 등) | `INSERT ... ON CONFLICT(자연키) DO UPDATE` | `land_basic`,`land_register`,`land_owner`,`land_presence`,`land_price` |
| B. 범위 전체 교체 | surrogate PK, 후보 자연키 UNIQUE 없음, `guard_business_row`의 DELETE 차단 목록(`parcel`,`building_register`,`building_unit`,`collective_building`,`collective_unit`)에 **없음** | `DELETE FROM <table> WHERE <부모scope>=?` 후 `INSERT` — 부모+서비스 범위 전체를 이번 item 결과로 교체 | `land_share`,`land_right`,`unit_ownership_history`,`land_movement_history`/`relation`,`land_ownership_history`,`building_title`,`building_floor`,`building_title_owner`/`change`,`building_exclusive_area`/`owner`/`price` |
| C. 신원 매칭 후 UPSERT | surrogate PK, DELETE 차단 목록에 **있음**(삭제 대신 상태 변경만 허용) | 후보 자연키로 기존 행 검색 → 있으면 UPDATE, 없으면 INSERT (surrogate ID 보존) | `collective_building`,`collective_unit`,`building_register`,`building_unit` |
| D. 순수 append | 자연키 없음, 매 수집이 새 사건 | `INSERT`만(중복은 `UNIQUE(source_item_id,record_no)`가 막음) | `land_change_event` |

패턴 B와 C를 가르는 기준은 **"이 surrogate ID를 외부에서 참조하는 자식이 있는가"**다. `collective_building_id`는 `collective_unit`이, `unit_id`는 `land_right`가 참조한다 — ID가 매번 바뀌면 자식 관계가 끊긴다. 반면 `land_share`·`land_right` 자체는 아무도 참조하지 않는 리프 데이터라 통째로 지우고 다시 넣어도 무해하다. `guard_business_row`가 DELETE를 차단하는 5개 테이블이 정확히 "누군가 참조하는 부모" 목록과 일치한다는 걸 이번에 대조해서 확인했다 — 우연이 아니라 그 이유로 DDL이 그렇게 짜여 있다.

### 8.2 패턴 A — 이미 §6.3에서 구체화함
`kras.land_register`, `kras.land_owner` — `ON CONFLICT(pnu) DO UPDATE`.

### 8.3 패턴 B 예시 — `kras.land_share`

```sql
DELETE FROM kras.land_share WHERE pnu = (SELECT pnu FROM kras.stage_land_share WHERE item_id=? LIMIT 1);
INSERT INTO kras.land_share(pnu,org_cd,shr_seqno,own_rgt_chg_rsn_cd,own_rgt_chg_rsn_nm,own_rgt_chg_ymd,
    owner_regno,owner_nm,owner_addr,own_rgt_jibun,own_rgt_chg_del_ymd,own_gbn,own_gbn_nm,parea,source_item_id)
  SELECT pnu,org_cd,shr_seqno,own_rgt_chg_rsn_cd,own_rgt_chg_rsn_nm,own_rgt_chg_ymd,
    owner_regno,owner_nm,owner_addr,own_rgt_jibun,own_rgt_chg_del_ymd,own_gbn,own_gbn_nm,parea,item_id
  FROM kras.stage_land_share WHERE item_id=?;
```

`land_share`는 DELETE 차단 목록에 없어 DELETE가 허용된다(DDL로 확인됨). 한 PNU의 공유인 응답은 §2 서비스 자체가 매번 "그 시점 전체 목록"을 반환하는 구조이므로 부분 갱신이 아니라 전체 교체가 맞다.

### 8.4 패턴 C 예시 — `kras.collective_building`

```sql
-- 1) 후보 자연키(pnu + cbldg_seqno)로 기존 행 탐색
SELECT collective_building_id INTO existing_id
  FROM kras.collective_building WHERE pnu=? AND cbldg_seqno=?;

-- 2-a) 있으면 UPDATE (surrogate ID 보존 — collective_unit이 참조 중일 수 있음)
UPDATE kras.collective_building SET cbldg_nm=?, last_seen_item_id=?
  WHERE collective_building_id=existing_id;

-- 2-b) 없으면 INSERT (신규 건물)
INSERT INTO kras.collective_building(pnu,org_cd,cbldg_seqno,cbldg_nm,source_item_id)
  VALUES (?,?,?,?,?) RETURNING collective_building_id;
```

탐색·UPDATE·INSERT 세 문장이 한 애플리케이션 트랜잭션 안에서 순차 실행돼야 한다 — SELECT와 INSERT 사이의 경쟁 상태(같은 PNU를 동시에 두 번 승격하면 같은 건물이 중복 INSERT될 수 있음) 방지는 8.6의 기관별 직렬화가 담당한다.

### 8.5 패턴 D — `kras.land_change_event`

그냥 `INSERT`. `record_no NOT NULL` + `UNIQUE(source_item_id,record_no)`가 이미 중복을 막으므로 애플리케이션은 원본 그대로 넣기만 하면 된다.

### 8.6 Java 구현: `KrasStagePromotionService`

```java
public enum PromotionPattern { NATURAL_KEY_UPSERT, SCOPE_REPLACE, IDENTITY_MATCH_UPSERT, APPEND_ONLY }

public record StagePromotionSpec(
    String stageTable, String businessTable,
    PromotionPattern pattern,
    List<String> naturalKeyColumns,   // A: ON CONFLICT 충돌 컬럼 / C: 신원 매칭 컬럼
    String scopeColumn                // B: DELETE WHERE 기준 컬럼(pnu, unit_id 등)
) {}
```

서비스별 매퍼(`KrasXmlServiceMapper`)가 자신이 쓰는 stage 테이블마다 `StagePromotionSpec`을 같이 선언하고, `KrasStagePromotionService.promote(itemId, spec)`가 패턴에 따라 8.2~8.5의 SQL을 조립해 실행한다 — 서비스가 늘어나도 승격 로직 자체는 4가지로 수렴하므로 서비스마다 새로 안 짠다.

### 8.7 정합성 — 기관별 직렬화

design 문서 §8 "DB advisory lock은 같은 물리 연결에서 획득·해제" 원칙을 그대로 따른다. `KrasStagePromotionService.promote()`가 쓰는 것과 같은 커넥션으로 승격 시작 시:

```sql
SELECT pg_advisory_xact_lock(hashtextextended('kras-promote:'||org_cd||':'||pnu, 0));
```

`pg_advisory_xact_lock`은 트랜잭션 종료 시 자동 해제되므로, 탐색→UPDATE/INSERT 전체가 하나의 커넥션·하나의 트랜잭션 안에 있어야 락이 의미가 있다 — §3-4에서 설계한 `SingleConnectionDataSource` 패턴을 여기서도 그대로 재사용한다(새 메커니즘을 또 안 만든다).

## 9. `lt_c_uzone`(용도지역) 배치 — `spatial_release` 전체 워크플로

연속지적(1단계)과 달리 **기관 전체 레이어가 한 번에 검증돼야** 한다(F2 수정 사항 때문). 새 클래스 `KrasUsezoneIngestService`.

### 9.1 절차

```
0. (사전, 별도 버튼) 카탈로그 수집
   krasApiClient.layerList()(KrasWorker가 이미 쓰는 메서드, isUsezoneLayer()로 필터링하는 로직도 재사용)로
   받은 레이어 목록을 dataset_code='layer_list' sync_item + sync_record
   (payload={"dataset_code":"usezone_file","layer_code":"..."})로 저장, item을 SUCCESS 전환

1. kras.spatial_release 생성 (status=DRAFT, catalog_item_id=0번 item)
2. kras.spatial_release_expected에 레이어 목록 전체 등록
   (0번 카탈로그와 정확히 일치해야 함 — validate_spatial_manifest가 대조)
3. kras.seal_spatial_release(release_id) 호출 → READY (매니페스트 검증)
4. 레이어별로 순회 (1단계 연속지적과 거의 동일, dataset_code='usezone_file'):
   a. spatial_layer 등록/확인
   b. sync_item 생성 (scope_key='LAYER:'+layerCode)
   c. SHP 다운로드(krasApiClient.downloadLayer) → workspaceScanner 파싱 → spatial_feature INSERT
   d. usezone_feature를 spatial_feature에서 SELECT로 채움 (1단계와 동일하게 geom 재파싱 안 함)
   e. sync_item SUCCESS 전환 (usezone_file 데이터셋도 별도로 VERIFIED+enabled 필요)
   f. kras.spatial_release_member에 (release_id,layer_code,item_id) 등록
5. 전체 레이어 처리 후: kras.publish_spatial_release(release_id) 호출
   → 내부에서 validate_spatial_release()가 모든 레이어의 완전성(개수 일치·SUCCESS·rows_rejected=0)을 검증
   → 하나라도 실패하면 전체 release가 PUBLISHED 안 됨 — F2가 막던 바로 그 상황이 여기서 재현·차단됨
6. SELECT kras.sync_public_usezone(); → public.lt_c_uzone 승격
```

4번에서 중간에 실패하는 레이어가 있으면 5번에서 막히므로, 실패한 레이어만 다시 c~f를 돌리고 `release_id`는 그대로 재사용한다(아직 DRAFT/READY 상태라 `spatial_release_member`를 계속 추가·갱신 가능).

### 9.2 UI

`kras-db.html`에 카드 추가(새 탭 아님):

**카드 "용도지역 릴리즈 진행상태"** — 현재 release_id, 레이어별 상태 표(성공/대기/실패 배지), 버튼 **4개**(1단계의 2버튼보다 세분화): [카탈로그 수집] [레이어 순회 시작] [release 발행] [public 승격]. 레이어가 수십 개라 실패 지점이 다양하므로(28개 중 하나만 실패해도 5번이 막힘) 어느 단계에서 멈췄는지 바로 보이게 나눈다.

## 10. `/conn`,`/svc` 서빙 전환 — 그 전에 빠진 것부터

§6까지의 설계는 **원문 XML을 파싱해서 업무 컬럼으로 쪼개 넣는 것까지만** 다뤘다. `/conn`,`/svc`가 실제로 kras 데이터로 서빙하려면 §4.8의 `kras.api_response`/`api_bundle`/`api_publication`(원문 XML 자체를 보존·게시하는 계층)이 같이 채워져야 하는데, 이건 지금까지 빠져 있었다.

### 10.1 `KrasPnuIngestService`에 원문 보존 단계 추가

§6.3의 5번(`krasApiClient.query()` 호출) 직후, 파싱과 별개로 원문도 저장한다(호출은 여전히 한 번만 — 파싱용과 원문 저장용으로 두 번 안 부른다):

```
5. xml = krasApiClient.query(svcId, pnu, extra)
5-1. kras.api_response INSERT
     (source_item_id, org_cd, source_system='KRAS', service_code, pnu, bno,
      normalized_params, request_key=SELECT kras.request_key(dataset_code,contract_version,pnu,bno,normalized_params),
      dataset_code, contract_version, response_xml=xml 원문, source_result_code=xml에서 CODE 추출,
      data_state, response_hash=SELECT encode(sha256(convert_to(xml,'UTF8')),'hex'))
```

`guard_api_response` 트리거(4140~4184줄)가 엄격하게 검증한다 — **`kras.api_contract`가 먼저 등록·`verified=true`여야** 하고, XML의 `CODE`가 계약에 정의된 `success_code`와 정확히 일치해야 들어간다. 10.2가 먼저 되어 있어야 이 단계가 통과한다.

### 10.2 `kras.api_contract` 등록 — 계약 확인 버튼 확장

5.3(연속지적)·6.3(land_info)의 "계약 확인" 버튼은 지금까지 `sync_dataset`만 VERIFIED로 바꿨다. `/conn` 서빙까지 하려면 같은 화면에서 `kras.api_contract` 행도 같이 등록해야 한다:

```sql
INSERT INTO kras.api_contract(dataset_code, contract_version, success_code, code_xpath, empty_xpath, verified)
  VALUES ('land_info', '1', '0000', '/RESPONSE/HEADER/CODE/text()', '/RESPONSE/BODY[not(*)]', true);
```

`success_code`/`code_xpath`/`empty_xpath` 기본값은 `kras.md` §1 예시(`<CODE>0000</CODE>`,`<MESSAGE>SUCCESS</MESSAGE>`)를 근거로 잡았지만, 실패 코드가 실제로 뭔지·`empty_xpath`가 진짜 정상 빈 응답을 구분하는지는 운영 응답을 봐야 한다 — §14로 넘김.

### 10.3 `/svc` 조합용 `api_bundle`

`GetTojiDaejangPrint`처럼 여러 서비스를 묶는 `/svc`는 `api_bundle`+`api_bundle_request`+`api_bundle_member`가 필요하다(`guard_bundle_child`, 4186~4239줄이 순서를 강제). §6은 PNU 1건에 서비스 1개(`land_info`)만 다뤄서 아직 필요 없다 — §11(PNU 전체 순회)에서 한 PNU에 여러 서비스를 같이 돌리게 되면 그때 `api_bundle_profile`(4195~4213줄에 `GetTojiDaejangPrint` 등 19개 조합이 이미 등록돼 있음)을 그대로 활용해 조합한다.

### 10.4 실제 서빙 전환 — 기존 컨트롤러는 안 건드림

`KrasConnController`/`KrasGmxController`를 고치지 않는다(원칙 §3-5 그대로). 대신:

- 새 엔드포인트 `GET /kras-db/preview-response?path=land_info&pnu=...` — `kras.api_current_response`에서 읽어 결과를 `kras-db.html`에 미리보기로만 보여준다.
- 기존 `/conn/land_info` 응답과 나란히 놓고 XML을 사람이 눈으로 비교하는 용도.

**실제 `/conn` 트래픽을 kras로 돌리는 건 이 설계 문서 범위 밖으로 계속 남긴다.** 이건 미리보기가 여러 번 일치하는 걸 확인한 뒤 결정할 운영 판단이지, 지금 설계할 기술 과제가 아니다.

## 11. 스케줄 자동화 설계

### 11.1 원칙

기본은 계속 비활성(수동 트리거만). "켜면 어떻게 동작하는가"만 미리 설계해둔다 — 나중에 실제로 켤 때 추가 설계 없이 설정값만 바꾸면 되게.

### 11.2 신규 스케줄 항목

`application.yml`에 `kras.db-sync` 블록을 신규 추가(기존 `kras.schedule`은 안 건드림):

```yaml
kras:
  db-sync:
    cadastral-schedule: ${KRAS_DB_CADASTRAL_SCHEDULE:}   # 비어있으면 비활성
    usezone-schedule: ${KRAS_DB_USEZONE_SCHEDULE:}
    pnu-schedule: ${KRAS_DB_PNU_SCHEDULE:}
```

`DynamicScheduleManager`에 이 3개를 위한 `ScheduledFuture` 3개를 추가 — 기존 `krasTask`/`fileDownloadTask` 옆에 나란히, 같은 `isInterval`/`isValid` 파싱 로직을 재사용. 트리거 대상은 기존 `SyncScheduler`가 아니라 새 `KrasDbSyncScheduler`(신규 클래스)다 — 기존 `SyncScheduler` 파일 자체에 메서드를 추가하지 않는다(원칙 §3-5를 스케줄러 레벨에서도 지킨다).

### 11.3 PNU 전체 순회 배치 — `kras.sync_work` 큐 활용

design 문서가 이미 `kras.sync_work`를 "필지/전유부 등 재조회 대상, 상태, 시도횟수, 다음 재시각, lease 만료시각"으로 정의해뒀다(DDL 157~172줄) — 새로 안 만들고 그대로 쓴다.

```
1. (최초 1회, 수동) land_frst_ledg 전체 PNU를 kras.sync_work에 채워넣기
   INSERT INTO kras.sync_work(item_id, target_dataset, entity_key, request_params)
     SELECT <오늘의 카탈로그 item_id>, 'land_info', kras.entity_key(jsonb_build_object('pnu',pnu)),
            jsonb_build_object('pnu',pnu)
     FROM public.land_frst_ledg
   ON CONFLICT(item_id,target_dataset,entity_key) DO NOTHING

2. 배치 워커(신규 KrasWorkQueueRunner)가 주기적으로:
   SELECT * FROM kras.sync_work
     WHERE target_dataset='land_info' AND status IN ('PENDING','RETRY') AND next_retry_at <= now()
     ORDER BY next_retry_at LIMIT 200
     FOR UPDATE SKIP LOCKED   -- 여러 워커/재시작이 겹쳐도 같은 PNU를 중복 처리 안 함
   각 행에 대해:
   - status='RUNNING', lease_expires_at=now()+5분, worker_id=이 프로세스 식별자로 UPDATE
   - KrasPnuIngestService.ingest(pnu) 실행 (§6.3 1~9단계 전부, 수집+승격 자동 연속 실행)
   - 성공: status='SUCCESS'
   - 실패: attempt_count+1, status = attempt_count<3 ? 'RETRY' : 'FAILED',
           next_retry_at=now()+지수백오프, last_error=메시지
```

`FOR UPDATE SKIP LOCKED`로 동시 실행 안전성을 얻는다 — 지금은 단일 인스턴스지만 나중에 여러 인스턴스로 늘어나도 안전한 큐 모양이다. 이건 실제로 켤 때까지는 코드 없이 설계만 남긴다(§11.1 원칙 유지).

### 11.4 UI

`kras-db.html`에 카드 추가: "PNU 순회 큐 현황" — `kras.sync_work` 상태별 건수(PENDING/RUNNING/SUCCESS/FAILED)와 [초기 큐 채우기] 버튼만 우선 노출. 실제 워커 실행 버튼은 스케줄이 켜지기 전까지 만들지 않는다(수동 전체 배치 실행은 리스크가 커서 11.1과 상충).

## 12. 나머지 16개 서비스 매퍼 — 구조별 분류

§1(`land_info`)은 평평한 구조라 이미 §6에서 끝냈다. 나머지 16개(§11·12 제외)를 `kras.md`에서 확인한 실제 XML 구조 기준으로 난이도별 분류한다 — 실제로 매퍼를 짤 때 이 순서대로 하면 된다.

| 난이도 | 서비스(§, 경로) | 구조 특징 |
|---|---|---|
| 쉬움(평평) | §3 `land_bldg_check`, §4 `collective_building`(건물조회), §13 `integrated_building` | `land_info`와 동일하게 반복 없는 단일 레코드 |
| 보통(단일 반복) | §2 `shr_ymb`, §5 `collective_unit`(전유부조회), §7 `land_mov_hist`, §8 `own_rgt_hist`, §9 `unit_ownership_history`(일부) | `SHR_YMB_SET > SHR_YMB` 같은 반복 그룹 하나 |
| 복잡(다중 반복) | §6 `land_right`(대지권등록부) | `LAND_RGT_SET` 안에 `LAND_RGT`(대지권부분)와 `OWN_HIST`(소유권연혁) **두 종류**가 같이 반복 — 매퍼가 반복 그룹을 종류별로 나눠 각각 다른 stage 테이블로 보내야 함 |
| 조합형 | §14 `building_image`, §15/16(파일 계열), KOREPS 5종, §26 `land_use_plan_info` | 응답 자체는 단순해도 이미지/파일/지도 옵션 같은 부가 파라미터가 얽힘. §14는 이미지 바이너리라 XML 매퍼가 아니라 파일 저장 로직이 필요(6.2의 `KrasXmlServiceMapper`와 다른 인터페이스) |

**KOREPS 5종**(`land_jiga`,`house_info`,`fin_dec_jiga`,`read_dec_jiga`,`land_attr`)은 매퍼 구조는 "쉬움"이지만 `korepsApiClient`(별도 클라이언트, `KrasApiClient`와 형제 클래스)를 통해야 한다 — `KrasPnuIngestService`가 `KrasApiClient`/`KorepsApiClient` 둘 다 받아 `source_system`에 따라 분기하도록 설계한다(`KrasGmxController.callGateway()`가 이미 이 분기를 하고 있으므로 그 로직 그대로 참고).

각 서비스의 정확한 태그 매핑표는 §6.2처럼 서비스마다 작성해야 하는데, 16개분을 한 번에 다 쓰면 문서가 감당 안 된다 — **다음 매퍼를 실제로 짤 때 그 서비스 하나만 §6.2와 같은 표로 이 문서에 추가**하는 방식으로 진행한다.

## 13. `land_owner.availability` — 지금 확정 가능한 만큼

§6.2에서 "요청 옵션을 모른다"고 남긴 문제를 설계 차원에서라도 안전하게 만든다.

`KrasApiClient.query()`의 `extraParams`(이미 있는 `Map<String,String>` 파라미터)에 소유내역 옵션을 담는 자리를 미리 마련해둔다 — 새 메서드를 안 만들고, 매퍼 호출부가 `extraParams.put("<실제 파라미터명, 확인 전까지 비워둠>", "Y")` 형태로 채우게만 설계해둔다.

매퍼(`LandInfoMapper`)의 `availability` 판정 로직은 3단계로 정정한다(이전 문서에서 "미확정이면 `UNKNOWN`"이라 했던 걸 고침):

1. 매퍼가 소유내역 옵션을 이번 요청에 **보냈는지 여부**는 자기가 만든 `extraParams`이므로 이미 안다.
2. **안 보냈으면 → `NOT_REQUESTED`.** 이건 지금 당장도 확정 가능하다 — "옵션을 안 보냈다"는 확실한 근거가 있으므로 `UNKNOWN`을 쓸 이유가 없다.
3. 보냈는데 `OWNER_NM`이 비어있으면 → `MASKED`, 값이 있으면 → `PROVIDED`.

즉 **옵션 파라미터명이 확인되기 전까지는 전부 `NOT_REQUESTED`로 채우는 게 맞다.** `UNKNOWN`은 정말 아무 근거가 없을 때만 쓰는 값이다.

## 14. 열린 질문 (실물 확인이 필요한 것만)

1. `cadastral_file`/`usezone_file`/§1~13 PNU 서비스들의 `contract_status`를 언제, 누가 `VERIFIED`로 바꾸는가 — 운영 응답 검증 절차가 코드 밖에 있어야 함. "계약 확인" 버튼이 이 결정을 대신하지 않는다 — 실제 검증은 사람이 먼저 하고, 버튼은 결과를 DB에 반영만 한다.
2. `kras.api_contract`의 `success_code`/실패 코드/`empty_xpath`(§10.2) — `kras.md` 예시만으로는 확정 못 함, 운영 응답 확인 필요.
3. 1단계(연속지적) 결과를 실제 GeoServer 레이어로 눈으로 확인하는 절차 — DB까지는 이번 세션에서 검증했지만 GeoServer 렌더링까지는 확인 안 됨.
4. 신규 탭의 "계약 확인" 버튼에 접근 권한 제한이 필요한가 — 지금 이 앱의 다른 화면(설정, 스케줄)도 별도 인증 없이 노출돼 있다면 동일 수준으로 두면 되는지, 아니면 이 조작만 더 보호할지 확인 필요.
5. `land_owner.availability`를 정확히 채우는 데 필요한 KRAS 요청 옵션(§13)의 실제 파라미터명 — 운영 쪽 확인 필요.
