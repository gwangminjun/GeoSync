package geosync.gateway;

import geosync.kras.KorepsApiClient;
import geosync.kras.KrasApiClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API 테스트 화면 전용 게이트웨이 프록시.
 * 연결 정보(URL / conn_sys_id / 기관코드)를 파라미터로 받아 게이트웨이를 직접 호출한다.
 * null/빈 값이면 서버 설정값으로 폴백한다.
 */
@RestController
public class ApiTestProxyController {

    private final KrasApiClient krasApiClient;
    private final KorepsApiClient korepsApiClient;

    public ApiTestProxyController(KrasApiClient krasApiClient, KorepsApiClient korepsApiClient) {
        this.krasApiClient  = krasApiClient;
        this.korepsApiClient = korepsApiClient;
    }

    @GetMapping(value = "/api-test/call", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> call(
            @RequestParam String connSvcId,
            @RequestParam(required = false, defaultValue = "") String pnu,
            @RequestParam(required = false, defaultValue = "") String bno,
            @RequestParam(required = false) String map_width,
            @RequestParam(required = false) String map_height,
            @RequestParam(required = false) String legend_width,
            @RequestParam(required = false) String legend_height,
            @RequestParam(required = false) String scale,
            @RequestParam(required = false) String gatewayUrl,
            @RequestParam(required = false) String connSysId,
            @RequestParam(required = false) String orgCode,
            @RequestParam(defaultValue = "false") boolean bodyOnly) {

        if (!pnu.isEmpty() && !pnu.matches("\\d{19}")) {
            return errXml("PNU 형식 오류: 19자리 숫자여야 합니다", 400);
        }

        Map<String, String> extra = new LinkedHashMap<>();
        if (!bno.isBlank())        extra.put("bldg_gbn_no",   bno);
        if (map_width    != null)  extra.put("map_width",     map_width);
        if (map_height   != null)  extra.put("map_height",    map_height);
        if (legend_width != null)  extra.put("legend_width",  legend_width);
        if (legend_height!= null)  extra.put("legend_height", legend_height);
        if (scale        != null)  extra.put("scale",         scale);

        try {
            byte[] xml;
            if (connSvcId.startsWith("KRAS")) {
                xml = krasApiClient.queryDirect(connSvcId, pnu,
                        extra.isEmpty() ? null : extra, gatewayUrl, connSysId, orgCode);
            } else if (connSvcId.startsWith("KOREPS")) {
                xml = korepsApiClient.queryDirect(connSvcId, pnu,
                        extra.isEmpty() ? null : extra, gatewayUrl, connSysId, orgCode);
            } else {
                return errXml("지원하지 않는 서비스 코드: " + connSvcId, 400);
            }

            String body = bodyOnly
                    ? KrasConnController.extractBodyXml(xml)
                    : new String(xml, StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                    .body(body);
        } catch (Exception e) {
            return errXml(e.getMessage(), 500);
        }
    }

    private ResponseEntity<String> errXml(String msg, int status) {
        String safe = msg == null ? "unknown" : msg.replace("<", "&lt;").replace(">", "&gt;");
        return ResponseEntity.status(status)
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><error><message>" + safe + "</message></error>");
    }
}
