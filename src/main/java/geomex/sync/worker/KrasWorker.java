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
import geomex.sync.service.SyncStatusService;
import geomex.sync.service.TargetDbService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${kras.url}")
    private String gatewayUrl;

    @Value("${kras.conn-sys-id}")
    private String connSysId;

    @Value("${kras.chk-pnu}")
    private String chkPnu;

    @Value("${kras.config:conf/kras/base-tables.xml}")
    private String configPath;

    @Value("${sync.org-code:46870}")
    private String orgCode;

    public KrasWorker(TableMapper tableMapper, OdsRepository odsRepository,
                      CoordTransformer coordTransformer, SyncStatusService statusService,
                      TargetDbService targetDbService, KrasFileWriter fileWriter,
                      KrasFileReader fileReader, KrasGpkiService gpkiService) {
        this.tableMapper = tableMapper;
        this.odsRepository = odsRepository;
        this.coordTransformer = coordTransformer;
        this.statusService = statusService;
        this.targetDbService = targetDbService;
        this.fileWriter = fileWriter;
        this.fileReader = fileReader;
        this.gpkiService = gpkiService;
    }

    public void run() {
        log.info("[KRAS] 동기화 시작 (url={})", gatewayUrl);
        statusService.recordStart("KRAS");
        int totalSuccess = 0, totalError = 0;
        boolean failed = false;
        try {
            if (!checkConnection()) {
                log.error("[KRAS] 연결 확인 실패 — 동기화 중단");
                failed = true;
                return;
            }
            List<SyncTableDef> tableDefs = tableMapper.load(configPath);
            Map<String, List<String>> manifest = new LinkedHashMap<>();
            for (SyncTableDef def : tableDefs) {
                int saved = processTable(def, manifest);
                if (saved >= 0) totalSuccess += saved;
                else totalError++;
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
        log.info("[KRAS] 수집 시작 (API → 파일, url={})", gatewayUrl);
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
            List<SyncTableDef> tableDefs = tableMapper.load(configPath);
            for (SyncTableDef def : tableDefs) {
                try {
                    List<String> fileBaseNames = collectTable(def);
                    manifest.put(def.srcTableName, fileBaseNames);
                    totalSuccess += fileBaseNames.size();
                } catch (Exception e) {
                    log.error("[KRAS] {} 수집 실패: {}", def.srcTableName, e.getMessage());
                    totalError++;
                }
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
        runLoad(null, null);
    }

    public void runLoad(List<Integer> targetIndices, String schemaOverride) {
        log.info("[KRAS] 적재 시작 (파일 → DB, schema={}, targets={})",
                schemaOverride != null ? schemaOverride : "기본",
                targetIndices != null ? targetIndices : "전체");
        statusService.recordStart("KRAS_LOAD");
        int totalSuccess = 0, totalError = 0;
        boolean failed = false;
        try {
            List<TargetDbService.ActiveTarget> allTargets = targetDbService.getConfiguredTargets();
            List<JdbcTemplate> selectedJdbcs = selectTargets(allTargets, targetIndices);
            if (selectedJdbcs.isEmpty()) {
                log.warn("[KRAS] 선택된 대상 DB 없음 — 적재 중단");
                failed = true;
                return;
            }

            Map<String, List<String>> manifest = fileReader.readManifest();
            List<SyncTableDef> tableDefs = tableMapper.load(configPath);
            for (SyncTableDef def : tableDefs) {
                List<String> fileBaseNames = manifest.getOrDefault(def.srcTableName, List.of());
                if (fileBaseNames.isEmpty()) {
                    log.warn("[KRAS] {} 에 대한 수집 파일 없음 — 건너뜀", def.srcTableName);
                    continue;
                }
                try {
                    int saved = loadTable(def, fileBaseNames, selectedJdbcs, schemaOverride);
                    if (saved >= 0) totalSuccess += saved;
                    else totalError++;
                } catch (Exception e) {
                    log.error("[KRAS] {} 적재 실패: {}", def.srcTableName, e.getMessage());
                    totalError++;
                }
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

    private List<JdbcTemplate> selectTargets(List<TargetDbService.ActiveTarget> all, List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return all.stream().map(TargetDbService.ActiveTarget::jdbc).toList();
        }
        return indices.stream()
                .filter(i -> i >= 0 && i < all.size())
                .map(i -> all.get(i).jdbc())
                .toList();
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
                          List<JdbcTemplate> targets, String schemaOverride) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String baseName : fileBaseNames) {
            rows.addAll(fileReader.readJson(baseName));
        }
        if (targets.isEmpty()) {
            log.warn("[KRAS] 활성 대상 DB 없음 — {} 저장 건너뜀", def.tgtTableName);
            return 0;
        }
        int saved = 0;
        for (JdbcTemplate jdbc : targets) {
            saved = odsRepository.replaceAllTo(jdbc, def, orgCode, coordTransformer.getTargetEpsg(), rows, schemaOverride);
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
            req.put("connSysId", connSysId);
            req.put("orgCode", orgCode);
            req.put("chkPnu", chkPnu);

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

    private int processTable(SyncTableDef def, Map<String, List<String>> manifest) {
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
        return saveToAllTargets(def, rows);
    }

    private int saveToAllTargets(SyncTableDef def, List<Map<String, Object>> rows) {
        List<JdbcTemplate> targets = targetDbService.getActiveTemplates();
        if (targets.isEmpty()) {
            log.warn("[KRAS] 활성 대상 DB 없음 — {} 저장 건너뜀", def.tgtTableName);
            return 0;
        }
        int saved = 0;
        for (JdbcTemplate jdbc : targets) {
            saved = odsRepository.replaceAllTo(jdbc, def, orgCode, coordTransformer.getTargetEpsg(), rows);
        }
        return saved;
    }

    private List<String> fetchAvailableUsezoneLayers() {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("service", "GetLayerList");
            req.put("connSysId", connSysId);
            req.put("orgCode", orgCode);

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
            req.put("connSysId", connSysId);
            req.put("orgCode", orgCode);
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
            HttpPost request = new HttpPost(gatewayUrl);
            request.setEntity(new StringEntity(objectMapper.writeValueAsString(body),
                    ContentType.APPLICATION_JSON));

            return client.execute(request, response -> {
                String responseText = EntityUtils.toString(response.getEntity());
                return objectMapper.readTree(gpkiService.decodeResponse(responseText));
            });
        }
    }
}
