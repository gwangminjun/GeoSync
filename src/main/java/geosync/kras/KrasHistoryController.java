package geosync.kras;

import geosync.database.TargetDbService;
import geosync.settings.RuntimeSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/kras-db")
public class KrasHistoryController {
    private final TargetDbService targets;
    private final RuntimeSettingsService settings;
    private final KrasHistoryService history;

    public KrasHistoryController(TargetDbService targets, RuntimeSettingsService settings, KrasHistoryService history) {
        this.targets = targets;
        this.settings = settings;
        this.history = history;
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "") String dataset,
                                      @RequestParam(defaultValue = "50") int limit) {
        return history.history(targets.getConfiguredTargets().get(0).jdbc(), settings.orgCode(), dataset, limit);
    }

    @GetMapping("/items/{itemId}")
    public Map<String, Object> preview(@PathVariable long itemId) {
        try {
            return history.preview(targets.getConfiguredTargets().get(0).jdbc(), settings.orgCode(), itemId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/readiness")
    public Map<String, Object> readiness(@RequestParam String dataset) {
        var reasons = history.readiness(targets.getConfiguredTargets().get(0).jdbc(), dataset);
        return Map.of("ready", reasons.isEmpty(), "reasons", reasons);
    }
}
