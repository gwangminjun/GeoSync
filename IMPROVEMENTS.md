# geomex-sync 기능 점검 및 개선사항

> 점검일: 2026-06-25  
> 대상 기능: lt_c_uzone (KRAS API) / lp_pa_cbnd (SHP) 동기화, 스케줄링, 설정 관리

---

## 1. 버그 / 기능 결함

### [HIGH] srsName 요청값과 소스 좌표계 불일치

**파일**: `KrasWorker.java:676`, `KrasWorker.java:420`

`fetchFeatures()`에서 API 요청 시 `srsName: EPSG:5174`를 전송하지만, `sourceEpsg(def)`는 USEZONE 테이블에 대해 `storageEpsg` (5186)을 반환한다. 즉 코드는 API가 5186 좌표로 응답한다고 가정하고 변환을 생략하는데, 실제 API가 요청된 srsName(5174)대로 응답하면 기하 데이터가 잘못된 SRID로 저장된다.

```java
// 현재
req.put("srsName", "EPSG:" + KRAS_EPSG);  // KRAS_EPSG = 5174 전송
// 그런데 sourceEpsg()는:
return def.srcTableName.startsWith("USEZONE:") ? coordTransformer.getStorageEpsg() : KRAS_EPSG;
// USEZONE → 5186 반환 → ST_GeomFromText(wkt, 5186) → 변환 없이 저장
```

**확인 방법**: 실제 KRAS estateGateway의 GetFeature 응답 wkt 좌표값 범위 확인  
- EPSG:5174 중부원점: X ≈ 150,000~250,000 / Y ≈ 450,000~600,000 (TM 투영, 단위 m)  
- EPSG:5186: 비슷한 범위이나 원점 이동으로 Y 기준값 상이

**수정 방향**: API가 실제로 어떤 CRS로 응답하는지 확인 후,
- API가 5186으로 고정 응답 → `srsName` 파라미터를 `EPSG:5186`으로 변경하거나 제거
- API가 5174로 응답 → `sourceEpsg(def)`에서 USEZONE도 5174를 반환하도록 수정

---

### [HIGH] `SyncScheduler.syncEnabled` 정적 `@Value` 주입

**파일**: `SyncScheduler.java:25`

```java
@Value("${sync.enabled:true}")
private boolean syncEnabled;
```

설정 화면에서 값을 변경해도 반영되지 않는다. `RuntimeSettingsService`를 통해 동적으로 읽어야 한다.

**수정**:
```java
// 필드 제거 후 settings 주입, 각 run* 메서드에서:
if (!settings.syncEnabled()) return;
```

---

### [MID] `KrasWorker.run()` 경로에서 ODS 스키마 무시

**파일**: `KrasWorker.java:641`

`saveToTargets()`에서 `schemaOverride = null`로 고정 전달한다.

```java
odsRepository.replaceAllTo(target.jdbc(), def, settings.orgCode(),
        sourceEpsg(def), coordTransformer.getStorageEpsg(), rows, null, "KRAS");
//                                                                   ^^^^ null
```

`run()` 경로(수동 전체 동기화)를 사용하면 `ods.schema` 설정이 무시되고 기본 스키마에 저장된다.  
현재 핵심 경로(`runDirectLoad`)는 `ts.schema()`를 정상 전달하므로 직접적 영향은 없으나, `run()`을 활성화하면 문제가 된다.

---

### [MID] `KrasCatalogStatusService` 하드코딩된 레이어 기준값

**파일**: `KrasCatalogStatusService.java:81`

```java
boolean fileLoaded = complete >= 47;
// "46870_DATA_CATALOG 기준 47개 용도지역지구 레이어"
```

기관별로 용도지역지구 레이어 수가 다를 수 있다. 카탈로그 파일(`readCatalogLayers()`)에서 동적으로 읽은 개수를 사용해야 한다.

```java
List<CatalogLayer> layers = readCatalogLayers();
boolean fileLoaded = complete >= layers.size();
```

---

### [MID] `catalogPath`에 기관코드 하드코딩

**파일**: `KrasCatalogStatusService.java:41`

```java
@Value("${kras.catalog:./46870_DATA_CATALOG.md}")
private String catalogPath;
```

기관코드가 46870으로 고정되어 있다. `settings.orgCode()`를 이용해 동적으로 경로를 구성해야 한다.

---

### [MID] `SettingsController.countRows()` PreparedStatement 미사용

**파일**: `SettingsController.java:341`

```java
String sql = "SELECT COUNT(*) FROM " + quoteIdent(schema) + "." + quoteIdent(table)
        + (onlyOrg ? " WHERE org_cd = '" + settings.orgCode().replace("'", "''") + "'" : "");
```

