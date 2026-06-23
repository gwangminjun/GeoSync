package geomex.sync.web;

import geomex.sync.service.KrasCatalogStatusService;
import geomex.sync.service.SyncStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final SyncStatusService statusService;
    private final KrasCatalogStatusService catalogStatusService;

    @Value("${sync.org-code:46870}")
    private String orgCode;

    public DashboardController(SyncStatusService statusService, KrasCatalogStatusService catalogStatusService) {
        this.statusService = statusService;
        this.catalogStatusService = catalogStatusService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("currentPage", "dashboard");
        model.addAttribute("orgCode", orgCode);
        model.addAttribute("krasRunning", statusService.isRunning("KRAS"));
        model.addAttribute("kaisRunning", statusService.isRunning("KAIS"));
        model.addAttribute("krasCollectRunning", statusService.isRunning("KRAS_COLLECT"));
        model.addAttribute("krasLoadRunning", statusService.isRunning("KRAS_LOAD"));
        model.addAttribute("krasLast", statusService.getLastRun("KRAS").orElse(null));
        model.addAttribute("kaisLast", statusService.getLastRun("KAIS").orElse(null));
        model.addAttribute("todaySuccess", statusService.todaySuccessCount());
        model.addAttribute("todayError", statusService.todayErrorCount());
        model.addAttribute("catalogStatuses", catalogStatusService.getStatuses());
        model.addAttribute("history", statusService.getRecentHistory(20));
        return "dashboard";
    }
}
