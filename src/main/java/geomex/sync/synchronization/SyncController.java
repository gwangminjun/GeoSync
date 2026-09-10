package geomex.sync.synchronization;

import geomex.sync.database.TargetDbService;
import geomex.sync.database.TargetTableNameService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/sync")
public class SyncController {

    private final SyncScheduler scheduler;
    private final SyncStatusService statusService;
    private final TargetDbService targetDbService;
    private final TargetTableNameService tableNameService;
    private final KrasWorker krasWorker;

    public SyncController(SyncScheduler scheduler, SyncStatusService statusService,
                          TargetDbService targetDbService, TargetTableNameService tableNameService,
                          KrasWorker krasWorker) {
        this.scheduler = scheduler;
        this.statusService = statusService;
        this.targetDbService = targetDbService;
        this.tableNameService = tableNameService;
        this.krasWorker = krasWorker;
    }

    /** JS 폴링용 실행 상태 조회 */
    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> syncStatus() {
        var types = List.of("KRAS", "KRAS_COLLECT", "KRAS_LOAD");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String type : types) {
            boolean isRunning = statusService.isRunning(type);
            Map<String, Object> info = new java.util.LinkedHashMap<>();
            info.put("running", isRunning);
            if (isRunning) {
                var t = statusService.getRunningStartTime(type);
                if (t != null) info.put("startedAt", t.toString());
                var progress = statusService.getProgress(type);
                info.put("total", progress.total());
                info.put("completed", progress.completed());
                info.put("percent", progress.percent());
                info.put("current", progress.current());
                info.put("rowTotal", progress.rowTotal());
                info.put("rowCompleted", progress.rowCompleted());
                info.put("rowPercent", progress.rowPercent());
                info.put("rowCurrent", progress.rowCurrent());
            }
            result.put(type, info);
        }
        return result;
    }

    /** 직접 적재용 대상 DB 목록 조회 */
    @GetMapping("/kras-direct-load-options")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> krasDirectLoadOptions() {
        List<TargetDbService.ActiveTarget> targets = targetDbService.getConfiguredTargets();
        List<Map<String, Object>> targetList = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            TargetDbService.ActiveTarget t = targets.get(i);
            targetList.add(Map.of("idx", i, "name", t.name(), "url", sanitizeUrl(t.url())));
        }
        return ResponseEntity.ok(Map.of(
            "targets", targetList,
            "schema", tableNameService.getOdsSchema()
        ));
    }

    /** 직접 적재: lt_c_uzone=API→DB, lp_pa_cbnd=SHP→DB */
    @PostMapping("/kras-direct-load")
    public String triggerKrasDirectLoad(
            @RequestParam(required = false) List<Integer> targetIdx,
            @RequestParam(required = false) List<String> tables,
            @RequestParam Map<String, String> allParams,
            RedirectAttributes ra) {

        if (statusService.isRunning("KRAS_LOAD")) {
            ra.addFlashAttribute("message", "KRAS 적재가 이미 실행 중입니다.");
            return "redirect:/";
        }

        String schema = DatabaseRequestCompatibility.schema(allParams, tableNameService.getOdsSchema());
        Map<Integer, String> schemaMap = schema.isBlank() ? null : Map.of(0, schema);

        java.util.Set<String> tableFilter = (tables != null && !tables.isEmpty())
                ? new java.util.HashSet<>(tables) : null;

        statusService.recordStart("KRAS_LOAD");
        scheduler.triggerKrasDirectLoadAsync(null, schemaMap, tableFilter);

        String tableDesc = tableFilter == null ? "전체 테이블" : String.join(", ", tableFilter);
        String targetDesc = "단일 DB";
        ra.addFlashAttribute("message", "KRAS 적재를 시작했습니다 (" + targetDesc + " / " + tableDesc + ").");
        return "redirect:/";
    }

    /** 데이터 미리보기: lt_c_uzone=API, lp_pa_cbnd=DB */
    @GetMapping("/preview")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> preview(
            @RequestParam String table,
            @RequestParam(required = false) String layer,
            @RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        try {
            return switch (table) {
                case "lt_c_uzone" -> {
                    if (layer == null || layer.isBlank())
                        yield ResponseEntity.badRequest().body(Map.of("error", "layer 파라미터가 필요합니다."));
                    yield ResponseEntity.ok(krasWorker.testFeatures(layer, safeLimit));
                }
                case "lp_pa_cbnd" -> ResponseEntity.ok(krasWorker.previewShpTable("lp_pa_cbnd", safeLimit));
                default -> ResponseEntity.badRequest().body(Map.of("error", "지원하지 않는 테이블: " + table));
            };
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Mock 적재: lt_c_uzone=간이 3건, lp_pa_cbnd=SHP */
    @PostMapping("/mock-load")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> mockLoad(
            @RequestParam(required = false) List<Integer> targetIdx,
            @RequestParam(defaultValue = "test") String schema) {
        try {
            return ResponseEntity.ok(krasWorker.runMockLoad(null, schema));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** KRAS API 직접 테스트 (CHECK / GetLayerList / GetFeature) */
    @GetMapping("/kras-api-test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> krasApiTest(
            @RequestParam String service,
            @RequestParam(required = false) String layer,
            @RequestParam(defaultValue = "5") int limit) {
        try {
            return switch (service) {
                case "check" -> ResponseEntity.ok(krasWorker.testConnection());
                case "layers" -> {
                    List<Map<String, Object>> layers = krasWorker.testLayerList();
                    yield ResponseEntity.ok(Map.of("layers", layers, "count", layers.size()));
                }
                case "features" -> {
                    if (layer == null || layer.isBlank())
                        yield ResponseEntity.badRequest().body(Map.of("error", "layer 파라미터가 필요합니다."));
                    yield ResponseEntity.ok(krasWorker.testFeatures(layer, limit));
                }
                default -> ResponseEntity.badRequest().body(Map.of("error", "알 수 없는 service: " + service));
            };
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) return "URL 없음";
        return url.replaceFirst("^jdbc:postgresql://", "");
    }
}
