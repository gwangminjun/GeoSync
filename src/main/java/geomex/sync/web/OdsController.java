package geomex.sync.web;

import geomex.sync.service.SyncStatusService;
import geomex.sync.service.TargetDbService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/ods")
public class OdsController {

    private static final List<Map<String, String>> ODS_TABLES = List.of(
        Map.of("name", "ods.lp_pa_cbnd",    "desc", "연속지적도",   "src", "KRAS"),
        Map.of("name", "ods.lt_c_uzone",     "desc", "용도지역지구", "src", "KRAS"),
        Map.of("name", "ods.land_frst_ledg", "desc", "토지기본정보", "src", "KRAS"),
        Map.of("name", "ods.tl_spbd_buld",   "desc", "건물",         "src", "KAIS"),
        Map.of("name", "ods.tl_sprd_manage", "desc", "도로구간",     "src", "KAIS"),
        Map.of("name", "ods.tl_sprd_intrvl", "desc", "기초구간",     "src", "KAIS"),
        Map.of("name", "ods.tl_sprd_crsrd",  "desc", "교차로",       "src", "KAIS"),
        Map.of("name", "ods.tl_spbd_entrc",  "desc", "출입구",       "src", "KAIS")
    );

    private static final Set<String> TABLE_NAMES = ODS_TABLES.stream()
        .map(t -> t.get("name")).collect(Collectors.toUnmodifiableSet());

    private final TargetDbService targetDbService;
    private final SyncStatusService statusService;

    @Value("${sync.org-code:46870}")
    private String orgCode;

    public OdsController(TargetDbService targetDbService, SyncStatusService statusService) {
        this.targetDbService = targetDbService;
        this.statusService = statusService;
    }

    @GetMapping
    public String odsPage(Model model) {
        model.addAttribute("currentPage", "ods");
        model.addAttribute("orgCode", orgCode);
        model.addAttribute("krasRunning", statusService.isRunning("KRAS"));
        model.addAttribute("kaisRunning", statusService.isRunning("KAIS"));
        model.addAttribute("targets", targetDbService.getActiveTargets());
        return "ods";
    }

    @GetMapping("/tables")
    @ResponseBody
    public Map<String, Object> getTables(@RequestParam(defaultValue = "0") int idx) {
        List<TargetDbService.ActiveTarget> targets = targetDbService.getActiveTargets();
        if (idx < 0 || idx >= targets.size()) {
            return Map.of("error", "대상 DB를 찾을 수 없습니다");
        }
        JdbcTemplate jdbc = targets.get(idx).jdbc();

        List<Map<String, Object>> tables = new ArrayList<>();
        for (Map<String, String> tbl : ODS_TABLES) {
            String tableName = tbl.get("name");
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", tableName);
            info.put("desc", tbl.get("desc"));
            info.put("src", tbl.get("src"));
            try {
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
                info.put("count", count);
                info.put("exists", true);
            } catch (Exception e) {
                info.put("count", -1L);
                info.put("exists", false);
            }
            tables.add(info);
        }
        return Map.of("tables", tables);
    }

    @GetMapping("/preview")
    @ResponseBody
    public Map<String, Object> getPreview(
            @RequestParam(defaultValue = "0") int idx,
            @RequestParam String table,
            @RequestParam(defaultValue = "0") int page) {

        if (!TABLE_NAMES.contains(table)) {
            return Map.of("error", "유효하지 않은 테이블");
        }
        List<TargetDbService.ActiveTarget> targets = targetDbService.getActiveTargets();
        if (idx < 0 || idx >= targets.size()) {
            return Map.of("error", "대상 DB를 찾을 수 없습니다");
        }
        JdbcTemplate jdbc = targets.get(idx).jdbc();

        int pageSize = 20;
        int offset = page * pageSize;
        String[] parts = table.split("\\.");

        try {
            List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? " +
                "AND udt_name NOT IN ('geometry','geography') " +
                "ORDER BY ordinal_position",
                String.class, parts[0], parts[1]);

            if (columns.isEmpty()) {
                return Map.of("columns", List.of(), "rows", List.of(), "page", page, "hasMore", false);
            }

            String colList = columns.stream()
                .map(c -> "\"" + c + "\"")
                .collect(Collectors.joining(", "));

            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + colList + " FROM " + table + " ORDER BY 1 LIMIT ? OFFSET ?",
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
