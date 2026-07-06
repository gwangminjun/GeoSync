package geomex.sync.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 개발 환경 전용 estateGateway mock (mock.gateway.enabled=true 일 때만 활성화).
 *
 * 실 게이트웨이(110.20.1.12:8385 등)에 접속할 수 없는 개발 PC에서
 * API 테스트 화면과 /conn, /svc 엔드포인트를 검증하기 위한 대역이다.
 * 실제 게이트웨이와 동일하게 Form POST(conn_svc_id/conn_sys_id/pnu...)를 받고,
 * 기존 kras 웹앱(geomex.kras.ivo.*DataSet JAXB VO)에서 확인한
 * RESPONSE &gt; HEADER(CODE/MESSAGE) + BODY 구조로 응답한다.
 *
 * 응답 템플릿: classpath:mock-gateway/{conn_svc_id}.xml
 *  - {{PNU}} {{BNO}} {{ADM}} 및 PNU 분해값({{LOC}} {{GBN}} {{BOBN}} {{BUBN}}) 치환
 *  - KRAS000038(SHP 바이너리), KRAS000039/40(TXT)은 코드에서 직접 생성
 */
@RestController
@ConditionalOnProperty(name = "mock.gateway.enabled", havingValue = "true")
public class MockGatewayController {

    private static final Logger log = LoggerFactory.getLogger(MockGatewayController.class);

    private static final String DEFAULT_PNU = "4687025625111190010";

    public MockGatewayController() {
        log.warn("[MockGW] mock estateGateway 활성화 — 개발 환경 전용 (/mock/estateGateway)");
    }

    @RequestMapping(value = "/mock/estateGateway", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<byte[]> estateGateway(@RequestParam Map<String, String> params) throws IOException {
        String svc = params.getOrDefault("conn_svc_id", "").trim();
        log.info("[MockGW] conn_svc_id={} pnu={} bldg_gbn_no={}", svc, pnuOf(params), bnoOf(params));

        if (svc.isEmpty()) {
            return xml(errorXml("9001", "conn_svc_id 파라미터 누락"));
        }
        // KRAS000038: SHP/DBF/SHX 바이너리 — '<'로 시작하지 않는 더미 바이트
        if ("KRAS000038".equals(svc)) {
            byte[] bin = new byte[256];
            bin[0] = 0x00; bin[1] = 0x00; bin[2] = 0x27; bin[3] = 0x0A; // SHP magic 9994
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(bin);
        }
        // KRAS000039/40: 공시지가/토지대장 TXT
        if ("KRAS000039".equals(svc) || "KRAS000040".equals(svc)) {
            String pnu = pnuOf(params);
            String txt = pnu + "|20260101|128000\n" + pnu.substring(0, 18) + "1|20260101|97500\n";
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(txt.getBytes(StandardCharsets.UTF_8));
        }

        ClassPathResource res = new ClassPathResource("mock-gateway/" + svc + ".xml");
        if (!res.exists()) {
            return xml(errorXml("9002", "mock 미지원 서비스: " + svc));
        }
        String template;
        try (var in = res.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return xml(substitute(template, params).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 요청에서 PNU 확보. 기존 싱크 방식(분해 파라미터: adm_sect_cd + land_loc_cd +
     * ledg_gbn + bobn + bubn)과 pnu 통째 전달 방식을 모두 지원한다.
     */
    private static String pnuOf(Map<String, String> params) {
        String pnu = params.getOrDefault("pnu", "");
        if (pnu.matches("\\d{19}")) return pnu;
        String rebuilt = params.getOrDefault("adm_sect_cd", "")
                + params.getOrDefault("land_loc_cd", "")
                + params.getOrDefault("ledg_gbn", "")
                + params.getOrDefault("bobn", "")
                + params.getOrDefault("bubn", "");
        return rebuilt.matches("\\d{19}") ? rebuilt : DEFAULT_PNU;
    }

    private static String substitute(String template, Map<String, String> params) {
        String pnu = pnuOf(params);
        String adm = params.getOrDefault("adm_sec_cd",
                params.getOrDefault("adm_sect_cd", pnu.substring(0, 5)));
        String bno = bnoOf(params);
        return template
                .replace("{{PNU}}",  pnu)
                .replace("{{ADM}}",  adm)
                .replace("{{BNO}}",  bno.isBlank() ? "1" : bno)
                .replace("{{LOC}}",  pnu.substring(5, 10))
                .replace("{{GBN}}",  pnu.substring(10, 11))
                .replace("{{BOBN}}", pnu.substring(11, 15))
                .replace("{{BUBN}}", pnu.substring(15, 19));
    }

    /** 건물번호: 실제 게이트웨이 파라미터명 bldg_gbn_no 우선, 구 bno 폴백 */
    private static String bnoOf(Map<String, String> params) {
        String v = params.getOrDefault("bldg_gbn_no", "");
        return v.isBlank() ? params.getOrDefault("bno", "") : v;
    }

    private static String errorXml(String code, String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><RESPONSE><HEADER><CODE>" + code
                + "</CODE><MESSAGE>" + message + "</MESSAGE></HEADER><BODY/></RESPONSE>";
    }

    private static ResponseEntity<byte[]> xml(String body) {
        return xml(body.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<byte[]> xml(byte[] body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body(body);
    }
}
