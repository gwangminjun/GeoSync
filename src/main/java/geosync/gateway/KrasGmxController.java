package geosync.gateway;

import geosync.common.xml.XmlUtil;
import geosync.kras.KorepsApiClient;
import geosync.kras.KrasApiClient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * kras 웹앱(/svc/*) 대체 GMX 조합 API 컨트롤러.
 *
 * YG-Space가 기존에 http://110.20.1.199:9080/kras/svc/{service} 로 호출하던
 * 엔드포인트를 대신 제공한다. 단건 또는 여러 estateGateway 응답을 &lt;GMX&gt; 래퍼로 조합해 반환한다.
 */
@RestController
@RequestMapping("/svc")
public class KrasGmxController implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KrasGmxController.class);

    private final KrasApiClient krasApiClient;
    private final KorepsApiClient korepsApiClient;
    private final ConnRequestLogService connLog;
    private final HttpServletRequest request;
    private final int apiTimeoutSeconds;
    private final Executor gmxExecutor;

    public KrasGmxController(KrasApiClient krasApiClient, KorepsApiClient korepsApiClient,
                              ConnRequestLogService connLog, HttpServletRequest request,
                              @Value("${kras.api-timeout-seconds:30}") int apiTimeoutSeconds) {
        this.krasApiClient = krasApiClient;
        this.korepsApiClient = korepsApiClient;
        this.connLog = connLog;
        this.request = request;
        this.apiTimeoutSeconds = apiTimeoutSeconds;

        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(5);
        exec.setMaxPoolSize(20);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("gmx-async-");
        exec.initialize();
        this.gmxExecutor = exec;
    }

    @Override
    public void destroy() {
        if (gmxExecutor instanceof ThreadPoolTaskExecutor exec) {
            exec.shutdown();
        }
    }

    // ── 1:1 단건 매핑 ────────────────────────────────────────────────────

    @GetMapping(value = "/GetLandInfo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getLandInfo(@RequestParam String pnu) {
        return gmxSingle("GetLandInfo", "land_info", pnu, null);
    }

    @GetMapping(value = "/GetJigaInfo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getJigaInfo(@RequestParam String pnu) {
        return gmxSingle("GetJigaInfo", "land_jiga", pnu, null);
    }

    @GetMapping(value = "/GetShareInfo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getShareInfo(@RequestParam String pnu) {
        return gmxSingle("GetShareInfo", "shr_ymb", pnu, null);
    }

    @GetMapping(value = "/GetLandHistInfo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getLandHistInfo(@RequestParam String pnu) {
        return gmxSingle("GetLandHistInfo", "land_mov_hist", pnu, null);
    }

    @GetMapping(value = "/GetOwnerHistInfo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getOwnerHistInfo(@RequestParam String pnu) {
        return gmxSingle("GetOwnerHistInfo", "own_rgt_hist", pnu, null);
    }

    @GetMapping(value = "/GetUseZoneList", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getUseZoneList(@RequestParam String pnu) {
        return gmxSingle("GetUseZoneList", "land_use_plan_attr", pnu, null);
    }

    @GetMapping(value = "/LandUsePlanAttr", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> landUsePlanAttr(@RequestParam String pnu) {
        return gmxSingle("LandUsePlanAttr", "land_use_plan_attr", pnu, null);
    }

    @GetMapping(value = "/GetLandBldgChk", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getLandBldgChk(@RequestParam String pnu) {
        return gmxSingle("GetLandBldgChk", "land_bldg_check", pnu, null);
    }

    @GetMapping(value = "/GetBldgList", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBldgList(@RequestParam String pnu) {
        return gmxSingle("GetBldgList", "bldg_dong_info", pnu, null);
    }

    @GetMapping(value = "/GetHouseInfo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getHouseInfo(
            @RequestParam String pnu,
            @RequestParam(required = false) String bno) {
        // 기존 GetHouseInfo는 HouseInfoService.getData(pnu) — bno를 게이트웨이에 보내지 않음
        return gmxSingle("GetHouseInfo", "house_info", pnu, null);
    }

    @GetMapping(value = "/GetJeonyubldg", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getJeonyubldg(
            @RequestParam String pnu,
            @RequestParam(required = false) String bno) {
        return gmxSingle("GetJeonyubldg", "bldg_ho_info", pnu, bnoExtra(bno));
    }

    @GetMapping(value = "/GetDjyexpos", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getDjyexpos(
            @RequestParam String pnu,
            @RequestParam(required = false) String bno) {
        return gmxSingle("GetDjyexpos", "cbldg_dfhs_info", pnu, bnoExtra(bno));
    }

    // ── 다중 조합 (parallel) ──────────────────────────────────────────────

    @GetMapping(value = "/GetTojiDaejangPrint", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getTojiDaejangPrint(@RequestParam String pnu) {
        return gmxMulti("GetTojiDaejangPrint", pnu,
                entry("land_info", null),
                entry("land_jiga", null),
                entry("land_mov_hist", null),
                entry("own_rgt_hist", null),
                entry("shr_ymb", null));
    }

    @GetMapping(value = "/GetTojiDaejangPrint2", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getTojiDaejangPrint2(@RequestParam String pnu) {
        return gmxMulti("GetTojiDaejangPrint2", pnu,
                entry("land_info", null),
                entry("land_jiga", null),
                entry("land_mov_hist", null),
                entry("own_rgt_hist", null),
                entry("shr_ymb", null));
    }

    @GetMapping(value = "/GetLandUsePlanInfo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getLandUsePlanInfo(
            @RequestParam String pnu,
            @RequestParam(required = false) String map_width,
            @RequestParam(required = false) String map_height,
            @RequestParam(required = false) String legend_width,
            @RequestParam(required = false) String legend_height,
            @RequestParam(required = false) String scale) {
        Map<String, String> mapExtra = new LinkedHashMap<>();
        if (map_width    != null) mapExtra.put("map_width",    map_width);
        if (map_height   != null) mapExtra.put("map_height",   map_height);
        if (legend_width != null) mapExtra.put("legend_width", legend_width);
        if (legend_height!= null) mapExtra.put("legend_height",legend_height);
        if (scale        != null) mapExtra.put("scale",        scale);
        return gmxMulti("GetLandUsePlanInfo", pnu,
                entry("land_use_plan_info", mapExtra.isEmpty() ? null : mapExtra),
                entry("land_jiga", null));
    }

    @GetMapping(value = "/GetBldgInfo", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBldgInfo(
            @RequestParam String pnu,
            @RequestParam(required = false) String bno) {
        Map<String, String> bno_ = bnoExtra(bno);
        return gmxMulti("GetBldgInfo", pnu,
                entry("bldg_dong_info", null),
                entry("bldg_hds_info", bno_),
                entry("bldg_ledg_gen_hds_info", null),
                entry("cbldg_hds_info", bno_));
    }

    @GetMapping(value = "/GetDjyrecaptitle", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getDjyrecaptitle(
            @RequestParam String pnu,
            @RequestParam(required = false) String bno) {
        Map<String, String> bno_ = bnoExtra(bno);
        return gmxMulti("GetDjyrecaptitle", pnu,
                entry("bldg_dong_info", null),
                entry("bldg_hds_info", bno_),
                entry("bldg_ledg_gen_hds_info", null),
                entry("cbldg_hds_info", bno_));
    }

    @GetMapping(value = "/GetDjytitle", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getDjytitle(
            @RequestParam String pnu,
            @RequestParam(required = false) String bno) {
        Map<String, String> bno_ = bnoExtra(bno);
        return gmxMulti("GetDjytitle", pnu,
                entry("bldg_hds_info", bno_),
                entry("cbldg_hds_info", bno_));
    }

    // ── 내부 유틸리티 ───────────────────────────────────────────────────

    private ResponseEntity<String> gmxSingle(String svc, String path, String pnu,
                                              Map<String, String> extra) {
        long t0 = System.currentTimeMillis();
        String ip = ConnRequestLogService.clientIp(request);
        String bno = extra != null ? extra.get("bldg_gbn_no") : null;
        if (!isValidPnu(pnu)) {
            connLog.record("GMX", svc, null, pnu, bno, ip,
                    ConnRequestLogService.ST_BAD_REQ, null, "PNU 형식 오류",
                    System.currentTimeMillis() - t0);
            return badRequest(svc, "PNU 형식 오류: 19자리 숫자여야 합니다");
        }
        try {
            byte[] xml = callGateway(path, pnu, extra);
            Map<String, byte[]> result = new LinkedHashMap<>();
            result.put(path, xml);
            String gmxXml = buildGmxXml(svc, result);
            String gwCode = ConnRequestLogService.gwCode(xml);
            String status = (gwCode == null || "0000".equals(gwCode))
                    ? ConnRequestLogService.ST_SUCCESS : ConnRequestLogService.ST_GW_ERROR;
            connLog.record("GMX", svc, null, pnu, bno, ip,
                    status, gwCode, null, System.currentTimeMillis() - t0);
            return ok(gmxXml);
        } catch (Exception e) {
            log.error("[GmxAPI] {} 실패: {}", svc, e.getMessage());
            connLog.record("GMX", svc, null, pnu, bno, ip,
                    ConnRequestLogService.ST_FAILED, null, e.getMessage(),
                    System.currentTimeMillis() - t0);
            return err(svc, e.getMessage());
        }
    }

    @SafeVarargs
    private ResponseEntity<String> gmxMulti(String svc, String pnu,
            Map.Entry<String, Map<String, String>>... pathEntries) {
        long t0 = System.currentTimeMillis();
        String ip = ConnRequestLogService.clientIp(request);
        String bno = null;
        for (var pe : pathEntries) {
            if (pe.getValue().containsKey("bldg_gbn_no")) { bno = pe.getValue().get("bldg_gbn_no"); break; }
        }
        if (!isValidPnu(pnu)) {
            connLog.record("GMX", svc, null, pnu, bno, ip,
                    ConnRequestLogService.ST_BAD_REQ, null, "PNU 형식 오류",
                    System.currentTimeMillis() - t0);
            return badRequest(svc, "PNU 형식 오류: 19자리 숫자여야 합니다");
        }

        Map<String, CompletableFuture<byte[]>> futures = new LinkedHashMap<>();
        for (var pe : pathEntries) {
            String p = pe.getKey();
            Map<String, String> ex = pe.getValue();
            futures.put(p, CompletableFuture.supplyAsync(() -> {
                try {
                    return callGateway(p, pnu, ex);
                } catch (Exception e) {
                    log.warn("[GmxAPI] {} 비동기 실패: {}", p, e.getMessage());
                    return errorBytes(p, e.getMessage());
                }
            }, gmxExecutor));
        }
        try {
            Map<String, byte[]> results = new LinkedHashMap<>();
            for (var fe : futures.entrySet()) {
                results.put(fe.getKey(), fe.getValue().get(apiTimeoutSeconds, TimeUnit.SECONDS));
            }
            String gmxXml = buildGmxXml(svc, results);
            connLog.record("GMX", svc, null, pnu, bno, ip,
                    ConnRequestLogService.ST_SUCCESS, null, null,
                    System.currentTimeMillis() - t0);
            return ok(gmxXml);
        } catch (Exception e) {
            log.error("[GmxAPI] {} 실패: {}", svc, e.getMessage());
            connLog.record("GMX", svc, null, pnu, bno, ip,
                    ConnRequestLogService.ST_FAILED, null, e.getMessage(),
                    System.currentTimeMillis() - t0);
            return err(svc, e.getMessage());
        }
    }

    private byte[] callGateway(String path, String pnu, Map<String, String> extra) throws Exception {
        String krasSvc = GatewayPaths.KRAS.get(path);
        if (krasSvc != null) return krasApiClient.query(krasSvc, pnu, extra);
        String korepsSvc = GatewayPaths.KOREPS.get(path);
        if (korepsSvc != null) return korepsApiClient.query(korepsSvc, pnu, extra);
        throw new IllegalArgumentException("지원하지 않는 경로: " + path);
    }

    private String buildGmxXml(String svc, Map<String, byte[]> results) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<GMX svc=\"").append(svc).append("\">");
        for (var e : results.entrySet()) {
            String tag = e.getKey().toUpperCase();
            sb.append("<").append(tag).append(">")
              .append(extractBodyInner(e.getValue()))
              .append("</").append(tag).append(">");
        }
        sb.append("</GMX>");
        return sb.toString();
    }

    static String extractBodyInner(byte[] rawXml) {
        if (rawXml == null || rawXml.length == 0) return "";
        try {
            Document doc = XmlUtil.parse(rawXml);
            NodeList bodies = doc.getElementsByTagName("BODY");
            if (bodies.getLength() == 0) {
                return new String(rawXml, StandardCharsets.UTF_8);
            }
            Node body = bodies.item(0);
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            NodeList children = body.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                t.transform(new DOMSource(children.item(i)), new StreamResult(writer));
            }
            return writer.toString();
        } catch (Exception e) {
            return new String(rawXml, StandardCharsets.UTF_8);
        }
    }

    private static boolean isValidPnu(String pnu) {
        return pnu == null || pnu.isBlank() || pnu.matches("\\d{19}");
    }

    private static Map<String, String> bnoExtra(String bno) {
        // 게이트웨이 파라미터명은 bldg_gbn_no (기존 KrasConn.getBldgData와 동일)
        return (bno != null && !bno.isBlank()) ? Map.of("bldg_gbn_no", bno) : null;
    }

    private static Map.Entry<String, Map<String, String>> entry(String path, Map<String, String> extra) {
        return Map.entry(path, extra != null ? extra : Map.of());
    }

    private static byte[] errorBytes(String path, String message) {
        String safe = message == null ? "unknown" : message.replace("<", "&lt;").replace(">", "&gt;");
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<error><path>" + path + "</path><message>" + safe + "</message></error>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body(body);
    }

    private ResponseEntity<String> err(String svc, String message) {
        String safe = message == null ? "unknown" : message.replace("<", "&lt;").replace(">", "&gt;");
        return ResponseEntity.internalServerError()
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><error><svc>" + svc
                        + "</svc><message>" + safe + "</message></error>");
    }

    private ResponseEntity<String> badRequest(String svc, String message) {
        String safe = message == null ? "unknown" : message.replace("<", "&lt;").replace(">", "&gt;");
        return ResponseEntity.badRequest()
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><error><svc>" + svc
                        + "</svc><message>" + safe + "</message></error>");
    }
}
