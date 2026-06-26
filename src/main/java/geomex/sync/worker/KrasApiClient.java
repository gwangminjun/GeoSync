package geomex.sync.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import geomex.sync.service.KrasGpkiService;
import geomex.sync.service.RuntimeSettingsService;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class KrasApiClient implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KrasApiClient.class);
    private static final int KRAS_EPSG = 5174;

    private final KrasGpkiService gpkiService;
    private final RuntimeSettingsService settings;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    public KrasApiClient(KrasGpkiService gpkiService, RuntimeSettingsService settings) {
        this.gpkiService = gpkiService;
        this.settings = settings;
    }

    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            JsonNode res = check();
            result.put("resultCode", res.path("resultCode").asText(""));
            result.put("resultMsg", res.path("resultMsg").asText(""));
            result.put("success", "00".equals(res.path("resultCode").asText("")));
            result.put("orgCode", settings.orgCode());
            result.put("url", settings.krasUrl());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 연결 확인: estateGateway 기본 파라미터로 POST → HEADER CODE=0000 검증.
     */
    public JsonNode check() throws Exception {
        gpkiService.assertReady();
        if (gpkiService.isEnabled()) {
            log.info("[KRAS] GPKI authentication enabled (gpki_id={})", gpkiService.gpkiId());
        }
        Map<String, String> params = baseParams();
        gpkiService.addAuthentication(params);
        Document doc = postXml(params);

        String code = textOf(doc, "CODE");
        String msg  = textOf(doc, "MESSAGE");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("resultCode", "0000".equals(code) ? "00" : (code != null ? code : "99"));
        result.put("resultMsg",  msg != null ? msg : "");
        return result;
    }

    /**
     * 레이어 목록 조회: CONT_CMAP_LAYER_LIST_SET 파싱.
     * 반환 형식: {"layers": [{"layerName": "LSMD_CONT_UB201", "layerAlias": "..."}, ...]}
     */
    public JsonNode layerList() throws Exception {
        Map<String, String> params = baseParams();
        Document doc = postXml(params);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode layersNode = objectMapper.createArrayNode();

        NodeList items = doc.getElementsByTagName("CONT_CMAP_LAYER_LIST");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String layerCd = childText(item, "LAYER_CD");
            String layerNm = childText(item, "LAYER_NM");
            if (layerCd == null || layerCd.isBlank()) continue;

            // "MLTM.LSMD_CONT_UB201" → "LSMD_CONT_UB201"
            String name = layerCd.contains(".")
                    ? layerCd.substring(layerCd.lastIndexOf('.') + 1)
                    : layerCd;
            ObjectNode layerNode = objectMapper.createObjectNode();
            layerNode.put("layerName",  name);
            layerNode.put("layerAlias", layerNm != null ? layerNm : "");
            layersNode.add(layerNode);
        }
        result.set("layers", layersNode);
        return result;
    }

    public List<Map<String, Object>> testLayerList() throws Exception {
        JsonNode res = layerList();
        List<Map<String, Object>> layers = new ArrayList<>();
        for (JsonNode layer : res.path("layers")) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("layerName",  layer.path("layerName").asText(""));
            info.put("layerAlias", layer.path("layerAlias").asText(""));
            layers.add(info);
        }
        return layers;
    }

    /**
     * 피처 조회: 기본 파라미터 + service=GetFeature + layer_cd + srs_name 으로 POST.
     * 반환 형식: {"resultCode": "00", "features": [{"mnum": "...", "wkt": "MULTIPOLYGON(...)", ...}]}
     */
    public JsonNode features(String layerName) throws Exception {
        Map<String, String> params = baseParams();
        // "LSMD_CONT_UB201" → "MLTM.LSMD_CONT_UB201"
        String fullLayerName = layerName.contains(".") ? layerName : "MLTM." + layerName;
        params.put("service",    "GetFeature");
        params.put("layer_cd",   fullLayerName);
        params.put("srs_name",   "EPSG:" + KRAS_EPSG);

        Document doc = postXml(params);

        String code = textOf(doc, "CODE");
        String msg  = textOf(doc, "MESSAGE");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("resultCode", "0000".equals(code) ? "00" : (code != null ? code : "99"));
        result.put("resultMsg",  msg != null ? msg : "");

        ArrayNode featuresNode = objectMapper.createArrayNode();
        NodeList featureEls = findFeatureElements(doc);
        if (featureEls != null) {
            for (int i = 0; i < featureEls.getLength(); i++) {
                if (!(featureEls.item(i) instanceof Element featureEl)) continue;
                ObjectNode featureNode = objectMapper.createObjectNode();
                NodeList children = featureEl.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (!(child instanceof Element el)) continue;
                    String key   = el.getTagName().toLowerCase();
                    String value = el.getTextContent() != null ? el.getTextContent().trim() : "";
                    // 좌표 필드 이름 통일 → wkt
                    if ("geometry".equals(key) || "geom".equals(key) || "shape".equals(key)) {
                        key = "wkt";
                    }
                    featureNode.put(key, value);
                }
                featuresNode.add(featureNode);
            }
        }
        result.set("features", featuresNode);
        return result;
    }

    public Map<String, Object> testFeatures(String layerName, int limit) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode res = features(layerName);
        result.put("resultCode", res.path("resultCode").asText(""));
        result.put("resultMsg",  res.path("resultMsg").asText(""));
        int total = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode feature : res.path("features")) {
            total++;
            if (rows.size() < Math.min(limit, 100)) {
                Map<String, Object> row = new LinkedHashMap<>();
                feature.fields().forEachRemaining(e -> {
                    String k = e.getKey();
                    String v = e.getValue().asText(null);
                    if ("wkt".equals(k) && v != null && v.length() > 60) {
                        row.put(k, v.substring(0, 60) + "...");
                    } else {
                        row.put(k, v);
                    }
                });
                rows.add(row);
            }
        }
        result.put("total", total);
        result.put("rows",  rows);
        return result;
    }

    // ──────────────────────────────────────────────────────────────────
    // 내부 유틸리티
    // ──────────────────────────────────────────────────────────────────

    /** estateGateway 공통 기본 파라미터 */
    private Map<String, String> baseParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("conn_svc_id", settings.krasConnSvcId());
        params.put("conn_sys_id", settings.krasConnSysId());
        params.put("adm_sect_cd", settings.orgCode());
        params.put("adm_sec_cd",  settings.orgCode());
        return params;
    }

    /** Form POST → XML Document 파싱 */
    private Document postXml(Map<String, String> params) throws Exception {
        List<NameValuePair> pairs = new ArrayList<>();
        params.forEach((k, v) -> pairs.add(new BasicNameValuePair(k, v)));

        HttpPost request = new HttpPost(settings.krasUrl());
        request.setEntity(new UrlEncodedFormEntity(pairs, StandardCharsets.UTF_8));

        String responseText = httpClient.execute(request, response ->
                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));

        responseText = gpkiService.decodeResponse(responseText);
        log.debug("[KRAS] response ({}chars): {}", responseText.length(),
                responseText.length() > 300 ? responseText.substring(0, 300) + "..." : responseText);

        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(responseText.getBytes(StandardCharsets.UTF_8)));
    }

    /** XML 전체에서 태그명으로 첫 번째 텍스트 추출 */
    private static String textOf(Document doc, String tagName) {
        NodeList nl = doc.getElementsByTagName(tagName);
        return nl.getLength() > 0 ? nl.item(0).getTextContent() : null;
    }

    /** Element 직계 자식에서 태그명으로 텍스트 추출 */
    private static String childText(Element el, String tagName) {
        NodeList nl = el.getElementsByTagName(tagName);
        return nl.getLength() > 0 ? nl.item(0).getTextContent() : null;
    }

    /**
     * XML에서 피처 목록 NodeList 탐색.
     * KRAS 응답 구조에 따라 일반적인 태그명을 순서대로 시도한다.
     */
    private static NodeList findFeatureElements(Document doc) {
        for (String tag : new String[]{"FEATURE", "ROW", "DATA", "ITEM", "RECORD"}) {
            NodeList nl = doc.getElementsByTagName(tag);
            if (nl.getLength() > 0) return nl;
        }
        return null;
    }

    @Override
    public void destroy() throws IOException {
        httpClient.close();
    }
}
