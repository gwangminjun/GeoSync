package geomex.sync.service;

import geomex.sync.service.TargetDbService.ActiveTarget;
import geomex.sync.util.XmlUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 외부 프로그램(YG-Space 등)의 kras conn API(/conn/*, /svc/*) 요청 로그 적재·통계.
 *
 * sync_execution_log와 동일하게 첫 번째 활성 타겟 DB의 ODS 스키마에 테이블을 만들고,
 * 요청 스레드를 막지 않도록 INSERT는 전용 단일 스레드에서 비동기로 수행한다.
 * DB가 없거나 INSERT가 실패해도 API 응답에는 영향을 주지 않는다.
 */
@Service
public class ConnRequestLogService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ConnRequestLogService.class);

    /** 요청 처리 상태 */
    public static final String ST_SUCCESS  = "SUCCESS";   // 정상 (게이트웨이 CODE 0000 또는 CODE 없음)
    public static final String ST_GW_ERROR = "GW_ERROR";  // HTTP 200이지만 게이트웨이 CODE != 0000
    public static final String ST_FAILED   = "FAILED";    // 예외 (게이트웨이 연결 실패 등)
    public static final String ST_BAD_REQ  = "BAD_REQ";   // 요청 파라미터 오류 (PNU 형식 등)

    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;
    private final ExecutorService writer;
    private final int retentionDays;
    private volatile boolean ready = false;

    public ConnRequestLogService(TargetDbService targetDbService, RuntimeSettingsService settings,
                                 @Value("${conn-log.retention-days:90}") int retentionDays) {
        this.targetDbService = targetDbService;
        this.settings = settings;
        this.retentionDays = retentionDays;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "conn-req-log");
            t.setDaemon(true);
            return t;
        });
    }

    private JdbcTemplate jdbc() {
        List<ActiveTarget> targets = targetDbService.getActiveTargets();
        return targets.isEmpty() ? null : targets.get(0).jdbc();
    }

    private String logTable() {
        return "\"" + settings.odsSchema() + "\".conn_request_log";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) {
            log.warn("[ConnLog] 활성 타겟 DB 없음 — conn 요청 로그 테이블 생성 건너뜀");
            return;
        }
        String schema = settings.odsSchema();
        try {
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS "%s".conn_request_log (
                    id           BIGSERIAL    PRIMARY KEY,
                    api_type     VARCHAR(10)  NOT NULL,
                    path         VARCHAR(60)  NOT NULL,
                    conn_svc_id  VARCHAR(20),
                    pnu          VARCHAR(19),
                    bno          VARCHAR(20),
                    client_ip    VARCHAR(45),
                    status       VARCHAR(10)  NOT NULL,
                    gw_code      VARCHAR(10),
                    error_msg    TEXT,
                    duration_ms  INTEGER,
                    requested_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
                )""".formatted(schema));
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_crl_requested_at
                ON "%s".conn_request_log (requested_at DESC)""".formatted(schema));
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_crl_path
                ON "%s".conn_request_log (path)""".formatted(schema));
            ready = true;
            log.info("[ConnLog] conn 요청 로그 테이블 준비 완료 (스키마: {})", schema);
        } catch (Exception e) {
            log.error("[ConnLog] 테이블 초기화 실패: {}", e.getMessage());
        }
    }

    /**
     * 요청 1건 기록 (비동기, 실패해도 무시).
     *
     * @param apiType  CONN(/conn/*) | GMX(/svc/*)
     * @param status   ST_SUCCESS / ST_GW_ERROR / ST_FAILED / ST_BAD_REQ
     * @param gwCode   게이트웨이 응답 HEADER > CODE (파싱 실패 시 null)
     */
    public void record(String apiType, String path, String connSvcId, String pnu, String bno,
                       String clientIp, String status, String gwCode, String errorMsg, long durationMs) {
        if (!ready) return;
        writer.submit(() -> {
            JdbcTemplate jdbc = jdbc();
            if (jdbc == null) return;
            try {
                jdbc.update(
                        "INSERT INTO " + logTable() +
                        " (api_type, path, conn_svc_id, pnu, bno, client_ip, status, gw_code, error_msg, duration_ms)" +
                        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        apiType, path, blankToNull(connSvcId), blankToNull(pnu), blankToNull(bno),
                        clientIp, status, gwCode, truncate(errorMsg, 500), (int) durationMs);
            } catch (Exception e) {
                log.warn("[ConnLog] 기록 실패: {}", e.getMessage());
            }
        });
    }

    /** 보존기간(conn-log.retention-days, 기본 90일) 지난 로그를 매일 새벽 정리 */
    @Scheduled(cron = "0 40 3 * * *")
    public void purgeOldLogs() {
        if (!ready || retentionDays <= 0) return;
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) return;
        try {
            int deleted = jdbc.update(
                    "DELETE FROM " + logTable() +
                    " WHERE requested_at < NOW() - make_interval(days => ?)", retentionDays);
            if (deleted > 0) {
                log.info("[ConnLog] {}일 경과 로그 {}건 정리", retentionDays, deleted);
            }
        } catch (Exception e) {
            log.warn("[ConnLog] 로그 정리 실패: {}", e.getMessage());
        }
    }

    public boolean tableExists() {
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) return false;
        try {
            Boolean r = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_tables WHERE schemaname=? AND tablename='conn_request_log')",
                Boolean.class, settings.odsSchema());
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    /** 기간별 통계: 요약 / 경로별 / 호출 IP별 / 일자별 추이 */
    public Map<String, Object> getStats(String period) {
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) return Collections.emptyMap();
        String where = switch (period) {
            case "today" -> "requested_at >= CURRENT_DATE";
            case "7d"    -> "requested_at >= NOW() - INTERVAL '7 days'";
            default      -> "requested_at >= NOW() - INTERVAL '30 days'";
        };
        try {
            Map<String, Object> summary = jdbc.queryForMap(
                "SELECT COUNT(*) AS total," +
                "       COUNT(*) FILTER (WHERE status='SUCCESS')  AS success," +
                "       COUNT(*) FILTER (WHERE status='GW_ERROR') AS gw_error," +
                "       COUNT(*) FILTER (WHERE status='FAILED')   AS failed," +
                "       COUNT(*) FILTER (WHERE status='BAD_REQ')  AS bad_req," +
                "       ROUND(AVG(duration_ms) FILTER (WHERE status IN ('SUCCESS','GW_ERROR')))::INTEGER AS avg_ms," +
                "       MAX(duration_ms) AS max_ms" +
                " FROM " + logTable() + " WHERE " + where);

            List<Map<String, Object>> byPath = jdbc.queryForList(
                "SELECT api_type, path, conn_svc_id," +
                "       COUNT(*) AS total," +
                "       COUNT(*) FILTER (WHERE status='SUCCESS')  AS success," +
                "       COUNT(*) FILTER (WHERE status='GW_ERROR') AS gw_error," +
                "       COUNT(*) FILTER (WHERE status='FAILED')   AS failed," +
                "       ROUND(AVG(duration_ms) FILTER (WHERE status IN ('SUCCESS','GW_ERROR')))::INTEGER AS avg_ms" +
                " FROM " + logTable() + " WHERE " + where +
                " GROUP BY api_type, path, conn_svc_id ORDER BY total DESC");

            List<Map<String, Object>> byClient = jdbc.queryForList(
                "SELECT client_ip," +
                "       COUNT(*) AS total," +
                "       COUNT(*) FILTER (WHERE status IN ('FAILED','GW_ERROR','BAD_REQ')) AS errors," +
                "       MAX(requested_at) AS last_at" +
                " FROM " + logTable() + " WHERE " + where +
                " GROUP BY client_ip ORDER BY total DESC LIMIT 10");

            List<Map<String, Object>> byDay = jdbc.queryForList(
                "SELECT TO_CHAR(requested_at AT TIME ZONE 'Asia/Seoul', 'MM-DD') AS day," +
                "       COUNT(*) AS total," +
                "       COUNT(*) FILTER (WHERE status IN ('FAILED','GW_ERROR','BAD_REQ')) AS errors" +
                " FROM " + logTable() + " WHERE " + where +
                " GROUP BY 1 ORDER BY 1");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("summary", summary);
            result.put("byPath", byPath);
            result.put("byClient", byClient);
            result.put("byDay", byDay);
            return result;
        } catch (Exception e) {
            log.warn("[ConnLog] getStats 조회 실패: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) return Collections.emptyList();
        try {
            return jdbc.queryForList(
                    "SELECT id, api_type, path, conn_svc_id, pnu, bno, client_ip," +
                    "       status, gw_code, error_msg, duration_ms," +
                    "       TO_CHAR(requested_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS requested_at" +
                    " FROM " + logTable() +
                    " ORDER BY requested_at DESC, id DESC LIMIT ?", limit);
        } catch (Exception e) {
            log.warn("[ConnLog] 이력 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 프록시 뒤에서도 실제 호출 IP를 얻도록 X-Forwarded-For 우선 */
    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 게이트웨이 응답 XML의 HEADER > CODE 추출 (파싱 불가 시 null) */
    public static String gwCode(byte[] xml) {
        try {
            Document doc = XmlUtil.parse(xml);
            NodeList nl = doc.getElementsByTagName("CODE");
            return nl.getLength() > 0 ? nl.item(0).getTextContent().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public void destroy() throws InterruptedException {
        writer.shutdown();
        writer.awaitTermination(3, TimeUnit.SECONDS);
    }
}
