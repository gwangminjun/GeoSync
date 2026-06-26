package geomex.sync.web;

import geomex.sync.service.KrasCatalogStatusService;
import geomex.sync.service.RuntimeSettingsService;
import geomex.sync.service.SyncStatusService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final SyncStatusService statusService;
    private final KrasCatalogStatusService catalogStatusService;
    private final RuntimeSettingsService settings;

    public DashboardController(SyncStatusService statusService,
                               KrasCatalogStatusService catalogStatusService,
                               RuntimeSettingsService settings) {
        this.statusService = statusService;
        this.catalogStatusService = catalogStatusService;
        this.settings = settings;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("currentPage", "dashboard");
        model.addAttribute("orgCode", settings.orgCode());
        model.addAttribute("krasRunning", statusService.isRunning("KRAS"));
        model.addAttribute("krasCollectRunning", statusService.isRunning("KRAS_COLLECT"));
        model.addAttribute("krasLoadRunning", statusService.isRunning("KRAS_LOAD"));
        model.addAttribute("krasLast", statusService.getLastRun("KRAS").orElse(null));
        model.addAttribute("todaySuccess", statusService.todaySuccessCount());
        model.addAttribute("todayError", statusService.todayErrorCount());
        model.addAttribute("catalogStatuses", catalogStatusService.getStatuses());
        model.addAttribute("history", statusService.getRecentHistory(20));
        return "dashboard";
    }
}
