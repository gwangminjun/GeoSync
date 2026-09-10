package geomex.sync.kras;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import geomex.sync.settings.RuntimeSettingsService;
import geomex.sync.common.xml.XmlUtil;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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
    /** 단건 조회용 (api-timeout-seconds) */
    private final CloseableHttpClient httpClient;
    /** SHP/TXT 파일 다운로드용 (timeout-seconds, 응답 크기가 크므로 별도 타임아웃) */
    private final CloseableHttpClient fileHttpClient;

    public KrasApiClient(KrasGpkiService gpkiService, RuntimeSettingsService settings,
                         @Value("${kras.api-timeout-seconds:30}") int apiTimeoutSeconds,
                         @Value("${kras.timeout-seconds:300}") int fileTimeoutSeconds) {
        this.gpkiService = gpkiService;
        this.settings = settings;
        RequestConfig apiConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(apiTimeoutSeconds))
                .setResponseTimeout(Timeout.ofSeconds(apiTimeoutSeconds))
                .build();
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(apiConfig)
                .build();
        RequestConfig fileConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(30))
                .setResponseTimeout(Timeout.ofSeconds(fileTimeoutSeconds))
                .build();
        this.fileHttpClient = HttpClients.custom()
                .setDefaultRequestConfig(fileConfig)
                .build();
        log.info("[KRAS] HttpClient 타임아웃: 단건={}s, 파일={}s", apiTimeoutSeconds, fileTimeoutSeconds);
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
        gpkiService.addGpkiIdAlways(params); // KrasWorker.getUseZoneLayers도 gpki_id를 항상 전송
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
        gpkiService.addGpkiIdAlways(params);
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
     * PNU 기반 단건 조회: conn_svc_id + PNU 분해 파라미터 [+ extraParams] → raw XML bytes.
     *
     * 기존 kras 웹앱(KrasConn)과 동일하게 pnu를 통째로 보내지 않고
     * adm_sect_cd / land_loc_cd / ledg_gbn / bobn / bubn 으로 분해해 POST 한다.
     * (파일 다운로드 계열(KRAS000037~40)은 adm_sec_cd 사용 — baseParams 참고)
     *
     * @param connSvcId  서비스 코드 (예: KRAS000002)
     * @param pnu        필지번호 (19자리)
     * @param extraParams 추가 파라미터 (bno, map_width 등, null 가능)
     */
    public byte[] query(String connSvcId, String pnu, Map<String, String> extraParams) throws Exception {
        // 파라미터 구성·순서를 기존 KrasConn과 동일하게: conn_sys_id, gpki_id(항상), conn_svc_id, PNU분해
        Map<String, String> params = new LinkedHashMap<>();
        params.put("conn_sys_id", settings.krasConnSysId());
        gpkiService.addGpkiIdAlways(params);
        params.put("conn_svc_id", connSvcId);
        putPnuParams(params, settings.orgCode(), pnu);
        if (extraParams != null) params.putAll(extraParams);
        log.debug("[KRAS] query svc={} pnu={}", connSvcId, pnu);
        return postRaw(params);
    }

    /**
     * 단건 조회 파라미터 구성 (기존 KrasConn/KorepsConn.getData 방식).
     * pnu가 19자리면 분해해서 넣고, 아니면 pnu 그대로 전달(하위호환).
     */
    static void putPnuParams(Map<String, String> params, String admSectCd, String pnu) {
        params.put("adm_sect_cd", admSectCd);
        if (pnu != null && pnu.matches("\\d{19}")) {
            params.put("land_loc_cd", pnu.substring(5, 10));
            params.put("ledg_gbn",    pnu.substring(10, 11));
            params.put("bobn",        pnu.substring(11, 15));
            params.put("bubn",        pnu.substring(15));
        } else {
            params.put("pnu", pnu != null ? pnu : "");
        }
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

    /** Form POST → XML Document 파싱 (단건 조회 HttpClient 사용) */
    private Document postXml(Map<String, String> params) throws Exception {
        byte[] data = postRaw(params);
        String text = gpkiService.decodeResponse(new String(data, StandardCharsets.UTF_8));
        log.debug("[KRAS] XML 응답 ({}chars): {}", text.length(),
                text.length() > 300 ? text.substring(0, 300) + "..." : text);
        return XmlUtil.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 연결 정보를 직접 지정해 단건 조회. API 테스트 화면에서 URL/연결ID/기관코드 오버라이드 시 사용.
     * null 또는 빈 값이면 settings 기본값으로 폴백한다.
     */
    public byte[] queryDirect(String connSvcId, String pnu, Map<String, String> extraParams,
                               String gatewayUrl, String connSysId, String orgCode) throws Exception {
        String url = (gatewayUrl != null && !gatewayUrl.isBlank()) ? gatewayUrl : settings.krasUrl();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("conn_sys_id", (connSysId != null && !connSysId.isBlank()) ? connSysId : settings.krasConnSysId());
        gpkiService.addGpkiIdAlways(params);
        params.put("conn_svc_id", connSvcId);
        putPnuParams(params, (orgCode != null && !orgCode.isBlank()) ? orgCode : settings.orgCode(), pnu);
        if (extraParams != null) params.putAll(extraParams);
        log.debug("[KRAS] queryDirect svc={} pnu={} url={}", connSvcId, pnu, url);
        return postRawWith(httpClient, url, params);
    }

    /** Form POST → 바이너리 응답 (파일 다운로드 HttpClient 사용) */
    private byte[] postBinary(Map<String, String> params) throws Exception {
        byte[] data = postRawWith(fileHttpClient, settings.krasUrl(), params);
        if (data.length > 0 && data[0] == (byte) '<') {
            String errorText = new String(data, StandardCharsets.UTF_8);
            throw new IOException("KRAS 오류 응답: " +
                    errorText.substring(0, Math.min(300, errorText.length())));
        }
        return data;
    }

    /** 단건 조회용 HTTP POST → raw bytes */
    private byte[] postRaw(Map<String, String> params) throws Exception {
        return postRawWith(httpClient, settings.krasUrl(), params);
    }

    /**
     * 지정 HttpClient + URL로 Form POST 실행 → raw bytes.
     * 기존 싱크(HttpURLConnection)와 동일하게 URL 인코딩 없는 raw 본문과
     * charset 표기 없는 Content-Type(application/x-www-form-urlencoded)을 사용한다.
     */
    private byte[] postRawWith(CloseableHttpClient client, String url, Map<String, String> params) throws Exception {
        HttpPost request = new HttpPost(url);
        request.setEntity(new StringEntity(joinParams(params),
                ContentType.create("application/x-www-form-urlencoded")));
        return client.execute(request, response ->
                EntityUtils.toByteArray(response.getEntity()));
    }

    /** 기존 MapUtils.join(params, "=", "&")과 동일한 raw 본문 생성 */
    static String joinParams(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(k).append('=').append(v != null ? v : "");
        });
        return sb.toString();
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
        fileHttpClient.close();
    }
}
