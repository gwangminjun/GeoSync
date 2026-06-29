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
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * KRAS estateGateway API 클라이언트.
 *
 * 모든 요청은 Form POST (application/x-www-form-urlencoded) 방식.
 *  - KRAS000037 : 레이어 목록 조회 (XML 응답)
 *  - KRAS000038 : SHP/DBF/SHX 바이너리 다운로드 (file_type 2/3/4)
 */
@Component
public class KrasApiClient implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KrasApiClient.class);

    /** KRAS 서비스 ID 상수 */
    private static final String SVC_LAYER_LIST = "KRAS000037";
    private static final String SVC_DOWNLOAD   = "KRAS000038";
    private static final String SVC_JIGA_TXT   = "KRAS000039";
    private static final String SVC_LAND_TXT   = "KRAS000040";

    /** SHP 파일 타입 코드 */
    public static final int FILE_TYPE_SHP = 2;
    public static final int FILE_TYPE_DBF = 3;
    public static final int FILE_TYPE_SHX = 4;

    private final KrasGpkiService gpkiService;
    private final RuntimeSettingsService settings;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    public KrasApiClient(KrasGpkiService gpkiService, RuntimeSettingsService settings) {
        this.gpkiService = gpkiService;
        this.settings = settings;
    }

    // ──────────────────────────────────────────────────────────────────
    // 연결 확인 (KRAS000037)
    // ──────────────────────────────────────────────────────────────────

    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            JsonNode res = check();
            result.put("resultCode", res.path("resultCode").asText(""));
            result.put("resultMsg",  res.path("resultMsg").asText(""));
            result.put("success", "00".equals(res.path("resultCode").asText("")));
            result.put("orgCode", settings.orgCode());
            result.put("url", settings.krasUrl());

            // Phase 4: KRAS000011 PNU 존재 확인 (chk-pnu 설정 시)
            String chkPnu = settings.krasChkPnu();
            if (chkPnu != null && !chkPnu.isBlank()) {
                try {
                    byte[] pnuResult = checkPnu(chkPnu);
                    String pnuXml = new String(pnuResult, StandardCharsets.UTF_8);
                    result.put("pnuCheckOk", pnuXml.contains("0000"));
                    result.put("pnuCheckPnu", chkPnu);
                } catch (Exception ex) {
                    result.put("pnuCheckOk", false);
                    result.put("pnuCheckError", ex.getMessage());
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 연결 확인: KRAS000037 POST → HEADER CODE=0000 검증.
     * 반환: {"resultCode": "00", "resultMsg": "SUCCESS"}
     */
    public JsonNode check() throws Exception {
        gpkiService.assertReady();
        if (gpkiService.isEnabled()) {
            log.info("[KRAS] GPKI 인증 활성화 (gpki_id={})", gpkiService.gpkiId());
        }
        Map<String, String> params = baseParams(SVC_LAYER_LIST);
        gpkiService.addAuthentication(params);
        Document doc = postXml(params);

        String code = textOf(doc, "CODE");
        String msg  = textOf(doc, "MESSAGE");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("resultCode", "0000".equals(code) ? "00" : (code != null ? code : "99"));
        result.put("resultMsg",  msg != null ? msg : "");
        return result;
    }

    // ──────────────────────────────────────────────────────────────────
    // 레이어 목록 조회 (KRAS000037)
    // ──────────────────────────────────────────────────────────────────

    /**
     * 레이어 목록 조회: KRAS000037 POST → CONT_CMAP_LAYER_LIST_SET 파싱.
     * 반환: {"layers": [{"layerName": "LSMD_CONT_UB201", "layerAlias": "..."}, ...]}
     */
    public JsonNode layerList() throws Exception {
        Map<String, String> params = baseParams(SVC_LAYER_LIST);
        Document doc = postXml(params);

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

        ObjectNode result = objectMapper.createObjectNode();
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

    // ──────────────────────────────────────────────────────────────────
    // SHP 다운로드 (KRAS000038)
    // ──────────────────────────────────────────────────────────────────

    /**
     * 단일 파일 다운로드: KRAS000038 POST → 바이너리 응답.
     *
     * @param layerCd  레이어 코드 (예: "LSMD_CONT_LDREG", "LSMD_CONT_UB201")
     * @param fileType {@link #FILE_TYPE_SHP}=2, {@link #FILE_TYPE_DBF}=3, {@link #FILE_TYPE_SHX}=4
     */
    public byte[] downloadFile(String layerCd, int fileType) throws Exception {
        Map<String, String> params = baseParams(SVC_DOWNLOAD);
        params.put("layer_cd",  layerCd);
        params.put("file_type", String.valueOf(fileType));
        gpkiService.addAuthentication(params);
        return postBinary(params);
    }

    /**
     * SHP/DBF/SHX 3종 다운로드 후 outputDir에 저장.
     *
     * @param layerCd   레이어 코드 (예: "LSMD_CONT_LDREG")
     * @param outputDir 저장 디렉토리
     * @return 파일 기본명 (예: "lsmd_cont_ldreg") — outputDir/{baseName}.shp 로 저장됨
     */
    public String downloadLayer(String layerCd, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        String baseName = layerCd.toLowerCase();

        byte[] shpData = downloadFile(layerCd, FILE_TYPE_SHP);
        byte[] dbfData = downloadFile(layerCd, FILE_TYPE_DBF);
        byte[] shxData = downloadFile(layerCd, FILE_TYPE_SHX);

        Files.write(outputDir.resolve(baseName + ".shp"), shpData);
        Files.write(outputDir.resolve(baseName + ".dbf"), dbfData);
        Files.write(outputDir.resolve(baseName + ".shx"), shxData);

        log.info("[KRAS] SHP 다운로드 완료: {} (shp={}B, dbf={}B, shx={}B)",
                layerCd, shpData.length, dbfData.length, shxData.length);
        return baseName;
    }

    // ──────────────────────────────────────────────────────────────────
    // 단건 조회 (KRAS000002 ~ KRAS000103)
    // ──────────────────────────────────────────────────────────────────

    /**
     * KRAS000011 PNU 존재 여부 확인.
     * testConnection()에서 레이어 목록(KRAS000037) 외 추가 검증에 사용.
     */
    public byte[] checkPnu(String pnu) throws Exception {
        return query("KRAS000011", pnu, null);
    }

    /**
     * PNU 기반 단건 조회: conn_svc_id + pnu [+ extraParams] → raw XML bytes.
     *
     * @param connSvcId  서비스 코드 (예: KRAS000002)
     * @param pnu        필지번호
     * @param extraParams 추가 파라미터 (bno, map_width 등, null 가능)
     */
    public byte[] query(String connSvcId, String pnu, Map<String, String> extraParams) throws Exception {
        Map<String, String> params = baseParams(connSvcId);
        params.put("pnu", pnu != null ? pnu : "");
        if (extraParams != null) params.putAll(extraParams);
        gpkiService.addAuthentication(params);
        log.debug("[KRAS] query svc={} pnu={}", connSvcId, pnu);
        return postRaw(params);
    }

    // ──────────────────────────────────────────────────────────────────
    // TXT 다운로드 (KRAS000039 / KRAS000040)
    // ──────────────────────────────────────────────────────────────────

    /** 공시지가 TXT 다운로드 (KRAS000039) */
    public byte[] downloadJigaTxt() throws Exception {
        Map<String, String> params = baseParams(SVC_JIGA_TXT);
        gpkiService.addAuthentication(params);
        return postBinary(params);
    }

    /** 토지대장 TXT 다운로드 (KRAS000040) */
    public byte[] downloadLandTxt() throws Exception {
        Map<String, String> params = baseParams(SVC_LAND_TXT);
        gpkiService.addAuthentication(params);
        return postBinary(params);
    }

    // ──────────────────────────────────────────────────────────────────
    // 내부 유틸리티
    // ──────────────────────────────────────────────────────────────────

    /**
     * 공통 기본 파라미터.
     * Postman 연계문서 기준: conn_svc_id / conn_sys_id / adm_sec_cd
     */
    private Map<String, String> baseParams(String connSvcId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("conn_svc_id", connSvcId);
        params.put("conn_sys_id", settings.krasConnSysId());
        params.put("adm_sec_cd",  settings.orgCode());
        return params;
    }

    /** Form POST → XML Document 파싱 */
    private Document postXml(Map<String, String> params) throws Exception {
        byte[] data = postRaw(params);
        String text = gpkiService.decodeResponse(new String(data, StandardCharsets.UTF_8));
        log.debug("[KRAS] XML 응답 ({}chars): {}", text.length(),
                text.length() > 300 ? text.substring(0, 300) + "..." : text);
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Form POST → 바이너리 응답 */
    private byte[] postBinary(Map<String, String> params) throws Exception {
        byte[] data = postRaw(params);
        // 오류 응답은 XML 텍스트로 반환됨
        if (data.length > 0 && data[0] == (byte) '<') {
            String errorText = new String(data, StandardCharsets.UTF_8);
            throw new IOException("KRAS 오류 응답: " +
                    errorText.substring(0, Math.min(300, errorText.length())));
        }
        return data;
    }

    /** 실제 HTTP POST 실행 → raw bytes */
    private byte[] postRaw(Map<String, String> params) throws Exception {
        List<NameValuePair> pairs = new ArrayList<>();
        params.forEach((k, v) -> pairs.add(new BasicNameValuePair(k, v != null ? v : "")));

        HttpPost request = new HttpPost(settings.krasUrl());
        request.setEntity(new UrlEncodedFormEntity(pairs, StandardCharsets.UTF_8));

        return httpClient.execute(request, response ->
                EntityUtils.toByteArray(response.getEntity()));
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

    @Override
    public void destroy() throws IOException {
        httpClient.close();
    }
}
