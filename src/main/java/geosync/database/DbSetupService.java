package geosync.database;

import geosync.settings.RuntimeSettingsService;

import geosync.database.TargetDbService.ActiveTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class DbSetupService {

    private static final Logger log = LoggerFactory.getLogger(DbSetupService.class);

    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;

    public DbSetupService(TargetDbService targetDbService, RuntimeSettingsService settings) {
        this.targetDbService = targetDbService;
        this.settings = settings;
    }

    public record TableStatus(String name, String schema, boolean exists) {}

    public record TargetDbStatus(int idx, String name, String host, String dbname,
                                  List<TableStatus> tables, String error) {}

    public record CreateResult(boolean success, String message) {}

    private List<String[]> requiredTables() {
        String ods = settings.odsSchema();
        return List.of(
            new String[]{"lp_pa_cbnd",          ods},
            new String[]{"lt_c_uzone",           ods},
            new String[]{"mt_usezone_cd",        ods},
            new String[]{"sync_execution_log",   ods}
        );
    }

    public List<TargetDbStatus> checkAll() {
        List<ActiveTarget> targets = targetDbService.getActiveTargets();
        List<TargetDbStatus> result = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            result.add(checkTarget(i, targets.get(i)));
        }
        return result;
    }

    private TargetDbStatus checkTarget(int idx, ActiveTarget t) {
        try {
            List<TableStatus> statuses = new ArrayList<>();
            for (String[] spec : requiredTables()) {
                boolean exists = tableExists(t.jdbc(), spec[1], spec[0]);
                statuses.add(new TableStatus(spec[0], spec[1], exists));
            }
            String[] parts = parseUrl(t.url());
            return new TargetDbStatus(idx, t.name(), parts[0], parts[1], statuses, null);
        } catch (Exception e) {
            log.warn("[DbSetup] 연결 실패 (idx={}): {}", idx, e.getMessage());
            String[] parts = parseUrl(t.url());
            return new TargetDbStatus(idx, t.name(), parts[0], parts[1], List.of(), e.getMessage());
        }
    }

    private boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
        Boolean r = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_tables WHERE schemaname=? AND tablename=?)",
            Boolean.class, schema, table);
        return Boolean.TRUE.equals(r);
    }

    public CreateResult createTable(int targetIdx, String tableName) {
        List<ActiveTarget> targets = targetDbService.getActiveTargets();
        if (targets.isEmpty()) return new CreateResult(false, "DB가 준비되지 않았습니다");

        ActiveTarget t = targets.get(0);
        String odsSchema = settings.odsSchema();
        try {
            switch (tableName) {
                case "lp_pa_cbnd"          -> createLpPaCbnd(t.jdbc(), odsSchema);
                case "lt_c_uzone"          -> createLtCUzone(t.jdbc(), odsSchema);
                case "mt_usezone_cd"       -> createMtUsezonecd(t.jdbc(), odsSchema);
                case "sync_execution_log"  -> createSyncExecutionLog(t.jdbc(), odsSchema);
                default -> { return new CreateResult(false, "알 수 없는 테이블: " + tableName); }
            }
            return new CreateResult(true, "[" + t.name() + "] " + tableName + " 생성 완료");
        } catch (Exception e) {
            log.warn("[DbSetup] 테이블 생성 실패 (단일 DB, 요청 idx={}): {}", tableName, targetIdx, e.getMessage());
            return new CreateResult(false, e.getMessage());
        }
    }

    // ── DDL 실행 ──────────────────────────────────────────────────────

    private void createLpPaCbnd(JdbcTemplate jdbc, String schema) {
        ensurePostgis(jdbc);
        ensureSchema(jdbc, schema);
        jdbc.execute(String.format("""
            CREATE TABLE IF NOT EXISTS "%s".lp_pa_cbnd (
                uid     int4                            NULL,
                geom    geometry(MultiPolygon, 5186)    NULL,
                jibun   varchar(100)                    NULL,
                bchk    varchar(1)                      NULL,
                pnu     varchar(19)                     NULL
            )""", schema));
    }

    private void createLtCUzone(JdbcTemplate jdbc, String schema) {
        ensurePostgis(jdbc);
        ensureSchema(jdbc, schema);
        jdbc.execute(String.format("""
            CREATE TABLE IF NOT EXISTS "%s".lt_c_uzone (
                mnum        varchar(33)                     NULL,
                remark      varchar(100)                    NULL,
                alias       varchar(100)                    NULL,
                layer_code  varchar(5)                      NULL,
                theme_code  varchar(6)                      NULL,
                theme_name  varchar(100)                    NULL,
                org_cd      varchar(10)                     NULL,
                uid         int4                            NULL,
                geom        geometry(MultiPolygon, 5186)    NULL
            )""", schema));
    }

    private void createMtUsezonecd(JdbcTemplate jdbc, String schema) {
        ensureSchema(jdbc, schema);
        jdbc.execute(String.format("""
            CREATE TABLE IF NOT EXISTS "%s".mt_usezone_cd (
                use_zone_zone_cd        varchar(255),
                law_cls_cd              varchar(255),
                rem                     varchar(255),
                del_ymd                 varchar(255),
                cre_ymd                 varchar(255),
                fac_cls                 varchar(255),
                fac_type                varchar(255),
                cont_tmap_layer_no      varchar(255),
                use_zone_zone_cd_nm     varchar(255),
                use_zone_zone_cls_gbn   varchar(255),
                cflt_expr_yn            varchar(255),
                app_law_cd              varchar(255),
                rpt_cls                 varchar(255),
                edt_tmap_layer_no       varchar(255)
            )""", schema));

        Long count = jdbc.queryForObject(
            String.format("SELECT COUNT(*) FROM \"%s\".mt_usezone_cd", schema), Long.class);
        if (count == null || count == 0) {
            loadMtUsezoneData(jdbc, schema);
        }
    }

    private void loadMtUsezoneData(JdbcTemplate jdbc, String schema) {
        try {
            String sql = Files.readString(Path.of("conf/sql/mt_usezone_cd.sql"), StandardCharsets.UTF_8);
            String[] inserts = Arrays.stream(sql.split("\n"))
                .filter(l -> l.trim().toLowerCase().startsWith("insert into mt_usezone_cd"))
                .map(l -> l.replaceFirst("(?i)INSERT INTO\\s+mt_usezone_cd",
                                         String.format("INSERT INTO \"%s\".mt_usezone_cd", schema)))
                .map(l -> l.endsWith(";") ? l.substring(0, l.length() - 1) : l)
                .toArray(String[]::new);
            if (inserts.length > 0) {
                jdbc.batchUpdate(inserts);
                log.info("[DbSetup] mt_usezone_cd 기준 데이터 {}건 삽입", inserts.length);
            }
        } catch (Exception e) {
            log.warn("[DbSetup] mt_usezone_cd 기준 데이터 삽입 실패: {}", e.getMessage());
        }
    }

    private void createSyncExecutionLog(JdbcTemplate jdbc, String schema) {
        ensureSchema(jdbc, schema);
        jdbc.execute(String.format("""
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
            )""", schema));
        jdbc.execute(String.format(
            "CREATE INDEX IF NOT EXISTS idx_sel_started_at ON \"%s\".sync_execution_log (started_at DESC)",
            schema));
    }

    private void ensurePostgis(JdbcTemplate jdbc) {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        } catch (Exception e) {
            log.warn("[DbSetup] PostGIS 확장 설치 실패 (이미 설치됐거나 권한 없음): {}", e.getMessage());
        }
    }

    private void ensureSchema(JdbcTemplate jdbc, String schema) {
        jdbc.execute(String.format("CREATE SCHEMA IF NOT EXISTS \"%s\"", schema));
    }

    private String[] parseUrl(String url) {
        if (url == null) return new String[]{"unknown", "unknown"};
        String clean = url.replaceFirst("^jdbc:postgresql://", "");
        int slashIdx = clean.indexOf('/');
        if (slashIdx > 0) return new String[]{clean.substring(0, slashIdx), clean.substring(slashIdx + 1)};
        return new String[]{clean, ""};
    }
}
