package geomex.sync.web;

import geomex.sync.worker.KorepsApiClient;
import geomex.sync.worker.KrasApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * kras 웹앱(/conn/*) 대체 컨트롤러.
 *
 * YG-Space가 기존에 http://110.20.1.199:9080/kras/conn/{path}?pnu=... 로 호출하던
 * 엔드포인트를 geomex-sync가 대신 제공한다.
 * estateGateway XML 응답을 그대로 pass-through 반환한다.
 */
@RestController
@RequestMapping("/conn")
public class KrasConnController {

    private static final Logger log = LoggerFactory.getLogger(KrasConnController.class);

    // ── KRAS 경로 → conn_svc_id 매핑 ────────────────────────────────
    private static final Map<String, String> KRAS_CODES = Map.ofEntries(
        Map.entry("land_info",              "KRAS000002"),
        Map.entry("shr_ymb",               "KRAS000003"),
        Map.entry("land_mov_hist",          "KRAS000006"),
        Map.entry("own_rgt_hist",           "KRAS000007"),
        Map.entry("bldg_hds_info",          "KRAS000014"),
        Map.entry("cbldg_hds_info",         "KRAS000015"),
        Map.entry("cbldg_dfhs_info",        "KRAS000016"),
        Map.entry("bldg_ledg_gen_hds_info", "KRAS000017"),
        Map.entry("land_use_plan_attr",     "KRAS000025"),
        Map.entry("land_use_plan_info",     "KRAS000026"),
        Map.entry("use_zone",               "KRAS000027"),
        Map.entry("land_bldg_check",        "KRAS000101"),
        Map.entry("bldg_dong_info",         "KRAS000102"),
        Map.entry("bldg_ho_info",           "KRAS000103")
    );

    // ── KOREPS 경로 → conn_svc_id 매핑 ──────────────────────────────
    private static final Map<String, String> KOREPS_CODES = Map.of(
        "land_jiga",    "KOREPS00011",
        "house_info",   "KOREPS00033",
        "fin_dec_jiga", "KOREPS00034",
        "read_dec_jiga","KOREPS00035",
        "land_attr",    "KOREPS00047"
    );

    // bno 파라미터가 의미 있는 건축물 관련 경로
    private static final Set<String> BNO_PATHS = Set.of(
        "bldg_dong_info", "bldg_hds_info", "bldg_ho_info",
        "bldg_ledg_gen_hds_info", "cbldg_hds_info", "cbldg_dfhs_info", "house_info"
    );

    private final KrasApiClient krasApiClient;
    private final KorepsApiClient korepsApiClient;

    public KrasConnController(KrasApiClient krasApiClient, KorepsApiClient korepsApiClient) {
        this.krasApiClient = krasApiClient;
        this.korepsApiClient = korepsApiClient;
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

        log.info("[ConnAPI] {} pnu={} bno={}", path, pnu, bno);

        try {
            Map<String, String> extra = buildExtra(path, bno, map_width, map_height,
                    legend_width, legend_height, scale);
            byte[] xml = callGateway(path, pnu, extra);
            String body = new String(xml, StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                    .body(body);
        } catch (Exception e) {
            log.error("[ConnAPI] {} 조회 실패: {}", path, e.getMessage());
            return ResponseEntity.internalServerError()
                    .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                    .body(errorXml(path, e.getMessage()));
        }
    }

    private byte[] callGateway(String path, String pnu, Map<String, String> extra) throws Exception {
        String krasSvc = KRAS_CODES.get(path);
        if (krasSvc != null) {
            return krasApiClient.query(krasSvc, pnu, extra);
        }
        String korepsSvc = KOREPS_CODES.get(path);
        if (korepsSvc != null) {
            return korepsApiClient.query(korepsSvc, pnu, extra);
        }
        throw new IllegalArgumentException("지원하지 않는 경로: " + path);
    }

    private Map<String, String> buildExtra(String path, String bno,
            String mapWidth, String mapHeight,
            String legendWidth, String legendHeight, String scale) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (BNO_PATHS.contains(path) && bno != null && !bno.isBlank()) {
            extra.put("bno", bno);
        }
        if ("land_use_plan_info".equals(path)) {
            if (mapWidth    != null) extra.put("map_width",    mapWidth);
            if (mapHeight   != null) extra.put("map_height",   mapHeight);
            if (legendWidth != null) extra.put("legend_width", legendWidth);
            if (legendHeight!= null) extra.put("legend_height",legendHeight);
            if (scale       != null) extra.put("scale",        scale);
        }
        return extra;
    }

    private static String errorXml(String path, String message) {
        String safe = message == null ? "unknown" : message.replace("<", "&lt;").replace(">", "&gt;");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<error><path>" + path + "</path><message>" + safe + "</message></error>";
    }
}
