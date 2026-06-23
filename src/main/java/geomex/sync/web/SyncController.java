package geomex.sync.web;

import geomex.sync.scheduler.SyncScheduler;
import geomex.sync.service.SyncStatusService;
import geomex.sync.service.TargetDbService;
import geomex.sync.service.TargetTableNameService;
import geomex.sync.worker.KrasFileReader;
import geomex.sync.worker.KrasWorkspaceScanner;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/sync")
public class SyncController {

    private final SyncScheduler scheduler;
    private final SyncStatusService statusService;
    private final TargetDbService targetDbService;
    private final TargetTableNameService tableNameService;
    private final KrasWorkspaceScanner workspaceScanner;
    private final KrasFileReader fileReader;

    public SyncController(SyncScheduler scheduler, SyncStatusService statusService,
                          TargetDbService targetDbService, TargetTableNameService tableNameService,
                          KrasWorkspaceScanner workspaceScanner, KrasFileReader fileReader) {
        this.scheduler = scheduler;
        this.statusService = statusService;
        this.targetDbService = targetDbService;
        this.tableNameService = tableNameService;
        this.workspaceScanner = workspaceScanner;
        this.fileReader = fileReader;
    }

    @PostMapping("/kras")
    public String triggerKras(RedirectAttributes ra) {
        if (statusService.isRunning("KRAS")) {
            ra.addFlashAttribute("message", "KRAS 동기화가 이미 실행 중입니다.");
        } else {
            statusService.recordStart("KRAS");
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
            statusService.recordStart("KAIS");
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
            statusService.recordStart("KRAS_COLLECT");
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

        // manifest 우선 로드, 없으면 빈 맵
        Map<String, java.util.List<String>> merged = new java.util.LinkedHashMap<>();
        try {
            merged.putAll(fileReader.readManifest());
        } catch (Exception ignored) {}

        // 워크스페이스에서 파일 탐색 후 manifest에 없는 항목 보완
        try {
            workspaceScanner.discoverWorkspaceFiles().forEach((table, files) -> {
                if (!merged.containsKey(table)) merged.put(table, files);
            });
        } catch (Exception ignored) {}

        List<Map<String, String>> files = new ArrayList<>();
        merged.forEach((table, fileNames) ->
            fileNames.forEach(f -> files.add(Map.of("table", table, "file", f)))
        );

        return ResponseEntity.ok(Map.of(
            "targets", targetList,
            "schema", tableNameService.getOdsSchema(),
            "files", files
        ));
    }

    @PostMapping("/kras-load")
    public String triggerKrasLoad(
            @RequestParam(required = false) List<Integer> targetIdx,
            @RequestParam Map<String, String> allParams,
            @RequestParam(required = false) List<String> files,
            RedirectAttributes ra) {

        if (statusService.isRunning("KRAS_LOAD")) {
            ra.addFlashAttribute("message", "KRAS 적재가 이미 실행 중입니다.");
            return "redirect:/";
        }

        // 각 DB별 스키마 파싱: schema_0=ods, schema_1=public, ...
        Map<Integer, String> schemaMap = new java.util.HashMap<>();
        if (targetIdx != null) {
            for (Integer idx : targetIdx) {
                String schema = allParams.get("schema_" + idx);
                if (schema != null && !schema.isBlank()) {
                    schemaMap.put(idx, schema.trim());
                }
            }
        }

        Set<String> fileFilter = (files == null || files.isEmpty()) ? null : new HashSet<>(files);
        statusService.recordStart("KRAS_LOAD");
        scheduler.triggerKrasLoadAsync(targetIdx, schemaMap.isEmpty() ? null : schemaMap, fileFilter);

        String targetDesc = (targetIdx == null || targetIdx.isEmpty()) ? "전체 DB" : targetIdx.size() + "개 DB";
        String fileDesc = (fileFilter == null) ? "전체 파일" : fileFilter.size() + "개 파일";
        ra.addFlashAttribute("message",
            "KRAS 적재를 시작했습니다 (" + targetDesc + ", " + fileDesc + ").");
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
