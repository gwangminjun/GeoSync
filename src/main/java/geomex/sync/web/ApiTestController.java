package geomex.sync.web;

import geomex.sync.service.RuntimeSettingsService;
import geomex.sync.service.SyncStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ApiTestController {

    private final RuntimeSettingsService settings;
    private final SyncStatusService statusService;
    private final boolean mockGatewayEnabled;
    private final String mockGatewayUrl;

    public ApiTestController(RuntimeSettingsService settings, SyncStatusService statusService,
                             @Value("${mock.gateway.enabled:false}") boolean mockGatewayEnabled,
                             @Value("${server.port:18080}") String serverPort) {
        this.settings = settings;
        this.statusService = statusService;
        this.mockGatewayEnabled = mockGatewayEnabled;
        this.mockGatewayUrl = "http://localhost:" + serverPort + "/mock/estateGateway";
    }

    @GetMapping("/api-test")
    public String apiTest(Model model) {
        model.addAttribute("currentPage", "api-test");
        model.addAttribute("orgCode", settings.orgCode());
        model.addAttribute("krasRunning", statusService.isRunning("KRAS"));
        model.addAttribute("chkPnu", settings.krasChkPnu());
        model.addAttribute("krasUrl", settings.krasUrl());
        model.addAttribute("krasConnSysId", settings.krasConnSysId());
        model.addAttribute("korepsUrl", settings.korepsUrl());
        model.addAttribute("korepsConnSysId", settings.korepsConnSysId());
        model.addAttribute("mockGatewayEnabled", mockGatewayEnabled);
        model.addAttribute("mockGatewayUrl", mockGatewayEnabled ? mockGatewayUrl : "");
        return "api-test";
    }
}