`schema`, `table`은 `quoteIdent()`로 안전하게 처리되지만, `orgCode`는 문자열 치환(`replace("'","''")`)만 한다. PreparedStatement를 사용해야 한다.

```java
// 수정
String sql = "SELECT COUNT(*) FROM " + quoteIdent(schema) + "." + quoteIdent(table)
        + (onlyOrg ? " WHERE org_cd = ?" : "");
// 실행 시 ps.setString(1, settings.orgCode())
```

---

### [LOW] `isSynced()` 판단 로직 의미 반전 가능성

**파일**: `KrasCatalogStatusService.java:351`

```java
return latestModified != null && lastSuccess != null && !latestModified.isBefore(lastSuccess);
```

현재 로직: "파일 최종 수정 시각 ≥ 마지막 동기화 성공 시각"이면 true.  
이는 "동기화 후 파일이 새로 바뀌었다" = 아직 동기화 안 된 상태를 `isSynced = true`로 표시하는 것이다.  
의도가 "동기화가 파일보다 최신"이라면 조건이 반전되어야 한다.

```java
// "마지막 동기화가 파일 수정 이후" = 파일이 반영된 상태
return lastSuccess != null && latestModified != null && !lastSuccess.isBefore(latestModified);
```

대시보드에서 실제 표시 결과를 확인하고 의도에 맞게 수정할 것.

---

## 2. 코드 품질 / 설계 이슈

### [MID] HTTP 클라이언트 매 호출 생성

**파일**: `KrasWorker.java:714`

```java
private JsonNode post(ObjectNode body) throws Exception {
    try (CloseableHttpClient client = HttpClients.createDefault()) {  // 매번 생성
```

적재 1회 실행에서 레이어 수(예: 47개) × API 호출 횟수만큼 HttpClient를 생성/소멸한다. 커넥션 풀 없이 소켓을 반복 생성하므로 레이턴시 증가 및 리소스 낭비 발생.

**수정**: `CloseableHttpClient`를 Bean 또는 필드로 한 번만 생성하고 공유.

```java
private final CloseableHttpClient httpClient = HttpClients.createDefault();
// DisposableBean.destroy()에서 httpClient.close()
```

---

### [MID] 레거시 실행 경로 누적으로 인한 코드 복잡도

현재 존재하는 실행 경로:

| 메서드 | 역할 | 사용 여부 |
|--------|------|-----------|
| `run()` | API fetch + 파일 저장 + DB 적재 | 미사용 (SyncScheduler.runKras()) |
| `runCollect()` | API fetch + 파일 저장 | 미사용 (SyncScheduler.runKrasCollect()) |
| `runLoad()` | 파일 → DB 적재 | 미사용 (triggerKrasLoadAsync) |
| `runDirectLoad()` | API + SHP → DB 직접 (파일 없음) | **핵심 경로** (버튼 클릭, 스케줄) |

`run()`, `runCollect()`, `runLoad()`는 레거시 파이프라인으로, 현재 스케줄과 버튼 경로 모두 `runDirectLoad()`를 사용한다. `SyncScheduler`에도 `runKras()`, `runKrasCollect()`, `runKrasLoad()` 등 불필요한 메서드가 다수 남아 있다.

**권고**: 레거시 경로를 정리하거나 명시적으로 deprecated 처리.

---

### [MID] `OdsRepository.replaceAllTo()` 오버로드 6종

동일 메서드의 오버로드가 6개로, 각각 chain 호출 구조다. 파라미터 의미를 파악하기 어렵다.

**권고**: Builder 패턴 또는 파라미터 객체 도입.

```java
// 예시
record InsertRequest(SyncTableDef def, String orgCode, int sourceEpsg, int storageEpsg,
                     List<Map<String, Object>> rows, String schemaOverride,
                     String progressType, boolean tableAlreadyCreated) {}
```

---

### [LOW] `resolveInsertColumn()` 컬럼 누락 시 silent 스킵

**파일**: `OdsRepository.java:355`

대상 테이블에 컬럼이 없으면 조용히 null을 반환하여 해당 컬럼을 INSERT에서 제외한다. 예상치 못한 컬럼 누락이 있어도 로그가 없어 원인 추적이 어렵다.

```java
// 수정: null 반환 전 WARN 로그 추가
log.warn("[{}] 컬럼 '{}' 대상 테이블에 없음 — 스킵", def.tgtTableName, col.tgtName);
```

---

### [LOW] `KrasWorker` 클래스 비대 (728줄)

API 통신, 파일 처리 위임, DB 저장 위임, Mock 생성, 테스트 메서드가 한 클래스에 혼재한다.  
`testConnection()`, `testLayerList()`, `testFeatures()` 같은 테스트용 메서드를 별도 `KrasApiClient` 클래스로 분리하는 것을 권고한다.

---

