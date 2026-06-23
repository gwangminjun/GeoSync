package geomex.sync.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import geomex.sync.geo.CoordTransformer;
import geomex.sync.mapper.TableMapper;
import geomex.sync.model.ColumnDef;
import geomex.sync.model.SyncTableDef;
import geomex.sync.repository.OdsRepository;
import geomex.sync.service.KrasGpkiService;
import geomex.sync.service.RuntimeSettingsService;
import geomex.sync.service.SyncStatusService;
import geomex.sync.service.TargetDbService;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final KrasGpkiService gpkiService;
    private final RuntimeSettingsService settings;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KrasWorker(TableMapper tableMapper, OdsRepository odsRepository,
                      CoordTransformer coordTransformer, SyncStatusService statusService,
                      TargetDbService targetDbService, KrasFileWriter fileWriter,
                      KrasFileReader fileReader, KrasGpkiService gpkiService,
                      RuntimeSettingsService settings) {
        this.tableMapper = tableMapper;
        this.odsRepository = odsRepository;
        this.coordTransformer = coordTransformer;
        this.statusService = statusService;
        this.targetDbService = targetDbService;
        this.fileWriter = fileWriter;
        this.fileReader = fileReader;
        this.gpkiService = gpkiService;
        this.settings = settings;
    }

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
            recreateSchemaTables(buildTargetsWithSchema(targets, null, null));
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

    public void runLoad() {
        runLoad(null, (Map<Integer, String>) null, null);
    }

    public void runLoad(List<Integer> targetIndices, String schemaOverride) {
        runLoad(targetIndices, schemaOverride, null);
    }

    public void runLoad(List<Integer> targetIndices, String schemaOverride, java.util.Set<String> fileFilter) {
        // 단일 스키마를 모든 타겟에 일괄 적용 (스케줄 실행용)
        runLoad(targetIndices, (Map<Integer, String>) null, fileFilter);
    }

    public void runLoad(List<Integer> targetIndices, Map<Integer, String> schemaMap, java.util.Set<String> fileFilter) {
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
            if (fileFilter == null || fileFilter.isEmpty()) {
                // 전체 적재: 모든 테이블 DROP + 재생성
                recreateSchemaTables(selected);
            } else {
                // 선택 적재: 적재할 테이블만 DROP → replaceAllTo의 ensureTableExists가 새 SRID로 재생성
                for (SyncTableDef def : tableDefs) {
                    boolean willLoad = manifest.getOrDefault(def.srcTableName, List.of())
                            .stream().anyMatch(fileFilter::contains);
                    if (willLoad) {
                        for (TargetWithSchema ts : selected) {
                            odsRepository.dropTable(ts.target().jdbc(), def.tgtTableName, ts.schema());
                        }
                    }
                }
            }

            // 전체 파일 수를 진행률 기준으로 사용
            long totalFiles = manifest.values().stream().mapToLong(List::size).sum();
            statusService.startProgress("KRAS_LOAD", (int) totalFiles, "");
            int completedFiles = 0;
            for (SyncTableDef def : tableDefs) {
                List<String> fileBaseNames = manifest.getOrDefault(def.srcTableName, List.of());
                if (fileFilter != null && !fileFilter.isEmpty()) {
                    fileBaseNames = fileBaseNames.stream().filter(fileFilter::contains).toList();
                }
                if (fileBaseNames.isEmpty()) {
                    continue;
                }
                statusService.updateProgress("KRAS_LOAD", completedFiles, (int) totalFiles, def.tgtTableName);
                try {
                    int saved = loadTable(def, fileBaseNames, selected);
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
                          List<TargetWithSchema> targets) throws IOException {
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
            log.info("[KRAS] loading {} into target {} (schema={})",
                    def.tgtTableName, ts.target().label(), ts.schema() != null ? ts.schema() : "default");
            saved = odsRepository.replaceAllTo(ts.target().jdbc(), def, settings.orgCode(),
                    coordTransformer.getTargetEpsg(), rows, ts.schema(), "KRAS_LOAD");
        }
        return saved;
    }

    private String toFileBaseName(String layerName) {
        String name = layerName.contains(":") ? layerName.substring(layerName.indexOf(':') + 1) : layerName;
        return name.toLowerCase();
    }

    private boolean checkConnection() {
        try {
            gpkiService.assertReady();
            if (gpkiService.isEnabled()) {
                log.info("[KRAS] GPKI authentication enabled (gpki_id={})", gpkiService.gpkiId());
            }
            ObjectNode req = objectMapper.createObjectNode();
            req.put("service", "CHECK");
            req.put("connSysId", settings.krasConnSysId());
            req.put("orgCode", settings.orgCode());
            req.put("chkPnu", settings.krasChkPnu());

            JsonNode res = post(req);
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
                    coordTransformer.getTargetEpsg(), rows, null, "KRAS");
        }
        return saved;
    }

    private void recreateSchemaTables(List<TargetWithSchema> targets) {
        for (TargetWithSchema ts : targets) {
            log.info("[KRAS] recreating sync tables on target {} (schema={})",
                    ts.target().label(), ts.schema() != null ? ts.schema() : "default");
            odsRepository.recreateSchemaTables(ts.target().jdbc(), ts.schema());
        }
    }

    private List<String> fetchAvailableUsezoneLayers() {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("service", "GetLayerList");
            req.put("connSysId", settings.krasConnSysId());
            req.put("orgCode", settings.orgCode());

            JsonNode res = post(req);
            List<String> names = new ArrayList<>();
            for (JsonNode layer : res.path("layers")) {
                String name = layer.path("layerName").asText("");
                if (name.startsWith("USEZONE:")) names.add(name);
            }
            return names;
        } catch (Exception e) {
            log.error("[KRAS] 레이어 목록 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchFeatures(String layerName, SyncTableDef def) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("service", "GetFeature");
            req.put("connSysId", settings.krasConnSysId());
            req.put("orgCode", settings.orgCode());
            req.put("layerName", layerName);
            req.put("srsName", "EPSG:" + KRAS_EPSG);

            JsonNode res = post(req);
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
                    row.put(col.srcName, coordTransformer.transformWkt(wkt, KRAS_EPSG));
                }
            } else {
                JsonNode val = feature.path(col.srcName);
                row.put(col.srcName, val.isMissingNode() ? null : val.asText(null));
            }
        }
        return row;
    }

    private JsonNode post(ObjectNode body) throws Exception {
        gpkiService.addAuthentication(body);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(settings.krasUrl());
            request.setEntity(new StringEntity(objectMapper.writeValueAsString(body),
                    ContentType.APPLICATION_JSON));

            return client.execute(request, response -> {
                String responseText = EntityUtils.toString(response.getEntity());
                return objectMapper.readTree(gpkiService.decodeResponse(responseText));
            });
        }
    }
}
