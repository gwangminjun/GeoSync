package geosync.ods;

import geosync.settings.RuntimeSettingsService;
import geosync.synchronization.SyncStatusService;
import geosync.database.TargetDbService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/ods")
public class OdsController {

    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
        "information_schema", "pg_catalog", "pg_toast", "pg_temp_1", "pg_toast_temp_1"
    );

    private final TargetDbService targetDbService;
    private final SyncStatusService statusService;
    private final RuntimeSettingsService settings;

    public OdsController(TargetDbService targetDbService, SyncStatusService statusService,
                         RuntimeSettingsService settings) {
        this.targetDbService = targetDbService;
        this.statusService = statusService;
        this.settings = settings;
    }

    @GetMapping
    public String odsPage(Model model) {
        model.addAttribute("currentPage", "ods");
        model.addAttribute("orgCode", settings.orgCode());
        model.addAttribute("krasRunning", statusService.isRunning("KRAS"));
        model.addAttribute("targets", targetDbService.getConfiguredTargets());
        return "ods";
    }

    @GetMapping("/schemas")
    @ResponseBody
    public Map<String, Object> getSchemas(@RequestParam(defaultValue = "0") int idx) {
        List<TargetDbService.ActiveTarget> targets = targetDbService.getConfiguredTargets();
        if (targets.isEmpty()) return Map.of("error", "DB가 준비되지 않았습니다");
        JdbcTemplate jdbc = targets.get(0).jdbc();
        try {
            List<String> schemas = jdbc.queryForList(
                "SELECT schema_name FROM information_schema.schemata " +
                "WHERE schema_name NOT LIKE 'pg_%' " +
                "AND schema_name NOT IN ('information_schema') " +
                "ORDER BY schema_name",
                String.class);
            return Map.of("schemas", schemas);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @GetMapping("/tables")
    @ResponseBody
    public Map<String, Object> getTables(
            @RequestParam(defaultValue = "0") int idx,
            @RequestParam String schema) {

        if (!SAFE_NAME.matcher(schema).matches() || SYSTEM_SCHEMAS.contains(schema)) {
            return Map.of("error", "유효하지 않은 스키마명");
        }
        List<TargetDbService.ActiveTarget> targets = targetDbService.getConfiguredTargets();
        if (targets.isEmpty()) return Map.of("error", "DB가 준비되지 않았습니다");
        JdbcTemplate jdbc = targets.get(0).jdbc();

        try {
            List<String> tableNames = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = ? AND table_type = 'BASE TABLE' " +
                "ORDER BY table_name",
                String.class, schema);

            List<Map<String, Object>> tables = new ArrayList<>();
            for (String tableName : tableNames) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", tableName);
                try {
                    Long count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM \"" + schema + "\".\"" + tableName + "\"", Long.class);
                    info.put("count", count);
                } catch (Exception e) {
                    info.put("count", -1L);
                }
                tables.add(info);
            }
            return Map.of("tables", tables);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @GetMapping("/preview")
    @ResponseBody
    public Map<String, Object> getPreview(
            @RequestParam(defaultValue = "0") int idx,
            @RequestParam String schema,
            @RequestParam String table,
            @RequestParam(defaultValue = "0") int page) {

        if (!SAFE_NAME.matcher(schema).matches() || SYSTEM_SCHEMAS.contains(schema)) {
            return Map.of("error", "유효하지 않은 스키마명");
        }
        if (!SAFE_NAME.matcher(table).matches()) {
            return Map.of("error", "유효하지 않은 테이블명");
        }

        List<TargetDbService.ActiveTarget> targets = targetDbService.getConfiguredTargets();
        if (targets.isEmpty()) return Map.of("error", "DB가 준비되지 않았습니다");
        JdbcTemplate jdbc = targets.get(0).jdbc();

        int pageSize = 20;
        int offset = page * pageSize;

        try {
            List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? " +
                "AND udt_name NOT IN ('geometry','geography') " +
                "ORDER BY ordinal_position",
                String.class, schema, table);

            if (columns.isEmpty()) {
                return Map.of("columns", List.of(), "rows", List.of(), "page", page, "hasMore", false);
            }

            String colList = columns.stream()
                .map(c -> "\"" + c + "\"")
                .collect(Collectors.joining(", "));

            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + colList + " FROM \"" + schema + "\".\"" + table + "\"" +
                " ORDER BY 1 LIMIT ? OFFSET ?",
                pageSize + 1, offset);

            boolean hasMore = rows.size() > pageSize;
            if (hasMore) rows = rows.subList(0, pageSize);

            List<List<String>> rowData = rows.stream()
                .map(r -> columns.stream()
                    .map(c -> { Object v = r.get(c); return v != null ? v.toString() : ""; })
                    .collect(Collectors.toList()))
                .collect(Collectors.toList());

            return Map.of("columns", columns, "rows", rowData, "page", page, "hasMore", hasMore);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}