### [LOW] `RuntimeSettingsService.snapshot()` 비원자적 갱신

**파일**: `RuntimeSettingsService.java:132`

```java
Snapshot current = snapshot;
if (current != null && current.modifiedTime() == modified) {
    return current;
}
// 여기서 다른 스레드가 동시에 진입 가능
Snapshot next = new Snapshot(modified, root);
snapshot = next;
```

두 스레드가 동시에 진입하면 YAML 파일을 두 번 파싱한다. 데이터 정확성에는 영향이 없지만 성능 낭비.  
`synchronized` 블록 또는 `AtomicReference` 사용 권고.

---

### [LOW] 미사용 메서드

| 파일 | 메서드 | 비고 |
|------|--------|------|
| `KrasCatalogStatusService.java` | `legacyStatus()`, `usezoneLayerStatuses()` | `getStatuses()`에서 미호출 |
| `SettingsController.java` | `extractYamlValue()` | 내부 미사용 |
| `OdsRepository.java` | `replaceAll()`, `buildInsertSql()` | 외부 미호출 |

---

## 3. UI/UX 이슈

### [MID] 스케줄 비활성화 방법 불명확

설정 화면에서 스케줄 필드를 비우면 저장 시 기본값 `0 30 4 * * *`으로 복원된다.

```java
// SettingsController.buildYaml():
.append("  schedule: ").append(yaml(krasSched.isEmpty() ? "0 30 4 * * *" : krasSched))
```

스케줄을 완전히 비활성화하려면 `-`를 입력해야 하나(`DynamicScheduleManager.isValid()` 참조), UI에 이 안내가 없다.

**권고**: 설정 화면에 "비활성화하려면 `-` 입력" 안내 추가, 또는 "스케줄 사용" 체크박스 추가.

---

### [MID] `/sync/status` 폴링에서 KRAS_LOAD만 모니터링

**파일**: `SyncController.java:41`

```java
var types = List.of("KRAS_LOAD");
```

KRAS (전체 동기화), KRAS_COLLECT (수집) 상태는 폴링에서 누락된다. 대시보드 네비바의 `krasRunning` 표시도 `"KRAS"` 타입에 의존(`DashboardController:29`)하므로, `runDirectLoad` 실행 중에는 네비바 실행중 표시가 비활성화될 수 있다.

**확인**: 현재 버튼 클릭 경로는 `KRAS_LOAD`만 사용하므로 실제 동작은 문제없으나, 일관성을 위해 status API에 `KRAS_LOAD` 외 타입도 포함하거나, 네비바 표시 타입을 통일할 것.

---

### [LOW] `kras_work_dir` 입력 가이드 없음

설정 화면에 작업 디렉토리 입력 필드가 있으나 절대경로/상대경로 예시가 없다.  
`krasWorkDir()` 구현상 `orgCode`가 붙어 있으면 중복을 제거하는 로직이 있어 헷갈릴 수 있다.

**권고**: placeholder 또는 설명 텍스트로 `예: ./workspace/kras` (orgCode 자동 추가됨) 안내.

---

## 4. 기능 부재 / 추가 권고

### [MID] 적재 이력 인메모리 저장 (재시작 시 소실)

`SyncStatusService`는 `ConcurrentHashMap`으로 이력을 관리하여, 앱 재시작 시 이전 성공/실패 이력이 사라진다. 대시보드의 "KRAS 마지막 실행" 카드가 빈 상태가 된다.

**권고**: 최소한 마지막 성공/실패 시각을 파일(JSON)에 영속화.

---

### [MID] 스케줄 적재 실패 알림 수단 없음

스케줄 실행 실패 시 로그 외에 별도 알림이 없다. 야간 배치가 조용히 실패해도 다음 날 대시보드를 확인하기 전에는 알 수 없다.

**권고**: 실패 시 로컬 알림 파일(`sync_error_YYYYMMDD.log`) 기록 또는 Webhook 발송 옵션 추가.

---

### [LOW] SHP 파일 인코딩 하드코딩

**파일**: `KrasWorkspaceScanner.java:266`

```java
store.setCharset(Charset.forName("MS949"));
```

MS949(EUC-KR)가 아닌 UTF-8로 제공되는 SHP 파일은 깨진다. DBF 파일의 CPG 메타데이터가 있으면 자동 감지할 수 있고, 없으면 설정으로 뺄 수 있다.

---

### [LOW] 워크스페이스 디렉토리 미존재 시 자동 생성 없음

SHP 파일 디렉토리가 없으면 `Scanner` 경고 로그를 남기고 빈 목록을 반환한다. 최초 설치 시 사용자가 디렉토리를 수동으로 만들어야 한다는 안내가 없다.

**권고**: 설정 저장 시 또는 앱 시작 시 `krasWorkDir/orgCode` 디렉토리 자동 생성.

