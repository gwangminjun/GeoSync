package geomex.sync.synchronization;

import geomex.sync.settings.RuntimeSettingsService;
import geomex.sync.database.TargetDbService;

import geomex.sync.database.TargetDbService.ActiveTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SyncExecutionLogService {

    private static final Logger log = LoggerFactory.getLogger(SyncExecutionLogService.class);

    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;

    public SyncExecutionLogService(TargetDbService targetDbService, RuntimeSettingsService settings) {
        this.targetDbService = targetDbService;
        this.settings = settings;
    }

    /** 첫 번째 활성 타겟 JdbcTemplate — 없으면 null */
    private JdbcTemplate jdbc() {
        List<ActiveTarget> targets = targetDbService.getActiveTargets();
        return targets.isEmpty() ? null : targets.get(0).jdbc();
    }

    private String logTable() {
        return "\"" + settings.odsSchema() + "\".sync_execution_log";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) {
            log.warn("[SyncLog] 활성 타겟 DB 없음 — 실행 로그 테이블 생성 건너뜀");
            return;
        }
        String schema = settings.odsSchema();
        try {
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS "%s".sync_execution_log (
                    id          BIGSERIAL    PRIMARY KEY,
                    type        VARCHAR(30)  NOT NULL,
                    triggered   VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULE',
                    status      VARCHAR(10)  NOT NULL DEFAULT 'RUNNING',
                    started_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    ended_at    TIMESTAMPTZ,
                    duration_s  INTEGER,
                    rows_ok     INTEGER      NOT NULL DEFAULT 0,
                    rows_err    INTEGER      NOT NULL DEFAULT 0,
                    error_msg   TEXT,
                    org_code    VARCHAR(20),
                    schedule    VARCHAR(100)
                )""".formatted(schema));
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_sel_started_at
                ON "%s".sync_execution_log (started_at DESC)""".formatted(schema));
            log.info("[SyncLog] 실행 로그 테이블 준비 완료 (스키마: {})", schema);
        } catch (Exception e) {
            log.error("[SyncLog] 테이블 초기화 실패: {}", e.getMessage());
        }
    }

    public Long start(String type, String triggeredBy, String schedule) {
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) return null;
        try {
            return jdbc.queryForObject(
                    "INSERT INTO " + logTable() +
                    " (type, triggered, status, started_at, org_code, schedule)" +
                    " VALUES (?, ?, 'RUNNING', NOW(), ?, ?) RETURNING id",
                    Long.class, type, triggeredBy, settings.orgCode(), schedule);
        } catch (Exception e) {
            log.warn("[SyncLog] start 기록 실패: {}", e.getMessage());
            return null;
        }
    }

    public void finish(Long id, int rowsOk, int rowsErr, boolean failed, String errorMsg) {
        if (id == null) return;
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) return;
        try {
            jdbc.update(
                    "UPDATE " + logTable() +
                    " SET status=?, ended_at=NOW()," +
                    "     duration_s=EXTRACT(EPOCH FROM (NOW() - started_at))::INTEGER," +
                    "     rows_ok=?, rows_err=?, error_msg=?" +
                    " WHERE id=?",
                    failed ? "FAILED" : "SUCCESS", rowsOk, rowsErr, errorMsg, id);
        } catch (Exception e) {
            log.warn("[SyncLog] finish 기록 실패: {}", e.getMessage());
        }
    }

    public boolean tableExists() {
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) return false;
        String schema = settings.odsSchema();
        try {
            Boolean r = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_tables WHERE schemaname=? AND tablename='sync_execution_log')",
                Boolean.class, schema);
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            log.warn("[SyncLog] tableExists 확인 실패: schema={}, 오류={}", schema, e.getMessage());
            return false;
        }
    }

    public Map<String, Object> getStats(String period) {
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) return Collections.emptyMap();
        String where = switch (period) {
            case "today" -> "started_at >= CURRENT_DATE";
            case "7d"    -> "started_at >= NOW() - INTERVAL '7 days'";
            default      -> "started_at >= NOW() - INTERVAL '30 days'";
        };
        try {
            Map<String, Object> summary = jdbc.queryForMap(
                "SELECT COUNT(*) FILTER (WHERE status<>'RUNNING') AS total," +
                "       COUNT(*) FILTER (WHERE status='SUCCESS')  AS success," +
                "       COUNT(*) FILTER (WHERE status='FAILED')   AS failed," +
                "       COUNT(*) FILTER (WHERE status='RUNNING')  AS running," +
                "       COALESCE(SUM(rows_ok),0)  AS rows_ok," +
                "       COALESCE(SUM(rows_err),0) AS rows_err," +
                "       ROUND(AVG(duration_s) FILTER (WHERE status='SUCCESS'))::INTEGER AS avg_dur_s" +
                " FROM " + logTable() + " WHERE " + where);

            List<Map<String, Object>> byType = jdbc.queryForList(
                "SELECT type," +
                "       COUNT(*) FILTER (WHERE status<>'RUNNING') AS total," +
                "       COUNT(*) FILTER (WHERE status='SUCCESS')  AS success," +
                "       COUNT(*) FILTER (WHERE status='FAILED')   AS failed," +
                "       ROUND(AVG(duration_s) FILTER (WHERE status='SUCCESS'))::INTEGER AS avg_dur_s" +
                " FROM " + logTable() + " WHERE " + where +
                " GROUP BY type ORDER BY total DESC");

            List<Map<String, Object>> byTrigger = jdbc.queryForList(
                "SELECT triggered, COUNT(*) AS cnt" +
                " FROM " + logTable() + " WHERE " + where +
                " GROUP BY triggered ORDER BY triggered");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("summary", summary);
            result.put("byType", byType);
            result.put("byTrigger", byTrigger);
            return result;
        } catch (Exception e) {
            log.warn("[SyncLog] getStats 조회 실패: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        JdbcTemplate jdbc = jdbc();
        if (jdbc == null) return Collections.emptyList();
        try {
            return jdbc.queryForList(
                    "SELECT id, type, triggered, status, started_at, ended_at," +
                    "       duration_s, rows_ok, rows_err, error_msg, org_code, schedule" +
                    " FROM " + logTable() +
                    " ORDER BY started_at DESC LIMIT ?", limit);
        } catch (Exception e) {
            log.warn("[SyncLog] 이력 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
