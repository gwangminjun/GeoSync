package geomex.sync.worker;

import com.fasterxml.jackson.databind.JsonNode;
import geomex.sync.geo.CoordTransformer;
import geomex.sync.mapper.TableMapper;
import geomex.sync.model.ColumnDef;
import geomex.sync.model.SyncTableDef;
import geomex.sync.repository.OdsRepository;
import geomex.sync.service.RuntimeSettingsService;
import geomex.sync.service.SyncExecutionLogService;
import geomex.sync.service.SyncStatusService;
import geomex.sync.service.TargetDbService;
import geomex.sync.service.UsezoneCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class KrasWorker {

    private static final Logger log = LoggerFactory.getLogger(KrasWorker.class);
    private static final int KRAS_EPSG = 5174;

    private final TableMapper tableMapper;
    private final OdsRepository odsRepository;
    private final CoordTransformer coordTransformer;
    private final SyncStatusService statusService;
    private final TargetDbService targetDbService;
    private final KrasFileWriter fileWriter;
    private final KrasFileReader fileReader;
    private final KrasApiClient apiClient;
    private final RuntimeSettingsService settings;
    private final KrasWorkspaceScanner workspaceScanner;
    private final UsezoneCodeService usezoneCodeService;
    private final SyncExecutionLogService executionLogService;

    public KrasWorker(TableMapper tableMapper, OdsRepository odsRepository,
                      CoordTransformer coordTransformer, SyncStatusService statusService,
                      TargetDbService targetDbService, KrasFileWriter fileWriter,
                      KrasFileReader fileReader, KrasApiClient apiClient,
                      RuntimeSettingsService settings, KrasWorkspaceScanner workspaceScanner,
                      UsezoneCodeService usezoneCodeService,
                      SyncExecutionLogService executionLogService) {
        this.tableMapper = tableMapper;
        this.odsRepository = odsRepository;
        this.coordTransformer = coordTransformer;
        this.statusService = statusService;
        this.targetDbService = targetDbService;
        this.fileWriter = fileWriter;
        this.fileReader = fileReader;
        this.apiClient = apiClient;
        this.settings = settings;
        this.workspaceScanner = workspaceScanner;
        this.usezoneCodeService = usezoneCodeService;
        this.executionLogService = executionLogService;
    }

    @Deprecated
    public void run() {
        log.info("[KRAS] 동기화 시작 (url={})", settings.krasUrl());
        statusService.recordStart("KRAS");
        int totalSuccess = 0, totalError = 0;
        boolean failed = false;
        try {
            if (!checkConnection()) {
                log.error("[KRAS] 연결 확인 실패 — 동기화 중단");
                failed = true;
                return;
            }
            List<SyncTableDef> tableDefs = tableMapper.load(settings.krasConfig());
            List<TargetDbService.ActiveTarget> targets = targetDbService.getConfiguredTargets();
            if (targets.isEmpty()) {
                log.warn("[KRAS] active target DB not found; sync stopped");
                failed = true;
                return;
            }
            statusService.startProgress("KRAS", tableDefs.size(), "");
            Map<String, List<String>> manifest = new LinkedHashMap<>();
            int completed = 0;
            for (SyncTableDef def : tableDefs) {
                statusService.updateProgress("KRAS", completed, tableDefs.size(), def.tgtTableName);
                int saved = processTable(def, manifest, targets);
                if (saved >= 0) totalSuccess += saved;
                else totalError++;
                completed++;
                statusService.updateProgress("KRAS", completed, tableDefs.size(), def.tgtTableName);
            }
            fileWriter.writeManifest(manifest);
            log.info("[KRAS] 동기화 완료 (success={}, error={})", totalSuccess, totalError);
        } catch (Exception e) {
            log.error("[KRAS] 동기화 중 오류: {}", e.getMessage(), e);
            failed = true;
        } finally {
            statusService.recordEnd("KRAS", totalSuccess, totalError, failed);
        }
    }

    @Deprecated
    public void runCollect() {
        log.info("[KRAS] 수집 시작 (API → 파일, url={})", settings.krasUrl());
        statusService.recordStart("KRAS_COLLECT");
        int totalSuccess = 0, totalError = 0;
        boolean failed = false;
        Map<String, List<String>> manifest = new LinkedHashMap<>();
        try {
            if (!checkConnection()) {
                log.error("[KRAS] 연결 확인 실패 — 수집 중단");
                failed = true;
                return;
            }
            List<SyncTableDef> tableDefs = tableMapper.load(settings.krasConfig());
            statusService.startProgress("KRAS_COLLECT", tableDefs.size(), "");
            int completed = 0;
            for (SyncTableDef def : tableDefs) {
                statusService.updateProgress("KRAS_COLLECT", completed, tableDefs.size(), def.srcTableName);
                try {
                    List<String> fileBaseNames = collectTable(def);
                    manifest.put(def.srcTableName, fileBaseNames);
                    totalSuccess += fileBaseNames.size();
                } catch (Exception e) {
                    log.error("[KRAS] {} 수집 실패: {}", def.srcTableName, e.getMessage());
                    totalError++;
                }
                completed++;
                statusService.updateProgress("KRAS_COLLECT", completed, tableDefs.size(), def.srcTableName);
            }
            fileWriter.writeManifest(manifest);
            log.info("[KRAS] 수집 완료 (success={}, error={})", totalSuccess, totalError);
        } catch (Exception e) {
            log.error("[KRAS] 수집 중 오류: {}", e.getMessage(), e);
            failed = true;
        } finally {
            statusService.recordEnd("KRAS_COLLECT", totalSuccess, totalError, failed);
        }
    }

    /**
     * 스케줄 자동 실행: 설정된 워크스페이스 경로에서 파일 스캔 후
     * SCHEDULED_TABLES(lt_c_uzone, lp_pa_cbnd)만 적재한다.
     */
    public void runScheduledLoad() {
        log.info("[KRAS] 스케줄 적재 시작 (lt_c_uzone=API, lp_pa_cbnd=SHP)");
        runDirectLoad(null, null, "SCHEDULE");
    }

    @Deprecated
    public void runLoad() {
        runLoad(null, (Map<Integer, String>) null, null);
    }

    @Deprecated
    public void runLoad(List<Integer> targetIndices, String schemaOverride) {
        runLoad(targetIndices, schemaOverride, null);
    }

    @Deprecated
    public void runLoad(List<Integer> targetIndices, String schemaOverride, java.util.Set<String> fileFilter) {
        runLoad(targetIndices, (Map<Integer, String>) null, fileFilter);
    }

    @Deprecated
    public void runLoad(List<Integer> targetIndices, Map<Integer, String> schemaMap, java.util.Set<String> fileFilter) {
        runLoad(targetIndices, schemaMap, fileFilter, null);
    }

    @Deprecated
    public void runLoad(List<Integer> targetIndices, Map<Integer, String> schemaMap,
                        java.util.Set<String> fileFilter, Set<String> tgtTableFilter) {
        log.info("[KRAS] 적재 시작 (파일 → DB, targets={}, files={})",
                targetIndices != null ? targetIndices : "전체",
                fileFilter != null ? fileFilter : "전체");
        statusService.recordStart("KRAS_LOAD");
        int totalSuccess = 0, totalError = 0;
        boolean failed = false;
        try {
            List<TargetDbService.ActiveTarget> allTargets = targetDbService.getConfiguredTargets();
            List<TargetWithSchema> selected = buildTargetsWithSchema(allTargets, targetIndices, schemaMap);
            if (selected.isEmpty()) {
                log.warn("[KRAS] 선택된 대상 DB 없음 — 적재 중단");
                failed = true;
                return;
            }

            Map<String, List<String>> manifest = fileReader.readManifest();
            List<SyncTableDef> tableDefs = tableMapper.load(settings.krasConfig());
            boolean tablesCreated = false;

            // 전체 파일 수를 진행률 기준으로 사용
            long totalFiles = manifest.values().stream().mapToLong(List::size).sum();
            statusService.startProgress("KRAS_LOAD", (int) totalFiles, "");
            int completedFiles = 0;
            for (SyncTableDef def : tableDefs) {
                if (tgtTableFilter != null && !tgtTableFilter.contains(tableBaseName(def.tgtTableName))) {
                    continue;
                }
                List<String> fileBaseNames = manifest.getOrDefault(def.srcTableName, List.of());
                if (fileFilter != null && !fileFilter.isEmpty()) {
                    fileBaseNames = fileBaseNames.stream().filter(fileFilter::contains).toList();
                }
                if (fileBaseNames.isEmpty()) {
                    continue;
                }
                statusService.updateProgress("KRAS_LOAD", completedFiles, (int) totalFiles, def.tgtTableName);
                try {
                    int saved = loadTable(def, fileBaseNames, selected, tablesCreated);
                    if (saved >= 0) totalSuccess += saved;
                    else totalError++;
                } catch (Exception e) {
                    log.error("[KRAS] {} 적재 실패: {}", def.srcTableName, e.getMessage());
                    totalError++;
                }
                completedFiles += fileBaseNames.size();
                statusService.updateProgress("KRAS_LOAD", completedFiles, (int) totalFiles, def.tgtTableName);
            }
            log.info("[KRAS] 적재 완료 (success={}, error={})", totalSuccess, totalError);
        } catch (IOException e) {
            log.error("[KRAS] 매니페스트 읽기 실패: {}", e.getMessage());
            failed = true;
        } catch (Exception e) {
            log.error("[KRAS] 적재 중 오류: {}", e.getMessage(), e);
            failed = true;
        } finally {
            statusService.recordEnd("KRAS_LOAD", totalSuccess, totalError, failed);
        }
    }

    private record TargetWithSchema(TargetDbService.ActiveTarget target, String schema) {}

    private List<TargetWithSchema> buildTargetsWithSchema(
            List<TargetDbService.ActiveTarget> all, List<Integer> indices, Map<Integer, String> schemaMap) {
        List<TargetWithSchema> result = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (indices == null || indices.contains(i)) {
                String schema = schemaMap != null ? schemaMap.get(i) : null;
                result.add(new TargetWithSchema(all.get(i), schema));
            }
        }
        return result;
    }


    private List<String> collectTable(SyncTableDef def) {
        List<String> written = new ArrayList<>();
        if (def.srcTableName.startsWith("USEZONE:")) {
            List<String> layerNames = fetchAvailableUsezoneLayers();
            for (String layerName : layerNames) {
                List<Map<String, Object>> rows = fetchFeatures(layerName, def);
                injectUsezoneCode(rows, layerName);
                fileWriter.write(layerName, def, rows);
                fileWriter.writeJson(layerName, rows);
                written.add(toFileBaseName(layerName));
            }
        } else {
            List<Map<String, Object>> rows = fetchFeatures(def.srcTableName, def);
            fileWriter.write(def.srcTableName, def, rows);
            fileWriter.writeJson(def.srcTableName, rows);
            written.add(toFileBaseName(def.srcTableName));
        }
        return written;
    }

    private int loadTable(SyncTableDef def, List<String> fileBaseNames,
                          List<TargetWithSchema> targets, boolean tableAlreadyCreated) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String baseName : fileBaseNames) {
            rows.addAll(fileReader.readJson(baseName));
        }
        if (targets.isEmpty()) {
            log.warn("[KRAS] 활성 대상 DB 없음 — {} 저장 건너뜀", def.tgtTableName);
            return 0;
        }
        int saved = 0;
        for (TargetWithSchema ts : targets) {
            log.info("[KRAS] loading {} into target {} (schema={}, rows={})",
                    def.tgtTableName, ts.target().label(),
                    ts.schema() != null ? ts.schema() : "default", rows.size());
            saved = odsRepository.replaceAllTo(ts.target().jdbc(), def, settings.orgCode(),
                    sourceEpsg(def), coordTransformer.getStorageEpsg(), rows, ts.schema(), "KRAS_LOAD",
                    tableAlreadyCreated);
        }
        return saved;
    }

    /** 워크스페이스 SHP 파일 미리보기 */
    public Map<String, Object> previewShpTable(String tgtTableBaseName, int limit) {
        List<SyncTableDef> defs = tableMapper.load(settings.krasConfig());
        SyncTableDef def = defs.stream()
                .filter(d -> tableBaseName(d.tgtTableName).equals(tgtTableBaseName))
                .findFirst().orElse(null);
        if (def == null) return Map.of("error", "테이블 정의 없음: " + tgtTableBaseName);

        List<Map<String, Object>> all = workspaceScanner.loadTable(def);
        if (all.isEmpty()) return Map.of("error", "SHP 파일 없음 또는 데이터 없음");

        List<Map<String, Object>> preview = all.subList(0, Math.min(limit, all.size()));
        List<String> columns = new ArrayList<>(preview.get(0).keySet());
        return Map.of("total", all.size(), "rows", preview, "columns", columns);
    }

    /** Mock 적재: lt_c_uzone=간이 3건, lp_pa_cbnd=워크스페이스 SHP */
    public Map<String, Object> runMockLoad(List<Integer> targetIndices, String schema) {
        String safeSchema = (schema == null || schema.isBlank()) ? "test" : schema.trim();
        List<TargetDbService.ActiveTarget> allTargets = targetDbService.getConfiguredTargets();
        List<TargetWithSchema> selected = buildTargetsWithSchema(allTargets, targetIndices, null);
        if (selected.isEmpty()) return Map.of("error", "선택된 대상 DB가 없습니다.");

        List<SyncTableDef> defs = tableMapper.load(settings.krasConfig());
        List<Map<String, Object>> results = new ArrayList<>();
        int storageEpsg = coordTransformer.getStorageEpsg();

        // lt_c_uzone: mock 3건 (EPSG:5174 → 5186 변환 적재)
        SyncTableDef uzoneDef = defs.stream()
                .filter(d -> d.srcTableName.startsWith("USEZONE:")).findFirst().orElse(null);
        if (uzoneDef != null) {
            List<Map<String, Object>> mockRows = buildMockUzoneRows();
            deriveUsezoneFields(mockRows);
            for (TargetWithSchema ts : selected) {
                try {
                    int saved = odsRepository.replaceAllTo(ts.target().jdbc(), uzoneDef,
                            settings.orgCode(), KRAS_EPSG, storageEpsg,
                            mockRows, safeSchema, null, false);
                    results.add(Map.of("table", "lt_c_uzone", "source", "mock", "rows", mockRows.size(),
                            "saved", saved, "target", ts.target().label()));
                } catch (Exception e) {
                    results.add(Map.of("table", "lt_c_uzone", "source", "mock",
                            "error", e.getMessage(), "target", ts.target().label()));
                }
            }
        }

        // lp_pa_cbnd: 워크스페이스 SHP 파일 (EPSG:5174→5186)
        SyncTableDef cbndDef = defs.stream()
                .filter(d -> tableBaseName(d.tgtTableName).equals("lp_pa_cbnd")).findFirst().orElse(null);
        if (cbndDef != null) {
            List<Map<String, Object>> shpRows = workspaceScanner.loadTable(cbndDef);
            for (TargetWithSchema ts : selected) {
                try {
                    int saved = odsRepository.replaceAllTo(ts.target().jdbc(), cbndDef,
                            settings.orgCode(), KRAS_EPSG, storageEpsg,
                            shpRows, safeSchema, null, false);
                    results.add(Map.of("table", "lp_pa_cbnd", "source", "SHP", "rows", shpRows.size(),
                            "saved", saved, "target", ts.target().label()));
                } catch (Exception e) {
                    results.add(Map.of("table", "lp_pa_cbnd", "source", "SHP",
                            "error", e.getMessage(), "target", ts.target().label()));
                }
            }
        }

        return Map.of("schema", safeSchema, "results", results);
    }

    private List<Map<String, Object>> buildMockUzoneRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Object[][] data = {
            {"4687025625-UQ112-0001", "UQ112", "UQ0112", "제2종일반주거지역", "2종일주", "",
             "MULTIPOLYGON(((155000.00 315000.00,155500.00 315000.00,155500.00 315500.00,155000.00 315500.00,155000.00 315000.00)))"},
            {"4687025625-UQ112-0002", "UQ112", "UQ0112", "제2종일반주거지역", "2종일주", "",
             "MULTIPOLYGON(((155500.00 315000.00,156200.00 315000.00,156200.00 315700.00,155500.00 315700.00,155500.00 315000.00)))"},
            {"4687025625-UQ112-0003", "UQ112", "UQ0112", "제2종일반주거지역", "2종일주", "일부 지형 복잡",
             "MULTIPOLYGON(((154800.00 314800.00,155100.00 314800.00,155200.00 314900.00,155100.00 315100.00,154800.00 315100.00,154800.00 314800.00)))"}
        };
        String[] keys = {"mnum","ulyr","ucode","uname","alias","remark","geometry"};
        for (Object[] d : data) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < keys.length; i++) row.put(keys[i], d[i]);
            rows.add(row);
        }
        return rows;
    }

    // USEZONE 레이어명(예: "LSMD_CONT_UB201") → ulyr 코드(예: "UB201") 추출 후 row에 주입
    // API가 ulyr를 이미 반환한 경우 덮어쓰지 않음
    private void injectUsezoneCode(List<Map<String, Object>> rows, String layerName) {
        int idx = layerName.lastIndexOf('_');
        String code = idx >= 0 ? layerName.substring(idx + 1).toUpperCase() : layerName.toUpperCase();
        for (Map<String, Object> row : rows) {
            row.putIfAbsent("ulyr", code);
        }
    }

    /**
     * 구 시스템과 동일한 방식으로 theme_code / theme_name 파생.
     * - ucode(theme_code): substr(mnum, 21, 6) — mnum이 26자 이상이면 파생, 아니면 API 값 유지
     * - uname(theme_name): mt_usezone_cd 코드 테이블 조회 → 없으면 API 값 유지
     */
    private void deriveUsezoneFields(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            String mnum = (String) row.get("mnum");
            String derivedCode = (mnum != null && mnum.length() >= 26)
                    ? mnum.substring(20, 26)          // 1-indexed 21~26 → 0-indexed 20~26
                    : (String) row.get("ucode");      // 짧은 mnum이면 API 값 그대로
            if (derivedCode != null) {
                row.put("ucode", derivedCode);
                String name = usezoneCodeService.getName(derivedCode);
                if (name != null) row.put("uname", name);
            }
        }
    }

    private String toFileBaseName(String layerName) {
        String name = layerName.contains(":") ? layerName.substring(layerName.indexOf(':') + 1) : layerName;
        return name.toLowerCase();
    }

    private static String tableBaseName(String tgtTableName) {
        int dot = tgtTableName.lastIndexOf('.');
        return dot >= 0 ? tgtTableName.substring(dot + 1) : tgtTableName;
    }

    // API(GetFeature, srsName:5174)와 SHP 파일 모두 KRAS_EPSG(5174)로 제공된다.
    // replaceAllTo()에서 sourceEpsg != storageEpsg이면 ST_Transform(wkt, 5174→5186) 적용.
    private int sourceEpsg(SyncTableDef def) {
        return KRAS_EPSG;
    }

    // ──────────────────────────────────────────────────────────────────
    // 직접 적재: lt_c_uzone=API→DB, lp_pa_cbnd=SHP→DB (JSON 파일 없음)
    // ──────────────────────────────────────────────────────────────────

    public void runDirectLoad(List<Integer> targetIndices, Map<Integer, String> schemaMap) {
        runDirectLoad(targetIndices, schemaMap, "MANUAL");
    }

    public void runDirectLoad(List<Integer> targetIndices, Map<Integer, String> schemaMap, String triggeredBy) {
        log.info("[KRAS] 직접 적재 시작 (lt_c_uzone=API, lp_pa_cbnd=SHP, triggeredBy={})", triggeredBy);
        Long logId = executionLogService.start("KRAS_LOAD", triggeredBy, settings.krasSchedule());
        usezoneCodeService.refresh();
        statusService.recordStart("KRAS_LOAD");
        int totalSuccess = 0, totalError = 0;
        boolean failed = false;
        String errorMsg = null;
        try {
            List<TargetDbService.ActiveTarget> allTargets = targetDbService.getConfiguredTargets();
            List<TargetWithSchema> selected = buildTargetsWithSchema(allTargets, targetIndices, schemaMap);
            if (selected.isEmpty()) {
                log.warn("[KRAS] 선택된 대상 DB 없음 — 직접 적재 중단");
                failed = true;
                errorMsg = "활성 대상 DB 없음";
                return;
            }

            List<SyncTableDef> tableDefs = tableMapper.load(settings.krasConfig());
            boolean needsApi = tableDefs.stream().anyMatch(d -> d.srcTableName.startsWith("USEZONE:"));
            boolean apiOk = !needsApi || checkConnection();

            statusService.startProgress("KRAS_LOAD", tableDefs.size(), "");
            int completed = 0;
            for (SyncTableDef def : tableDefs) {
                statusService.updateProgress("KRAS_LOAD", completed, tableDefs.size(), def.tgtTableName);
                try {
                    int saved = directLoadTable(def, selected, apiOk);
                    if (saved >= 0) totalSuccess += saved;
                    else totalError++;
                } catch (Exception e) {
                    log.error("[KRAS] {} 직접 적재 실패: {}", def.tgtTableName, e.getMessage(), e);
                    totalError++;
                    if (errorMsg == null) errorMsg = def.tgtTableName + ": " + e.getMessage();
                }
                completed++;
                statusService.updateProgress("KRAS_LOAD", completed, tableDefs.size(), def.tgtTableName);
            }
            log.info("[KRAS] 직접 적재 완료 (success={}, error={})", totalSuccess, totalError);
        } catch (Exception e) {
            log.error("[KRAS] 직접 적재 중 오류: {}", e.getMessage(), e);
            failed = true;
            errorMsg = e.getMessage();
        } finally {
            statusService.recordEnd("KRAS_LOAD", totalSuccess, totalError, failed);
            executionLogService.finish(logId, totalSuccess, totalError, failed, errorMsg);
        }
    }

    private int directLoadTable(SyncTableDef def, List<TargetWithSchema> targets, boolean apiOk) {
        List<Map<String, Object>> rows;
        if (def.srcTableName.startsWith("USEZONE:")) {
            if (!apiOk) {
                log.warn("[KRAS] API 연결 불가 — {} 건너뜀", def.tgtTableName);
                return -1;
            }
            List<String> layerNames = fetchAvailableUsezoneLayers();
            rows = new ArrayList<>();
            for (String layerName : layerNames) {
                List<Map<String, Object>> layerRows = fetchFeatures(layerName, def);
                injectUsezoneCode(layerRows, layerName);
                deriveUsezoneFields(layerRows);
                rows.addAll(layerRows);
                log.info("[KRAS] {} 수신: {}건 (누계 {}건)", layerName, layerRows.size(), rows.size());
            }
        } else {
            rows = workspaceScanner.loadTable(def);
        }

        if (rows.isEmpty()) {
            log.warn("[KRAS] {} 데이터 없음 — 적재 건너뜀", def.tgtTableName);
            return 0;
        }

        int saved = 0;
        for (TargetWithSchema ts : targets) {
            log.info("[KRAS] direct loading {} → {} (schema={}, rows={})",
                    def.tgtTableName, ts.target().label(),
                    ts.schema() != null ? ts.schema() : "default", rows.size());
            saved = odsRepository.replaceAllTo(ts.target().jdbc(), def, settings.orgCode(),
                    sourceEpsg(def), coordTransformer.getStorageEpsg(), rows, ts.schema(), "KRAS_LOAD", false);
        }
        return saved;
    }

    // ──────────────────────────────────────────────────────────────────
    // API 테스트용 public 메서드
    // ──────────────────────────────────────────────────────────────────

    public Map<String, Object> testConnection() {
        return apiClient.testConnection();
    }

    public List<Map<String, Object>> testLayerList() throws Exception {
        return apiClient.testLayerList();
    }

    public Map<String, Object> testFeatures(String layerName, int limit) throws Exception {
        return apiClient.testFeatures(layerName, limit);
    }

    private boolean checkConnection() {
        try {
            JsonNode res = apiClient.check();
            String code = res.path("resultCode").asText("");
            log.info("[KRAS] 연결 확인: resultCode={}, msg={}", code,
                    res.path("resultMsg").asText(""));
            return "00".equals(code);
        } catch (Exception e) {
            log.error("[KRAS] 연결 확인 중 오류: {}", e.getMessage());
            return false;
        }
    }

    private int processTable(SyncTableDef def, Map<String, List<String>> manifest,
                             List<TargetDbService.ActiveTarget> targets) {
        List<String> written = new ArrayList<>();
        List<Map<String, Object>> rows;
        if (def.srcTableName.startsWith("USEZONE:")) {
            List<String> layerNames = fetchAvailableUsezoneLayers();
            rows = new ArrayList<>();
            for (String layerName : layerNames) {
                List<Map<String, Object>> layerRows = fetchFeatures(layerName, def);
                injectUsezoneCode(layerRows, layerName);
                deriveUsezoneFields(layerRows);
                fileWriter.write(layerName, def, layerRows);
                fileWriter.writeJson(layerName, layerRows);
                written.add(toFileBaseName(layerName));
                rows.addAll(layerRows);
            }
        } else {
            rows = fetchFeatures(def.srcTableName, def);
            fileWriter.write(def.srcTableName, def, rows);
            fileWriter.writeJson(def.srcTableName, rows);
            written.add(toFileBaseName(def.srcTableName));
        }
        manifest.put(def.srcTableName, written);
        return saveToTargets(def, rows, targets);
    }

    private int saveToTargets(SyncTableDef def, List<Map<String, Object>> rows,
                              List<TargetDbService.ActiveTarget> targets) {
        if (targets.isEmpty()) {
            log.warn("[KRAS] 활성 대상 DB 없음 — {} 저장 건너뜀", def.tgtTableName);
            return 0;
        }
        int saved = 0;
        for (TargetDbService.ActiveTarget target : targets) {
            log.info("[KRAS] loading {} into target {}", def.tgtTableName, target.label());
            saved = odsRepository.replaceAllTo(target.jdbc(), def, settings.orgCode(),
                    sourceEpsg(def), coordTransformer.getStorageEpsg(), rows, settings.odsSchema(), "KRAS");
        }
        return saved;
    }

    private List<String> fetchAvailableUsezoneLayers() {
        try {
            JsonNode res = apiClient.layerList();
            List<String> names = new ArrayList<>();
            for (JsonNode layer : res.path("layers")) {
                String name = layer.path("layerName").asText("");
                // estateGateway는 전체 레이어 목록을 반환하므로 USEZONE 레이어만 필터링
                // USEZONE 레이어 패턴: LSMD_CONT_U{코드} (예: LSMD_CONT_UB201, LSMD_CONT_UQ112)
                if (!name.isBlank() && isUsezoneLayer(name)) names.add(name);
            }
            log.info("[KRAS] USEZONE 레이어 목록: {}개 ({})", names.size(), names);
            return names;
        } catch (Exception e) {
            log.error("[KRAS] 레이어 목록 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isUsezoneLayer(String layerName) {
        // "LSMD_CONT_U" 이후에 알파벳 코드가 오는 용도지역지구 레이어만 포함
        int idx = layerName.indexOf("LSMD_CONT_U");
        if (idx < 0) return false;
        int codeStart = idx + "LSMD_CONT_U".length();
        return codeStart < layerName.length() && Character.isLetter(layerName.charAt(codeStart));
    }

    private List<Map<String, Object>> fetchFeatures(String layerName, SyncTableDef def) {
        try {
            JsonNode res = apiClient.features(layerName);
            if (!"00".equals(res.path("resultCode").asText(""))) {
                log.warn("[KRAS] GetFeature 실패: layerName={}, code={}", layerName,
                        res.path("resultCode").asText());
                return List.of();
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode feature : res.path("features")) {
                rows.add(parseFeature(feature, def));
            }
            log.info("[KRAS] {} → {}건 수신", layerName, rows.size());
            return rows;

        } catch (Exception e) {
            log.error("[KRAS] {} 수집 실패: {}", layerName, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> parseFeature(JsonNode feature, SyncTableDef def) {
        Map<String, Object> row = new HashMap<>();
        for (ColumnDef col : def.columns) {
            if (col.isGeometry) {
                String wkt = feature.path("wkt").asText(null);
                if (wkt != null) {
                    row.put(col.srcName, wkt);
                }
            } else {
                JsonNode val = feature.path(col.srcName);
                row.put(col.srcName, val.isMissingNode() ? null : val.asText(null));
            }
        }
        return row;
    }

}
