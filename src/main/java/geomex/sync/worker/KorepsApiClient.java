package geomex.sync.worker;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    public KorepsApiClient(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    /**
     * KOREPS 단건 조회: conn_svc_id + pnu [+ bno] → raw XML bytes 반환.
     *
     * @param connSvcId  서비스 코드 (예: KOREPS00011)
     * @param pnu        필지번호
     * @param extraParams 추가 파라미터 (bno 등, null 가능)
     */
    public byte[] query(String connSvcId, String pnu, Map<String, String> extraParams) throws Exception {
        Map<String, String> params = baseParams(connSvcId, pnu);
        if (extraParams != null) params.putAll(extraParams);
        log.debug("[KOREPS] query svc={} pnu={}", connSvcId, pnu);
        return postRaw(params);
    }

    private Map<String, String> baseParams(String connSvcId, String pnu) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("conn_svc_id", connSvcId);
        params.put("conn_sys_id", settings.korepsConnSysId());
        params.put("adm_sect_cd", settings.orgCode());
        params.put("pnu",         pnu != null ? pnu : "");
        return params;
    }

    private byte[] postRaw(Map<String, String> params) throws Exception {
        List<NameValuePair> pairs = new ArrayList<>();
        params.forEach((k, v) -> pairs.add(new BasicNameValuePair(k, v != null ? v : "")));

        HttpPost request = new HttpPost(settings.korepsUrl());
        request.setEntity(new UrlEncodedFormEntity(pairs, StandardCharsets.UTF_8));

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
