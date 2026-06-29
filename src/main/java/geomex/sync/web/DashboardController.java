package geomex.sync.web;

import geomex.sync.service.KrasCatalogStatusService;
import geomex.sync.service.RuntimeSettingsService;
import geomex.sync.service.SyncStatusService;
import geomex.sync.worker.KorepsApiClient;
import geomex.sync.worker.KrasApiClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class DashboardController {

    private final SyncStatusService statusService;
    private final KrasCatalogStatusService catalogStatusService;
    private final RuntimeSettingsService settings;
    private final KrasApiClient krasApiClient;
    private final KorepsApiClient korepsApiClient;

    public DashboardController(SyncStatusService statusService,
                               KrasCatalogStatusService catalogStatusService,
                               RuntimeSettingsService settings,
                               KrasApiClient krasApiClient,
                               KorepsApiClient korepsApiClient) {
        this.statusService = statusService;
        this.catalogStatusService = catalogStatusService;
        this.settings = settings;
        this.krasApiClient = krasApiClient;
        this.korepsApiClient = korepsApiClient;
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

    /** KRAS / KOREPS estateGateway 연결 상태 확인 */
    @GetMapping("/api/gateway-status")
    @ResponseBody
    public Map<String, Object> gatewayStatus() {
        String chkPnu = settings.krasChkPnu();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kras",   krasApiClient.testConnection());
        result.put("koreps", korepsApiClient.testConnection(chkPnu));
        return result;
    }
}
