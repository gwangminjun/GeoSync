package geomex.sync.web;

import geomex.sync.service.KrasFileDownloadService;
import geomex.sync.service.RuntimeSettingsService;
import geomex.sync.service.SyncStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/file-download")
public class FileDownloadController {

    private final KrasFileDownloadService downloadService;
    private final RuntimeSettingsService settings;
    private final SyncStatusService statusService;

    public FileDownloadController(KrasFileDownloadService downloadService,
                                   RuntimeSettingsService settings,
                                   SyncStatusService statusService) {
        this.downloadService = downloadService;
        this.settings = settings;
        this.statusService = statusService;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("currentPage", "file-download");
        model.addAttribute("orgCode", settings.orgCode());
        model.addAttribute("krasRunning", statusService.isRunning("KRAS"));
        String defaultDir = settings.krasWorkDir().replace("\\", "/") + "/" + settings.orgCode();
        model.addAttribute("defaultOutputDir", defaultDir);
        model.addAttribute("downloading", downloadService.isRunning());
        return "file-download";
    }

    @PostMapping("/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> start(
            @RequestParam String outputDir,
            @RequestParam(defaultValue = "false") boolean cbndShp,
            @RequestParam(defaultValue = "false") boolean usezoneShp,
            @RequestParam(defaultValue = "false") boolean jigaTxt,
            @RequestParam(defaultValue = "false") boolean landTxt) {

        if (downloadService.isRunning()) {
            return ResponseEntity.ok(Map.of("started", false, "error", "이미 다운로드 중입니다."));
        }
        if (!cbndShp && !usezoneShp && !jigaTxt && !landTxt) {
            return ResponseEntity.ok(Map.of("started", false, "error", "다운로드할 항목을 하나 이상 선택하세요."));
        }
        if (outputDir == null || outputDir.isBlank()) {
            return ResponseEntity.ok(Map.of("started", false, "error", "저장 경로를 입력하세요."));
        }

        downloadService.startDownload(outputDir.trim(), cbndShp, usezoneShp, jigaTxt, landTxt);
        return ResponseEntity.ok(Map.of("started", true));
    }

    @GetMapping("/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> status() {
        KrasFileDownloadService.JobState job = downloadService.getStatus();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running",    job.running);
        result.put("done",       job.done);
        result.put("total",      job.total);
        result.put("completed",  job.completed);
        result.put("current",    job.current);
        result.put("fatalError", job.fatalError);

        List<Map<String, Object>> items = new ArrayList<>();
        for (KrasFileDownloadService.DownloadResult r : job.results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name",     r.name());
            item.put("filePath", r.filePath());
            item.put("bytes",    r.bytes());
            item.put("success",  r.success());
            item.put("error",    r.error());
            items.add(item);
        }
        result.put("results", items);
        return ResponseEntity.ok(result);
    }
}
