package geomex.sync.monitoring;

import geomex.sync.kras.KrasCatalogStatusService;
import geomex.sync.settings.RuntimeSettingsService;
import geomex.sync.synchronization.SyncExecutionLogService;
import geomex.sync.synchronization.SyncStatusService;
import geomex.sync.kras.KorepsApiClient;
import geomex.sync.kras.KrasApiClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Controller
public class DashboardController {

    private final SyncStatusService statusService;
    private final KrasCatalogStatusService catalogStatusService;
    private final RuntimeSettingsService settings;
    private final KrasApiClient krasApiClient;
    private final KorepsApiClient korepsApiClient;
    private final SyncExecutionLogService syncExecutionLogService;

    public DashboardController(SyncStatusService statusService,
                               KrasCatalogStatusService catalogStatusService,
                               RuntimeSettingsService settings,
                               KrasApiClient krasApiClient,
                               KorepsApiClient korepsApiClient,
                               SyncExecutionLogService syncExecutionLogService) {
        this.statusService = statusService;
        this.catalogStatusService = catalogStatusService;
        this.settings = settings;
        this.krasApiClient = krasApiClient;
        this.korepsApiClient = korepsApiClient;
        this.syncExecutionLogService = syncExecutionLogService;
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

    /** sync_execution_log 기반 집계 통계 */
    @GetMapping("/api/sync-stats")
    @ResponseBody
    public Map<String, Object> syncStats(@RequestParam(defaultValue = "today") String period) {
        return syncExecutionLogService.getStats(period);
    }

    /** KRAS / KOREPS estateGateway 연결 상태 확인 — 두 게이트웨이 병렬 조회 */
    @GetMapping("/api/gateway-status")
    @ResponseBody
    public Map<String, Object> gatewayStatus() {
        String chkPnu = settings.krasChkPnu();
        CompletableFuture<Map<String, Object>> krasFuture =
                CompletableFuture.supplyAsync(krasApiClient::testConnection);
        CompletableFuture<Map<String, Object>> korepsFuture =
                CompletableFuture.supplyAsync(() -> korepsApiClient.testConnection(chkPnu));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kras",   krasFuture.join());
        result.put("koreps", korepsFuture.join());
        return result;
    }
}
