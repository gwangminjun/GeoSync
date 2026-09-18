package geosync.gateway;

import geosync.common.xml.XmlUtil;
import geosync.kras.KorepsApiClient;
import geosync.kras.KrasApiClient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * kras 웹앱(/conn/*) 대체 컨트롤러.
 *
 * YG-Space가 기존에 http://110.20.1.199:9080/kras/conn/{path}?pnu=... 로 호출하던
 * 엔드포인트를 geosync가 대신 제공한다.
 */
@RestController
@RequestMapping("/conn")
public class KrasConnController {

    private static final Logger log = LoggerFactory.getLogger(KrasConnController.class);

    private final KrasApiClient krasApiClient;
    private final KorepsApiClient korepsApiClient;
    private final ConnRequestLogService connLog;
    private final HttpServletRequest request;

    public KrasConnController(KrasApiClient krasApiClient, KorepsApiClient korepsApiClient,
                              ConnRequestLogService connLog, HttpServletRequest request) {
        this.krasApiClient = krasApiClient;
        this.korepsApiClient = korepsApiClient;
        this.connLog = connLog;
        this.request = request;
    }

    @GetMapping(value = "/{path}/body", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> connQueryBody(
            @PathVariable String path,
            @RequestParam(required = false, defaultValue = "") String pnu,
            @RequestParam(required = false, defaultValue = "") String bno,
            @RequestParam(required = false) String map_width,
            @RequestParam(required = false) String map_height,
            @RequestParam(required = false) String legend_width,
            @RequestParam(required = false) String legend_height,
            @RequestParam(required = false) String scale) {
        return handle(path, pnu, bno, map_width, map_height, legend_width, legend_height, scale, true);
    }

    @GetMapping(value = "/{path}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> connQuery(
            @PathVariable String path,
            @RequestParam(required = false, defaultValue = "") String pnu,
            @RequestParam(required = false, defaultValue = "") String bno,
            @RequestParam(required = false) String map_width,
            @RequestParam(required = false) String map_height,
            @RequestParam(required = false) String legend_width,
            @RequestParam(required = false) String legend_height,
            @RequestParam(required = false) String scale) {
        return handle(path, pnu, bno, map_width, map_height, legend_width, legend_height, scale, false);
    }

    private ResponseEntity<String> handle(String path, String pnu, String bno,
            String mapWidth, String mapHeight, String legendWidth, String legendHeight,
            String scale, boolean bodyOnly) {
        long t0 = System.currentTimeMillis();
        String ip = ConnRequestLogService.clientIp(request);
        String svcId = GatewayPaths.KRAS.containsKey(path)
                ? GatewayPaths.KRAS.get(path) : GatewayPaths.KOREPS.get(path);
        String tag = bodyOnly ? "[ConnAPI/body]" : "[ConnAPI]";

        if (!isValidPnu(pnu)) {
            connLog.record("CONN", path, svcId, pnu, bno, ip,
                    ConnRequestLogService.ST_BAD_REQ, null, "PNU 형식 오류",
                    System.currentTimeMillis() - t0);
            return badRequest(errorXml(path, "PNU 형식 오류: 19자리 숫자여야 합니다"));
        }
        log.info("{} {} pnu={} bno={} ip={}", tag, path, pnu, bno, ip);
        try {
            byte[] xml = fetchXml(path, pnu, bno, mapWidth, mapHeight, legendWidth, legendHeight, scale);
            String body = bodyOnly ? extractBodyXml(xml) : new String(xml, StandardCharsets.UTF_8);
            String gwCode = ConnRequestLogService.gwCode(xml);
            String status = (gwCode == null || "0000".equals(gwCode))
                    ? ConnRequestLogService.ST_SUCCESS : ConnRequestLogService.ST_GW_ERROR;
            connLog.record("CONN", path, svcId, pnu, bno, ip,
                    status, gwCode, null, System.currentTimeMillis() - t0);
            return xmlOk(body);
        } catch (Exception e) {
            log.error("{} {} 조회 실패: {}", tag, path, e.getMessage());
            connLog.record("CONN", path, svcId, pnu, bno, ip,
                    ConnRequestLogService.ST_FAILED, null, e.getMessage(),
                    System.currentTimeMillis() - t0);
            return xmlErr(errorXml(path, e.getMessage()));
        }
    }

    private byte[] fetchXml(String path, String pnu, String bno,
            String mapWidth, String mapHeight,
            String legendWidth, String legendHeight, String scale) throws Exception {
        Map<String, String> extra = buildExtra(path, bno, mapWidth, mapHeight,
                legendWidth, legendHeight, scale);
        String krasSvc = GatewayPaths.KRAS.get(path);
        if (krasSvc != null) return krasApiClient.query(krasSvc, pnu, extra);
        String korepsSvc = GatewayPaths.KOREPS.get(path);
        if (korepsSvc != null) return korepsApiClient.query(korepsSvc, pnu, extra);
        throw new IllegalArgumentException("지원하지 않는 경로: " + path);
    }

    private Map<String, String> buildExtra(String path, String bno,
            String mapWidth, String mapHeight,
            String legendWidth, String legendHeight, String scale) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (GatewayPaths.BLDG_GBN_NO_PATHS.contains(path) && bno != null && !bno.isBlank()) {
            extra.put("bldg_gbn_no", bno);
        }
        if ("land_use_plan_info".equals(path)) {
            if (mapWidth     != null) extra.put("map_width",     mapWidth);
            if (mapHeight    != null) extra.put("map_height",    mapHeight);
            if (legendWidth  != null) extra.put("legend_width",  legendWidth);
            if (legendHeight != null) extra.put("legend_height", legendHeight);
            if (scale        != null) extra.put("scale",         scale);
        }
        return extra;
    }

    static String extractBodyXml(byte[] rawXml) throws Exception {
        Document doc = XmlUtil.parse(rawXml);
        NodeList bodies = doc.getElementsByTagName("BODY");
        if (bodies.getLength() == 0) {
            return new String(rawXml, StandardCharsets.UTF_8);
        }
        Node body = bodies.item(0);
        StringWriter writer = new StringWriter();
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.transform(new DOMSource(body), new StreamResult(writer));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + writer.toString();
    }

    private static boolean isValidPnu(String pnu) {
        return pnu == null || pnu.isBlank() || pnu.matches("\\d{19}");
    }

    private ResponseEntity<String> xmlOk(String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body(body);
    }

    private ResponseEntity<String> xmlErr(String body) {
        return ResponseEntity.internalServerError()
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body(body);
    }

    private ResponseEntity<String> badRequest(String body) {
        return ResponseEntity.badRequest()
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body(body);
    }

    private static String errorXml(String path, String message) {
        String safe = message == null ? "unknown" : message.replace("<", "&lt;").replace(">", "&gt;");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<error><path>" + path + "</path><message>" + safe + "</message></error>";
    }
}
