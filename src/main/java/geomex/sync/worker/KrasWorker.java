package geomex.sync.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import geomex.sync.geo.CoordTransformer;
import geomex.sync.mapper.TableMapper;
import geomex.sync.model.ColumnDef;
import geomex.sync.model.SyncTableDef;
import geomex.sync.repository.OdsRepository;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class KrasWorker {

    private static final Logger log = LoggerFactory.getLogger(KrasWorker.class);
    private static final int KRAS_EPSG = 5174;

    private final TableMapper tableMapper;
    private final OdsRepository odsRepository;
    private final CoordTransformer coordTransformer;
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
                      CoordTransformer coordTransformer) {
        this.tableMapper = tableMapper;
        this.odsRepository = odsRepository;
        this.coordTransformer = coordTransformer;
    }

    public void run() {
        log.info("[KRAS] 동기화 시작 (url={})", gatewayUrl);

        if (!checkConnection()) {
            log.error("[KRAS] 연결 확인 실패 — 동기화 중단");
            return;
        }

        List<SyncTableDef> tableDefs = tableMapper.load(configPath);
        for (SyncTableDef def : tableDefs) {
            processTable(def);
        }
        log.info("[KRAS] 동기화 완료");
    }

    private boolean checkConnection() {
        try {
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

    private void processTable(SyncTableDef def) {
        // USEZONE:LSMD_CONT_XXXXX 형태의 레이어명 처리
        String layerName = def.srcTableName.contains("XXXXX")
                ? fetchUszoneLayerNames(def)
                : def.srcTableName;

        if (layerName == null) return;

        // USEZONE 레이어는 여러 개를 합쳐서 하나의 테이블에 저장
        if (def.srcTableName.startsWith("USEZONE:")) {
            processUsezoneTable(def);
        } else {
            List<Map<String, Object>> rows = fetchFeatures(layerName, def);
            odsRepository.replaceAll(def, orgCode, coordTransformer.getTargetEpsg(), rows);
        }
    }

    private void processUsezoneTable(SyncTableDef def) {
        List<String> layerNames = fetchAvailableUsezoneLayers();
        List<Map<String, Object>> allRows = new ArrayList<>();

        for (String layerName : layerNames) {
            allRows.addAll(fetchFeatures(layerName, def));
        }
        odsRepository.replaceAll(def, orgCode, coordTransformer.getTargetEpsg(), allRows);
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

    private String fetchUszoneLayerNames(SyncTableDef def) {
        // USEZONE:LSMD_CONT_XXXXX → 실제 레이어 목록 조회로 대체
        return "USEZONE_AGGREGATE";
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
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(gatewayUrl);
            request.setEntity(new StringEntity(objectMapper.writeValueAsString(body),
                    ContentType.APPLICATION_JSON));

            return client.execute(request, response -> {
                try (InputStream is = response.getEntity().getContent()) {
                    return objectMapper.readTree(is);
                }
            });
        }
    }
}
