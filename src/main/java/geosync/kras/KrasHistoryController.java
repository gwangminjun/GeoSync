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
    private final KrasVerificationEvidenceService verificationEvidenceService;

    public KrasHistoryController(TargetDbService targets, RuntimeSettingsService settings, KrasHistoryService history,
                                  KrasVerificationEvidenceService verificationEvidenceService) {
        this.targets = targets;
        this.settings = settings;
        this.history = history;
        this.verificationEvidenceService = verificationEvidenceService;
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

    @GetMapping("/operations/{operationId}")
    public Map<String, Object> operation(@PathVariable long operationId) {
        try {
            return history.operation(targets.getConfiguredTargets().get(0).jdbc(), settings.orgCode(), operationId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 목록/상세 화면의 "마지막 검증: {일시}·{담당자}" 배지용 — dataset은 콤마로 여러 개 넘길 수 있다. */
    @GetMapping("/verification-evidence")
    public Map<String, Map<String, Object>> latestEvidence(@RequestParam String datasets) {
        var codes = java.util.Arrays.stream(datasets.split(",")).map(String::trim)
                .filter(s -> !s.isBlank()).toList();
        return verificationEvidenceService.latestByDataset(
                targets.getConfiguredTargets().get(0).jdbc(), settings.orgCode(), codes);
    }

    @GetMapping("/readiness")
    public Map<String, Object> readiness(@RequestParam String dataset) {
        var reasons = history.readiness(targets.getConfiguredTargets().get(0).jdbc(), dataset);
        return Map.of("ready", reasons.isEmpty(), "reasons", reasons);
    }
}
