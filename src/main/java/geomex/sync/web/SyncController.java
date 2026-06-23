package geomex.sync.web;

import geomex.sync.scheduler.SyncScheduler;
import geomex.sync.service.SyncStatusService;
import geomex.sync.service.TargetDbService;
import geomex.sync.service.TargetTableNameService;
import geomex.sync.worker.KrasWorkspaceScanner;
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
    private final KrasWorkspaceScanner workspaceScanner;

    public SyncController(SyncScheduler scheduler, SyncStatusService statusService,
                          TargetDbService targetDbService, TargetTableNameService tableNameService,
                          KrasWorkspaceScanner workspaceScanner) {
        this.scheduler = scheduler;
        this.statusService = statusService;
        this.targetDbService = targetDbService;
        this.tableNameService = tableNameService;
        this.workspaceScanner = workspaceScanner;
    }

    @PostMapping("/kras")
    public String triggerKras(RedirectAttributes ra) {
        if (statusService.isRunning("KRAS")) {
            ra.addFlashAttribute("message", "KRAS 동기화가 이미 실행 중입니다.");
        } else {
            scheduler.triggerKrasAsync();
            ra.addFlashAttribute("message", "KRAS 동기화를 시작했습니다.");
        }
        return "redirect:/";
    }

    @PostMapping("/kais")
    public String triggerKais(RedirectAttributes ra) {
        if (statusService.isRunning("KAIS")) {
            ra.addFlashAttribute("message", "KAIS 동기화가 이미 실행 중입니다.");
        } else {
            scheduler.triggerKaisAsync();
            ra.addFlashAttribute("message", "KAIS 동기화를 시작했습니다.");
        }
        return "redirect:/";
    }

    @PostMapping("/kras-collect")
    public String triggerKrasCollect(RedirectAttributes ra) {
        if (statusService.isRunning("KRAS_COLLECT")) {
            ra.addFlashAttribute("message", "KRAS 수집이 이미 실행 중입니다.");
        } else {
            scheduler.triggerKrasCollectAsync();
            ra.addFlashAttribute("message", "KRAS 수집을 시작했습니다 (API → 파일).");
        }
        return "redirect:/";
    }

    /** JS 폴링용 실행 상태 조회 */
    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> syncStatus() {
        var types = List.of("KRAS", "KRAS_COLLECT", "KRAS_LOAD", "KAIS");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String type : types) {
            boolean isRunning = statusService.isRunning(type);
            Map<String, Object> info = new java.util.LinkedHashMap<>();
            info.put("running", isRunning);
            if (isRunning) {
                var t = statusService.getRunningStartTime(type);
                if (t != null) info.put("startedAt", t.toString());
            }
            result.put(type, info);
        }
        return result;
    }

    /** 적재 확인 모달용 옵션 조회 */
    @GetMapping("/kras-load-options")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> krasLoadOptions() {
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

    @PostMapping("/kras-load")
    public String triggerKrasLoad(
            @RequestParam(required = false) List<Integer> targetIdx,
            @RequestParam(required = false, defaultValue = "") String schema,
            RedirectAttributes ra) {

        if (statusService.isRunning("KRAS_LOAD")) {
            ra.addFlashAttribute("message", "KRAS 적재가 이미 실행 중입니다.");
            return "redirect:/";
        }

        String schemaOverride = schema.isBlank() ? null : schema.trim();
        scheduler.triggerKrasLoadAsync(targetIdx, schemaOverride);

        String targetDesc = (targetIdx == null || targetIdx.isEmpty()) ? "전체 DB" : targetIdx.size() + "개 DB";
        String schemaDesc = schemaOverride != null ? schemaOverride : tableNameService.getOdsSchema();
        ra.addFlashAttribute("message",
            "KRAS 적재를 시작했습니다 (" + targetDesc + ", 스키마: " + schemaDesc + ").");
        return "redirect:/";
    }

    /** workspace의 SHP/TXT 파일에서 JSON 캐시 + manifest 생성 */
    @PostMapping("/kras-scan-workspace")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> scanWorkspace() {
        try {
            KrasWorkspaceScanner.ScanResult result = workspaceScanner.buildManifest();
            return ResponseEntity.ok(Map.of(
                "fileCount", result.fileCount(),
                "rowCount",  result.rowCount(),
                "messages",  result.messages()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage(), "messages", List.of()));
        }
    }

    private static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) return "URL 없음";
        return url.replaceFirst("^jdbc:postgresql://", "");
    }
}
