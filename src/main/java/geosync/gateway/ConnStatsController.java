package geosync.gateway;

import geosync.settings.RuntimeSettingsService;
import geosync.synchronization.SyncStatusService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

/**
 * kras conn API(/conn/*, /svc/*) 요청 통계 화면·데이터.
 */
@Controller
@RequestMapping("/conn-stats")
public class ConnStatsController {

    private final ConnRequestLogService connLog;
    private final RuntimeSettingsService settings;
    private final SyncStatusService statusService;

    public ConnStatsController(ConnRequestLogService connLog,
                               RuntimeSettingsService settings,
                               SyncStatusService statusService) {
        this.connLog = connLog;
        this.settings = settings;
        this.statusService = statusService;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("currentPage", "conn-stats");
        model.addAttribute("orgCode", settings.orgCode());
        model.addAttribute("krasRunning", statusService.isRunning("KRAS"));
        model.addAttribute("tableReady", connLog.tableExists());
        return "conn-stats";
    }

    @GetMapping(value = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> data(@RequestParam(defaultValue = "7d") String period) {
        return connLog.getStats(period);
    }

    @GetMapping(value = "/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "50") int limit) {
        return connLog.recent(Math.min(limit, 500));
    }
}
