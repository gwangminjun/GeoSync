package geomex.sync.web;

import geomex.sync.config.TargetDb;
import geomex.sync.mapper.TableMapper;
import geomex.sync.model.SyncHistory;
import geomex.sync.scheduler.DynamicScheduleManager;
import geomex.sync.service.RuntimeSettingsService;
import geomex.sync.service.SyncStatusService;
import geomex.sync.worker.KrasFileReader;
import geomex.sync.worker.KrasFileReader.ManifestInfo;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${sync.org-code:46870}")
    private String orgCode;

    public ScheduleController(DynamicScheduleManager scheduleManager,
                              SyncStatusService statusService,
                              KrasFileReader fileReader,
                              TableMapper tableMapper,
                              RuntimeSettingsService settings) {
        this.scheduleManager = scheduleManager;
        this.statusService = statusService;
        this.fileReader = fileReader;
        this.tableMapper = tableMapper;
        this.settings = settings;
    }

    public record TableEntry(String srcTable, String tgtTable, List<String> files) {}

    @GetMapping("/schedule")
    public String schedulePage(Model model) {
        model.addAttribute("currentPage", "schedule");
        model.addAttribute("orgCode", orgCode);
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

        return "schedule";
    }
}