---

## 5. 버튼 클릭 / 스케줄링 경로 최종 검증

| 항목 | 버튼 클릭 경로 | 스케줄 경로 | 상태 |
|------|---------------|-------------|------|
| 이중 실행 방지 | `isRunning()` 체크 + `compareAndSet` | `compareAndSet` | ✅ |
| API 연결 확인 | `checkConnection()` | `checkConnection()` | ✅ |
| lt_c_uzone API 수신 | `directLoadTable` → `fetchAvailableUsezoneLayers` + `fetchFeatures` | 동일 | ✅ |
| ulyr 코드 주입 | `injectUsezoneCode()` | 동일 | ✅ |
| ucode/uname 파생 | `deriveUsezoneFields()` | 동일 | ✅ |
| lp_pa_cbnd SHP 읽기 | `workspaceScanner.loadTable()` | 동일 | ✅ |
| 좌표 변환 (5174→5186) | `sourceEpsg` 분기 + `ST_Transform` | 동일 | ✅ |
| 대상 DB 스키마 적용 | `ts.schema()` 전달 | 전달 (null이면 기본) | ✅ |
| 진행률 표시 | `startProgress` + `updateProgress` | 동일 | ✅ |
| 완료 후 이력 기록 | `recordEnd()` | 동일 | ✅ |
| 설정 변경 즉시 반영 | `RuntimeSettingsService` 동적 읽기 | 동일 | ✅ |
| syncEnabled 동적 반영 | `RuntimeSettingsService` 동적 읽기 | 동일 | ✅ |
| srsName 정합성 | `srsName=EPSG:5174` / `sourceEpsg=5174` | 동일 | ✅ |

---

## 6. 우선순위 요약

| 우선순위 | 항목 | 영향 | 처리 상태 |
|----------|------|------|-----------|
| **즉시** | srsName / sourceEpsg 불일치 검증 | 기하 데이터 오염 가능 | 확인 완료: 요청/저장 모두 EPSG:5174 기준 |
| **즉시** | `isSynced()` 로직 의미 검토 | 대시보드 상태 오표시 | 완료: 마지막 성공 시각이 파일 수정 이후일 때 동기화됨 |
| **단기** | `SyncScheduler.syncEnabled` 동적 읽기 | 설정 변경 미반영 | 완료: `RuntimeSettingsService.syncEnabled()` 동적 조회 |
| **단기** | `catalogPath` 기관코드 하드코딩 제거 | 타 기관 적용 불가 | 완료: `orgCode` 기반 기본 카탈로그 경로 사용 |
| **단기** | `countRows()` PreparedStatement 적용 | 코드 안전성 | 완료: `org_cd` 조건 파라미터 바인딩 |
| **중기** | HTTP 클라이언트 재사용 | 성능 | 완료: `KrasWorker` 공유 `CloseableHttpClient` 사용 |
| **중기** | 적재 이력 영속화 | 운영 편의 | 완료: `logs/sync-history.json` 저장/복원 |
| **중기** | 레거시 실행 경로 정리 | 유지보수성 | 완료: 기존 API 유지 + deprecated 표시, 핵심 경로는 직접 적재 |
| **장기** | `KrasWorker` 클래스 분리 | 유지보수성 | 완료: API 통신/테스트를 `KrasApiClient`로 분리 |
| **장기** | 스케줄 실패 알림 | 운영 안정성 | 완료: 실패 시 `logs/sync_error_YYYYMMDD.log` 기록 |

### 추가 처리 항목

| 항목 | 처리 상태 |
|------|-----------|
| `OdsRepository.replaceAllTo()` 오버로드 복잡도 | 완료: 내부 구현을 `InsertRequest` 파라미터 객체로 집약 |
| 대상 컬럼 누락 시 silent skip | 완료: 누락 컬럼 WARN 로그 추가 |
| `RuntimeSettingsService.snapshot()` 비원자 갱신 | 완료: `snapshot()` 동기화 |
| 미사용 private 메서드 | 완료: `legacyStatus()`, `usezoneLayerStatuses()`, `extractYamlValue()` 제거 |
| 스케줄 비활성화 안내 | 완료: 설정 화면 안내 문구 보강 |
| `/sync/status` 폴링 타입 | 완료: `KRAS`, `KRAS_COLLECT`, `KRAS_LOAD` 모두 반환 |
| `kras_work_dir` 입력 가이드 | 완료: 예시와 자동 생성 안내 추가 |
| SHP 파일 인코딩 하드코딩 | 완료: `kras.shp-charset` 설정 및 설정 화면 입력 추가 |
| 워크스페이스 디렉토리 자동 생성 | 완료: 기본 작업 디렉토리 자동 생성 |
