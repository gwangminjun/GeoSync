package geosync.synchronization;

import geosync.configuration.TargetDb;
import geosync.synchronization.model.SyncHistory;
import geosync.settings.RuntimeSettingsService;
import geosync.kras.KrasFileReader;
import geosync.kras.KrasFileReader.ManifestInfo;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDateTime;
import java.util.*;

@Controller
public class ScheduleController {

    private final DynamicScheduleManager scheduleManager;
    private final SyncStatusService statusService;
    private final KrasFileReader fileReader;
    private final TableMapper tableMapper;
    private final RuntimeSettingsService settings;
    private final SyncExecutionLogService executionLogService;

    public ScheduleController(DynamicScheduleManager scheduleManager,
                              SyncStatusService statusService,
                              KrasFileReader fileReader,
                              TableMapper tableMapper,
                              RuntimeSettingsService settings,
                              SyncExecutionLogService executionLogService) {
        this.scheduleManager = scheduleManager;
        this.statusService = statusService;
        this.fileReader = fileReader;
        this.tableMapper = tableMapper;
        this.settings = settings;
        this.executionLogService = executionLogService;
    }

    public record TableEntry(String srcTable, String tgtTable, List<String> files) {}

    @GetMapping("/schedule")
    public String schedulePage(Model model) {
        model.addAttribute("currentPage", "schedule");
        model.addAttribute("orgCode", settings.orgCode());
        model.addAttribute("krasRunning", statusService.isRunning("KRAS") || statusService.isRunning("KRAS_LOAD"));

        String krasCron = scheduleManager.getKrasCron();
        model.addAttribute("krasCron", krasCron);
        model.addAttribute("krasActive", scheduleManager.isKrasActive());

        LocalDateTime krasNext = scheduleManager.getNextRunTime(krasCron);
        model.addAttribute("krasNext", krasNext);

        SyncHistory krasLast = statusService.getLastRun("KRAS_LOAD")
                .or(() -> statusService.getLastRun("KRAS"))
                .orElse(null);
        model.addAttribute("krasLast", krasLast);

        String fdCron = scheduleManager.getFileDownloadCron();
        model.addAttribute("fdCron", fdCron);
        model.addAttribute("fdActive", scheduleManager.isFileDownloadActive());
        model.addAttribute("fdInterval", scheduleManager.isFileDownloadInterval());
        LocalDateTime fdNext = scheduleManager.getNextRunTime(fdCron);
        model.addAttribute("fdNext", fdNext);
        model.addAttribute("fdOutputDir", settings.fileDownloadOutputDir());
        model.addAttribute("fdCbndShp", settings.fileDownloadCbndShp());
        model.addAttribute("fdUsezoneShp", settings.fileDownloadUsezoneShp());
        model.addAttribute("fdJigaTxt", settings.fileDownloadJigaTxt());
        model.addAttribute("fdLandTxt", settings.fileDownloadLandTxt());

        // manifest + table 정의 → 적재 대상 목록
        ManifestInfo manifest = fileReader.readManifestInfo();
        model.addAttribute("manifestCollectedAt", manifest.collectedAt());

        List<TableEntry> tableEntries = new ArrayList<>();
        if (!manifest.isEmpty()) {
            Map<String, String> srcToTgt = new LinkedHashMap<>();
            try {
                tableMapper.load(settings.krasConfig())
                        .forEach(d -> srcToTgt.put(d.srcTableName, d.tgtTableName));
            } catch (Exception ignored) {}

            manifest.tables().forEach((src, files) -> {
                String tgt = srcToTgt.getOrDefault(src, src.toLowerCase());
                tableEntries.add(new TableEntry(src, tgt, files));
            });
        }
        model.addAttribute("tableEntries", tableEntries);
        model.addAttribute("hasManifest", !manifest.isEmpty());

        // 적재 대상 DB 목록 (활성만)
        List<TargetDb> targets = settings.targets().stream()
                .filter(TargetDb::isEnabled).toList();
        model.addAttribute("targets", targets);
        model.addAttribute("odsSchema", settings.odsSchema());

        // 실행 이력 (최근 30건)
        model.addAttribute("executionLogs", executionLogService.recent(30));
        model.addAttribute("krasInterval", scheduleManager.isKrasInterval());

        return "schedule";
    }
}
