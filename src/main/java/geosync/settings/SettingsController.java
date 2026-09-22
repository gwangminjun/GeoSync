package geosync.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import geosync.synchronization.DynamicScheduleManager;
import geosync.database.TargetDbService;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeSettingsService settings;
    private final DynamicScheduleManager scheduleManager;
    private final TargetDbService targetDbService;
    private final SettingsFileService settingsFileService;

    public SettingsController(RuntimeSettingsService settings, DynamicScheduleManager scheduleManager,
                              TargetDbService targetDbService, SettingsFileService settingsFileService) {
        this.settings = settings;
        this.scheduleManager = scheduleManager;
        this.targetDbService = targetDbService;
        this.settingsFileService = settingsFileService;
    }

    @Value("${spring.config.location:conf/application.yml}")
    private String configLocation;

    @Value("${kras.chk-pnu:1283025625111190010}")
    private String defaultChkPnu;

    // ── GET /settings ────────────────────────────────────────────────────────

    @GetMapping
    public String settingsPage(Model model) {
        Path configFile = resolveConfigPath();
        Map<String, String> values = new LinkedHashMap<>();
        List<Map<String, Object>> targets = new ArrayList<>();

        if (Files.exists(configFile)) {
            try {
                String content = Files.readString(configFile, StandardCharsets.UTF_8);
                Map<String, Object> root = readYamlRoot(content);
                // SnakeYAML 파싱 결과를 직접 사용 (백슬래시 이스케이프 문제 방지)
                Map<String, Object> kras = childMap(root, "kras");
                Map<String, Object> ods  = childMap(root, "ods");
                values.put("kras_url",         yamlValue(kras, "url",        ""));
                values.put("kras_conn_sys_id", yamlValue(kras, "conn-sys-id",""));
                values.put("kras_chk_pnu",     yamlValue(kras, "chk-pnu",   ""));
                values.put("kras_work_dir",    yamlValue(kras, "work-dir",   ""));
                values.put("kras_shp_charset", yamlValue(kras, "shp-charset","MS949"));
                values.put("kras_schedule",    yamlValue(kras, "schedule",   ""));
                Map<String, Object> fileDl = childMap(kras, "file-download");
                values.put("fd_schedule",    yamlValue(fileDl, "schedule",    ""));
                values.put("fd_output_dir",  yamlValue(fileDl, "output-dir",  ""));
                values.put("fd_cbnd_shp",    yamlValue(fileDl, "cbnd-shp",    "true"));
                values.put("fd_usezone_shp", yamlValue(fileDl, "usezone-shp", "true"));
                values.put("fd_jiga_txt",    yamlValue(fileDl, "jiga-txt",    "false"));
                values.put("fd_land_txt",    yamlValue(fileDl, "land-txt",    "false"));
                values.put("ods_schema",       yamlValue(ods,  "schema",     "ods"));
                Map<String, Object> sync = childMap(root, "sync");
                values.put("sync_org_code",    yamlValue(sync, "org-code",   settings.orgCode()));

                if (values.get("kras_chk_pnu").isEmpty()) {
                    values.put("kras_chk_pnu", defaultChkPnu);
                }

                targets = readTargets(content);
            } catch (IOException e) {
                log.warn("설정 파일 읽기 실패: {}", e.getMessage());
            }
        }
        if (targets.isEmpty()) {
            targets = targetDbService.getTargets().stream().map(t -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", t.getName());
                row.put("host", t.getHost());
                row.put("port", t.getPort());
                row.put("dbname", t.getDbname());
                row.put("username", t.getUsername());
                row.put("password", t.getPassword());
                row.put("enabled", true);
                return row;
            }).toList();
        }
        DatabaseSettings database = settings.database();
        values.putIfAbsent("db_display_name", database.displayName());
        values.putIfAbsent("db_url", database.url());
        values.putIfAbsent("db_user", database.username());
        values.putIfAbsent("db_password", database.password());
        values.putIfAbsent("ods_schema", "ods");
        values.putIfAbsent("kras_shp_charset", "MS949");
        values.putIfAbsent("sync_org_code", settings.orgCode());

        model.addAttribute("currentPage", "settings");
        model.addAttribute("configPath", configFile.toAbsolutePath().toString());
        model.addAttribute("values", values);
        model.addAttribute("targets", targets);
        return "settings";
    }

    // ── POST /settings/test-db ───────────────────────────────────────────────

    @PostMapping("/test-db")
    @ResponseBody
    public Map<String, Object> testDbConnection(
            @RequestParam String db_host,
            @RequestParam String db_port,
            @RequestParam String db_name,
            @RequestParam String db_user,
            @RequestParam(required = false, defaultValue = "") String db_password) {
        String url = "jdbc:postgresql://" + db_host.trim() + ":" + db_port.trim() + "/" + db_name.trim();
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection(url, db_user.trim(), db_password)) {
                String version = conn.getMetaData().getDatabaseProductVersion();
                return conn.isValid(5)
                    ? Map.of("success", true,  "message", "연결 성공 — PostgreSQL " + version)
                    : Map.of("success", false, "message", "연결은 됐으나 ping 실패");
            }
        } catch (ClassNotFoundException e) {
            return Map.of("success", false, "message", "PostgreSQL 드라이버를 찾을 수 없습니다");
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @PostMapping("/check-ods")
    @ResponseBody
    public Map<String, Object> checkOdsSchema(
            @RequestParam int target_index,
            @RequestParam(required = false, defaultValue = "ods") String ods_schema) {
        try {
            String schema = safe(ods_schema).isEmpty() ? "ods" : safe(ods_schema);
            TargetDbService.ActiveTarget target = targetDbService.getActiveTargets().get(0);
            String url = target.url();
            try (Connection conn = target.jdbc().getDataSource().getConnection()) {
                boolean schemaExists = schemaExists(conn, schema);
                List<Map<String, Object>> tables = schemaExists
                        ? inspectSchemaTables(conn, schema)
                        : List.of();
                return Map.of(
                        "success", true,
                        "target", target.name(),
                        "url", url,
                        "schema", schema,
                        "schemaExists", schemaExists,
                        "tables", tables
                );
            }
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    // ── POST /settings/test-kras ─────────────────────────────────────────────

    @PostMapping("/test-kras")
    @ResponseBody
    public Map<String, Object> testKrasConnection(
            @RequestParam String kras_url,
            @RequestParam String kras_conn_sys_id,
            @RequestParam(required = false, defaultValue = "") String kras_chk_pnu) {
        String url     = kras_url.trim();
        String connId  = kras_conn_sys_id.trim();
        String chkPnu  = kras_chk_pnu.isBlank() ? defaultChkPnu : kras_chk_pnu.trim();

        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("service",   "CHECK");
            req.put("connSysId", connId);
            req.put("orgCode",   settings.orgCode());
            req.put("chkPnu",    chkPnu);

            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(url);
                post.setEntity(new StringEntity(objectMapper.writeValueAsString(req),
                        ContentType.APPLICATION_JSON));

                return client.execute(post, response -> {
                    try (InputStream is = response.getEntity().getContent()) {
                        JsonNode res = objectMapper.readTree(is);
                        String code = res.path("resultCode").asText("");
                        String msg  = res.path("resultMsg").asText("");
                        boolean ok  = "00".equals(code);
                        return ok
                            ? Map.of("success", true,  "message", "연결 성공 (code=" + code + ")")
                            : Map.of("success", false, "message", "서버 응답 오류: " + code + " — " + msg);
                    }
                });
            }
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    // ── POST /settings ───────────────────────────────────────────────────────

    @PostMapping
    public String saveSettings(
            @RequestParam Map<String, String> params,
            RedirectAttributes ra) {

        Path configFile = resolveConfigPath();
        try {
            DatabaseSettings database = databaseFromParams(params);
            Map<String, Object> managed = readYamlRoot(buildYaml(params));
            settingsFileService.save(configFile, database, managed);
            settings.reload();
            targetDbService.evictStaleTargets();
            scheduleManager.reloadSchedules();
            log.info("설정 저장: {}", configFile.toAbsolutePath());
            ra.addFlashAttribute("success", "설정을 저장했습니다. 변경 사항은 재시작 없이 바로 적용됩니다.");
        } catch (IOException e) {
            log.error("설정 저장 실패: {}", e.getMessage());
            ra.addFlashAttribute("error", "설정 저장 실패: " + e.getMessage());
        }
        return "redirect:/settings";
    }

    private DatabaseSettings databaseFromParams(Map<String, String> params) {
        String url = safe(params.get("db_url"));
        String user = safe(params.get("db_user"));
        String password = params.getOrDefault("db_password", "");
        String display = safe(params.get("db_display_name"));
        if (url.isEmpty()) {
            String host = safe(params.get("tgt_host_0"));
            String port = safe(params.get("tgt_port_0")).isEmpty() ? "5432" : safe(params.get("tgt_port_0"));
            String dbname = safe(params.get("tgt_dbname_0"));
            url = "jdbc:postgresql://" + host + ":" + port + "/" + dbname;
            if (user.isEmpty()) user = safe(params.get("tgt_user_0"));
            if (password.isEmpty()) password = params.getOrDefault("tgt_pass_0", "");
            if (display.isEmpty()) display = safe(params.get("tgt_name_0"));
        }
        return new DatabaseSettings(display, url, user, password, DatabaseSettings.Source.CANONICAL);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private Path resolveConfigPath() {
        String loc = configLocation;
        if (loc.startsWith("file:")) loc = loc.substring(5);
        return Path.of(loc).normalize();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readTargets(String content) {
        try {
            Map<String, Object> root = readYamlRoot(content);
            Object t = root.get("targets");
            if (!(t instanceof List)) return Collections.emptyList();
            return (List<Map<String, Object>>) t;
        } catch (Exception e) {
            log.warn("targets 파싱 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlRoot(String content) {
        Object loaded = new Yaml().load(content);
        if (!(loaded instanceof Map)) return Collections.emptyMap();
        return (Map<String, Object>) loaded;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object child = parent.get(key);
        if (!(child instanceof Map)) return Collections.emptyMap();
        return (Map<String, Object>) child;
    }

    private String yamlValue(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : fallback;
    }

    private boolean schemaExists(Connection conn, String schema) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private List<Map<String, Object>> inspectSchemaTables(Connection conn, String schema) throws Exception {
        List<String> tableNames = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name LIMIT 100")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tableNames.add(rs.getString(1));
                }
            }
        }

        List<Map<String, Object>> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            boolean hasOrgCd = columnExists(conn, schema, tableName, "org_cd");
            long totalRows = countRows(conn, schema, tableName, false);
            Long orgRows = hasOrgCd ? countRows(conn, schema, tableName, true) : null;
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("name", tableName);
            table.put("totalRows", totalRows);
            table.put("orgRows", orgRows);
            table.put("hasOrgCd", hasOrgCd);
            tables.add(table);
        }
        return tables;
    }

    private boolean columnExists(Connection conn, String schema, String table, String column) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? AND column_name = ?)")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private long countRows(Connection conn, String schema, String table, boolean onlyOrg) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + quoteIdent(schema) + "." + quoteIdent(table)
                + (onlyOrg ? " WHERE org_cd = ?" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (onlyOrg) {
                ps.setString(1, settings.orgCode());
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private String quoteIdent(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String buildYaml(Map<String, String> p) {
        int count = Integer.parseInt(p.getOrDefault("tgt_count", "0"));
        StringBuilder sb = new StringBuilder();

        // spring.datasource — 첫 번째 활성 target 과 동기화
        for (int i = 0; i < count; i++) {
            String host = safe(p.get("tgt_host_" + i));
            if (host.isEmpty()) continue;
            sb.append("spring:\n  datasource:\n")
              .append("    url: ").append(yaml("jdbc:postgresql://" + host + ":"
                      + safe(p.get("tgt_port_" + i)) + "/"
                      + safe(p.get("tgt_dbname_" + i)))).append("\n")
              .append("    username: ").append(yaml(p.get("tgt_user_" + i))).append("\n")
              .append("    password: ").append(yaml(p.get("tgt_pass_" + i))).append("\n\n");
            break;
        }

        String krasSched = safe(p.get("kras_schedule"));

        sb.append("kras:\n")
          .append("  url: ").append(yaml(p.get("kras_url"))).append("\n")
          .append("  conn-sys-id: ").append(yaml(p.get("kras_conn_sys_id"))).append("\n")
          .append("  chk-pnu: ").append(yaml(p.get("kras_chk_pnu"))).append("\n")
          .append("  work-dir: ").append(yaml(safe(p.get("kras_work_dir")).isEmpty() ? "./workspace/kras" : p.get("kras_work_dir"))).append("\n")
          .append("  shp-charset: ").append(yaml(safe(p.get("kras_shp_charset")).isEmpty() ? "MS949" : p.get("kras_shp_charset"))).append("\n")
          .append("  schedule: ").append(yaml(krasSched.isEmpty() ? "0 30 4 * * *" : krasSched)).append("\n")
          .append("  file-download:\n")
          .append("    schedule: ").append(yaml(safe(p.get("fd_schedule")))).append("\n")
          .append("    output-dir: ").append(yaml(safe(p.get("fd_output_dir")))).append("\n")
          .append("    cbnd-shp: ").append(p.get("fd_cbnd_shp") != null ? "true" : "false").append("\n")
          .append("    usezone-shp: ").append(p.get("fd_usezone_shp") != null ? "true" : "false").append("\n")
          .append("    jiga-txt: ").append(p.get("fd_jiga_txt") != null ? "true" : "false").append("\n")
          .append("    land-txt: ").append(p.get("fd_land_txt") != null ? "true" : "false").append("\n\n")
          .append("ods:\n")
          .append("  schema: ").append(yaml(safe(p.get("ods_schema")).isEmpty() ? "ods" : p.get("ods_schema"))).append("\n\n")
          .append("sync:\n")
          .append("  org-code: ").append(yaml(safe(p.get("sync_org_code")).isEmpty() ? "12830" : p.get("sync_org_code"))).append("\n\n")
          .append("targets:\n");

        for (int i = 0; i < count; i++) {
            String host = safe(p.get("tgt_host_" + i));
            if (host.isEmpty()) continue;
            String enabled = p.get("tgt_enabled_" + i);
            sb.append("  - name: ").append(yaml(p.get("tgt_name_" + i))).append("\n")
              .append("    host: ").append(yaml(host)).append("\n")
              .append("    port: ").append(safe(p.get("tgt_port_" + i))).append("\n")
              .append("    dbname: ").append(yaml(p.get("tgt_dbname_" + i))).append("\n")
              .append("    username: ").append(yaml(p.get("tgt_user_" + i))).append("\n")
              .append("    password: ").append(yaml(p.get("tgt_pass_" + i))).append("\n")
              .append("    enabled: ").append(enabled != null ? "true" : "false").append("\n");
        }

        return sb.toString();
    }

    private String safe(String v) {
        return v != null ? v.trim() : "";
    }

    private String yaml(String v) {
        return "\"" + safe(v).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String mapValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value).trim() : "";
    }
}
