package geosync.kras;

import geosync.settings.RuntimeSettingsService;
import geosync.common.xml.XmlUtil;
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
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KOREPS estateGateway API 클라이언트.
 *
 * KRAS와 동일하게 Form POST(application/x-www-form-urlencoded) 방식.
 * 차이점:
 *   - 게이트웨이 URL: koreps.url (기본 10.188.226.221:8385)
 *   - conn_sys_id:   koreps.conn-sys-id
 *   - 행정구역 파라미터명: adm_sect_cd (KRAS는 adm_sec_cd)
 */
@Component
public class KorepsApiClient implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KorepsApiClient.class);

    private final RuntimeSettingsService settings;
    private final CloseableHttpClient httpClient;

    public KorepsApiClient(RuntimeSettingsService settings,
                           @Value("${kras.api-timeout-seconds:30}") int apiTimeoutSeconds) {
        this.settings = settings;
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(apiTimeoutSeconds))
                .setResponseTimeout(Timeout.ofSeconds(apiTimeoutSeconds))
                .build();
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build();
        log.info("[KOREPS] HttpClient 타임아웃: {}s", apiTimeoutSeconds);
    }

    /**
     * KOREPS 연결 상태 확인. KOREPS00011 + chkPnu 로 게이트웨이 응답 검증.
     * 응답 XML의 CODE 요소가 0000이면 success=true, 네트워크 오류면 false.
     */
    public Map<String, Object> testConnection(String chkPnu) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", settings.korepsUrl());
        result.put("orgCode", settings.orgCode());
        try {
            byte[] data = query("KOREPS00011", chkPnu != null ? chkPnu : "", null);
            String text = new String(data, StandardCharsets.UTF_8);
            String code = parseCode(data);
            boolean ok = "0000".equals(code) || (code == null && text.startsWith("<?xml"));
            result.put("success", ok);
            result.put("resultCode", code != null ? (ok ? "00" : code) : "");
            result.put("resultMsg", ok ? "연결 성공" : "응답 코드: " + code);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private String parseCode(byte[] data) {
        try {
            Document doc = XmlUtil.parse(data);
            NodeList nl = doc.getElementsByTagName("CODE");
            return nl.getLength() > 0 ? nl.item(0).getTextContent() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * KOREPS 단건 조회: conn_svc_id + PNU 분해 파라미터 [+ bno] → raw XML bytes 반환.
     *
     * 기존 kras 웹앱(KorepsConn)과 동일하게 pnu를 통째로 보내지 않고
     * adm_sect_cd / land_loc_cd / ledg_gbn / bobn / bubn 으로 분해해 POST 한다.
     *
     * @param connSvcId  서비스 코드 (예: KOREPS00011)
     * @param pnu        필지번호 (19자리)
     * @param extraParams 추가 파라미터 (bno 등, null 가능)
     */
    public byte[] query(String connSvcId, String pnu, Map<String, String> extraParams) throws Exception {
        Map<String, String> params = baseParams(connSvcId, pnu);
        if (extraParams != null) params.putAll(extraParams);
        log.debug("[KOREPS] query svc={} pnu={}", connSvcId, pnu);
        return postRaw(params);
    }

    private Map<String, String> baseParams(String connSvcId, String pnu) {
        // 파라미터 구성·순서를 기존 KorepsConn과 동일하게: conn_sys_id, gpki_id(항상), conn_svc_id, PNU분해
        Map<String, String> params = new LinkedHashMap<>();
        params.put("conn_sys_id", settings.korepsConnSysId());
        params.put("gpki_id", "");
        params.put("conn_svc_id", connSvcId);
        KrasApiClient.putPnuParams(params, settings.orgCode(), pnu);
        return params;
    }

    /**
     * 연결 정보를 직접 지정해 단건 조회. API 테스트 화면에서 URL/연결ID/기관코드 오버라이드 시 사용.
     * null 또는 빈 값이면 settings 기본값으로 폴백한다.
     */
    public byte[] queryDirect(String connSvcId, String pnu, Map<String, String> extraParams,
                               String gatewayUrl, String connSysId, String orgCode) throws Exception {
        String url = (gatewayUrl != null && !gatewayUrl.isBlank()) ? gatewayUrl : settings.korepsUrl();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("conn_sys_id", (connSysId != null && !connSysId.isBlank()) ? connSysId : settings.korepsConnSysId());
        params.put("gpki_id", "");
        params.put("conn_svc_id", connSvcId);
        KrasApiClient.putPnuParams(params, (orgCode != null && !orgCode.isBlank()) ? orgCode : settings.orgCode(), pnu);
        if (extraParams != null) params.putAll(extraParams);
        log.debug("[KOREPS] queryDirect svc={} pnu={} url={}", connSvcId, pnu, url);
        return postRawTo(url, params);
    }

    private byte[] postRaw(Map<String, String> params) throws Exception {
        return postRawTo(settings.korepsUrl(), params);
    }

    private byte[] postRawTo(String url, Map<String, String> params) throws Exception {
        // 기존 싱크와 동일: URL 인코딩 없는 raw 본문 + charset 표기 없는 Content-Type
        HttpPost request = new HttpPost(url);
        request.setEntity(new StringEntity(KrasApiClient.joinParams(params),
                ContentType.create("application/x-www-form-urlencoded")));

        byte[] data = httpClient.execute(request, response ->
                EntityUtils.toByteArray(response.getEntity()));

        if (data.length > 0 && data[0] == (byte) '<') {
            String text = new String(data, StandardCharsets.UTF_8);
            if (text.contains("ERROR") || text.contains("error")) {
                log.warn("[KOREPS] 오류 응답 (svc={}): {}", params.get("conn_svc_id"),
                        text.substring(0, Math.min(200, text.length())));
            }
        }
        return data;
    }

    @Override
    public void destroy() throws IOException {
        httpClient.close();
    }
}
