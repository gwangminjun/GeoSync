package geomex.sync.web;

import geomex.sync.service.SyncStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final SyncStatusService statusService;

    @Value("${sync.org-code:46870}")
    private String orgCode;

    public DashboardController(SyncStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("currentPage", "dashboard");
        model.addAttribute("orgCode", orgCode);
        model.addAttribute("krasRunning", statusService.isRunning("KRAS"));
        model.addAttribute("kaisRunning", statusService.isRunning("KAIS"));
        model.addAttribute("krasLast", statusService.getLastRun("KRAS").orElse(null));
        model.addAttribute("kaisLast", statusService.getLastRun("KAIS").orElse(null));
        model.addAttribute("todaySuccess", statusService.todaySuccessCount());
        model.addAttribute("todayError", statusService.todayErrorCount());
        model.addAttribute("history", statusService.getRecentHistory(20));
        return "dashboard";
    }
}
