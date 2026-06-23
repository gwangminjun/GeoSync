package geomex.sync.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import geomex.sync.scheduler.DynamicScheduleManager;
import geomex.sync.service.RuntimeSettingsService;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeSettingsService settings;
    private final DynamicScheduleManager scheduleManager;

    public SettingsController(RuntimeSettingsService settings, DynamicScheduleManager scheduleManager) {
        this.settings = settings;
        this.scheduleManager = scheduleManager;
    }

    @Value("${spring.config.location:conf/application.yml}")
    private String configLocation;

    @Value("${kras.chk-pnu:4687025625111190010}")
    private String defaultChkPnu;

    @Value("${sync.org-code:46870}")
    private String orgCode;

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
                Map<String, Object> ods = childMap(root, "ods");
                values.put("kras_url",         extractYamlValue(content, "url",        "kras"));
                values.put("kras_conn_sys_id", extractYamlValue(content, "conn-sys-id","kras"));
                values.put("kras_chk_pnu",     extractYamlValue(content, "chk-pnu",   "kras"));
                values.put("kras_schedule",    extractYamlValue(content, "schedule",   "kras"));
                values.put("ods_schema",       yamlValue(ods, "schema", "ods"));
                values.put("kais_work_dir",    extractYamlValue(content, "work-dir",   "kais"));
                values.put("kais_schedule",    extractYamlValue(content, "schedule",   "kais"));

                // chk-pnu fallback
                if (values.get("kras_chk_pnu").isEmpty()) {
                    values.put("kras_chk_pnu", defaultChkPnu);
                }

                targets = readTargets(content);
            } catch (IOException e) {
                log.warn("설정 파일 읽기 실패: {}", e.getMessage());
            }
        }
        values.putIfAbsent("ods_schema", "ods");

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
        Path configFile = resolveConfigPath();
        if (!Files.exists(configFile)) {
            return Map.of("success", false, "message", "Config file not found");
        }

        try {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            List<Map<String, Object>> targets = readTargets(content);
            if (target_index < 0 || target_index >= targets.size()) {
                return Map.of("success", false, "message", "Target DB not found");
            }

            Map<String, Object> target = targets.get(target_index);
            String schema = safe(ods_schema).isEmpty() ? "ods" : safe(ods_schema);
            String host = mapValue(target, "host");
            String port = mapValue(target, "port");
            String dbname = mapValue(target, "dbname");
            String user = mapValue(target, "username");
            String password = mapValue(target, "password");
            String name = mapValue(target, "name");
            String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbname;

            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                boolean schemaExists = schemaExists(conn, schema);
                List<Map<String, Object>> tables = schemaExists
                        ? inspectSchemaTables(conn, schema)
                        : List.of();
                return Map.of(
                        "success", true,
                        "target", name.isEmpty() ? url : name,
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
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, buildYaml(params), StandardCharsets.UTF_8);
            settings.reload();
            scheduleManager.reloadSchedules();
            log.info("설정 저장: {}", configFile.toAbsolutePath());
            ra.addFlashAttribute("success", "설정을 저장했습니다. 변경 사항은 재시작 없이 바로 적용됩니다.");
        } catch (IOException e) {
            log.error("설정 저장 실패: {}", e.getMessage());
            ra.addFlashAttribute("error", "설정 저장 실패: " + e.getMessage());
        }
        return "redirect:/settings";
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
                + (onlyOrg ? " WHERE org_cd = '" + settings.orgCode().replace("'", "''") + "'" : "");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private String quoteIdent(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String extractYamlValue(String content, String key, String section) {
        int sectionIdx = content.indexOf("\n" + section + ":");
        if (sectionIdx < 0) sectionIdx = content.indexOf(section + ":");
        String searchIn = sectionIdx >= 0 ? content.substring(sectionIdx) : content;
        int nextSection = searchIn.indexOf("\n\n");
        if (nextSection > 0) searchIn = searchIn.substring(0, nextSection);
        Pattern p = Pattern.compile("(?m)^\\s+" + Pattern.quote(key) + ":\\s*(.+)$");
        Matcher m = p.matcher(searchIn);
        if (!m.find()) return "";
        String raw = m.group(1).trim();
        if (raw.length() >= 2) {
            char f = raw.charAt(0), l = raw.charAt(raw.length() - 1);
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'')) {
                raw = raw.substring(1, raw.length() - 1);
            }
        }
        return raw;
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

        sb.append("kras:\n")
          .append("  url: ").append(yaml(p.get("kras_url"))).append("\n")
          .append("  conn-sys-id: ").append(yaml(p.get("kras_conn_sys_id"))).append("\n")
          .append("  chk-pnu: ").append(yaml(p.get("kras_chk_pnu"))).append("\n")
          .append("  schedule: ").append(yaml(p.get("kras_schedule"))).append("\n\n")
          .append("kais:\n")
          .append("  work-dir: ").append(yaml(p.get("kais_work_dir"))).append("\n")
          .append("  schedule: ").append(yaml(p.get("kais_schedule"))).append("\n\n")
          .append("ods:\n")
          .append("  schema: ").append(yaml(safe(p.get("ods_schema")).isEmpty() ? "ods" : p.get("ods_schema"))).append("\n\n")
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
