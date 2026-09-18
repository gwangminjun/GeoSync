package geosync.kras;

import geosync.database.TargetDbService;
import geosync.settings.RuntimeSettingsService;
import geosync.synchronization.TableMapper;
import geosync.synchronization.model.SyncTableDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * kras 스키마 신규 적재 화면(/kras-db). 기존 schedule.html/SyncController와 코드 레벨로 분리된
 * 새 컨트롤러다 — 기존 KrasWorker/SyncScheduler는 건드리지 않는다.
 * 설계: docs/superpowers/specs/2026-09-18-kras-ingest-implementation-design.md §5.
 */
@Controller
public class KrasSchemaController {

    private static final Logger log = LoggerFactory.getLogger(KrasSchemaController.class);

    private static final String LAYER_CODE = "LP_PA_CBND";
    private static final String CADASTRAL_TGT_TABLE = "ods.lp_pa_cbnd";

    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;
    private final KrasApiClient krasApiClient;
    private final KrasWorkspaceScanner workspaceScanner;
    private final TableMapper tableMapper;
    private final KrasCadastralIngestService cadastralIngestService;

    /** 기존 SyncScheduler의 krasLoadRunning과 별개 — 신규 탭 전용 실행 상태(design §5.2). */
    private final AtomicBoolean cadastralRunning = new AtomicBoolean(false);
    private volatile String lastCadastralResult;

    public KrasSchemaController(TargetDbService targetDbService, RuntimeSettingsService settings,
                                 KrasApiClient krasApiClient, KrasWorkspaceScanner workspaceScanner,
                                 TableMapper tableMapper, KrasCadastralIngestService cadastralIngestService) {
        this.targetDbService = targetDbService;
        this.settings = settings;
        this.krasApiClient = krasApiClient;
        this.workspaceScanner = workspaceScanner;
        this.tableMapper = tableMapper;
        this.cadastralIngestService = cadastralIngestService;
    }

    @GetMapping("/kras-db")
    public String page(Model model) {
        JdbcTemplate jdbc = jdbc();
        model.addAttribute("currentPage", "kras-db");
        model.addAttribute("orgCode", settings.orgCode());
        model.addAttribute("ingestRunning", cadastralRunning.get());
        model.addAttribute("lastResult", lastCadastralResult);

        Map<String, Object> dataset = jdbc.queryForMap("""
            SELECT contract_status, enabled FROM kras.sync_dataset WHERE dataset_code='cadastral_file'
            """);
        model.addAttribute("datasetStatus", dataset.get("contract_status"));
        model.addAttribute("datasetEnabled", Boolean.TRUE.equals(dataset.get("enabled")));

        Long krasCount = jdbc.queryForObject("SELECT count(*) FROM kras.lp_pa_cbnd", Long.class);
        Long publicCount = jdbc.queryForObject("SELECT count(*) FROM public.lp_pa_cbnd", Long.class);
        model.addAttribute("krasCount", krasCount);
        model.addAttribute("publicCount", publicCount);

        List<Map<String, Object>> recentRuns = jdbc.queryForList("""
            SELECT si.item_id, si.status, si.rows_valid, si.rows_rejected, sr.started_at
            FROM kras.sync_item si JOIN kras.sync_run sr ON sr.run_id = si.run_id
            WHERE si.dataset_code='cadastral_file' AND si.org_cd=?
            ORDER BY si.item_id DESC LIMIT 20
            """, settings.orgCode());
        model.addAttribute("recentRuns", recentRuns);

        return "kras-db";
    }

    /**
     * cadastral_file 데이터셋을 VERIFIED+enabled로 전환한다.
     * 이 버튼은 "운영 응답으로 계약을 실제 확인했다"는 사람의 선언을 DB에 반영만 한다 —
     * 검증 자체를 이 버튼이 대신하지 않는다(design §5.3, §14-1).
     */
    @PostMapping("/kras-db/verify-dataset")
    public String verifyDataset(@RequestParam(defaultValue = "false") boolean confirmed,
                                 RedirectAttributes ra) {
        if (!confirmed) {
            ra.addFlashAttribute("message", "확인 체크박스를 선택해야 활성화할 수 있습니다.");
            return "redirect:/kras-db";
        }
        jdbc().update("""
            UPDATE kras.sync_dataset SET contract_status='VERIFIED', enabled=true
            WHERE dataset_code='cadastral_file'
            """);
        ra.addFlashAttribute("message", "cadastral_file 데이터셋을 VERIFIED로 전환했습니다.");
        return "redirect:/kras-db";
    }

    /** 수집만(설계 절차 1~6). SHP를 새로 다운로드해 kras 스키마에 적재한다 — public 테이블은 안 건드림. */
    @PostMapping("/kras-db/ingest/cadastral")
    @ResponseBody
    public Map<String, Object> ingestCadastral() {
        if (!cadastralRunning.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        try {
            SyncTableDef def = findCadastralDef();
            Path workDir = Path.of(settings.krasWorkDir(), settings.orgCode());
            String baseName = krasApiClient.downloadLayer(def.srcTableName, workDir);
            Path shpPath = workDir.resolve(baseName + ".shp");
            List<Map<String, Object>> rows = workspaceScanner.loadShpFile(shpPath, def);

            KrasCadastralIngestService.IngestResult result =
                    cadastralIngestService.ingest(jdbc(), settings.orgCode(), LAYER_CODE, rows, "UI");

            String msg = "수집 성공: item_id=%d, %d건 적재(도형 없음 %d건 제외)"
                    .formatted(result.itemId(), result.rowCount(), result.skippedCount());
            lastCadastralResult = msg;
            return Map.of("success", true, "itemId", result.itemId(), "rowCount", result.rowCount(),
                    "skippedCount", result.skippedCount(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] 연속지적 수집 실패: {}", e.getMessage(), e);
            lastCadastralResult = "수집 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastCadastralResult);
        } finally {
            cadastralRunning.set(false);
        }
    }

    /** 승격만(설계 절차 7~8). 실제 GeoServer 테이블(public.lp_pa_cbnd)을 여기서만 건드린다. */
    @PostMapping("/kras-db/promote/cadastral")
    @ResponseBody
    public Map<String, Object> promoteCadastral() {
        try {
            KrasCadastralIngestService.PromoteResult result =
                    cadastralIngestService.promote(jdbc(), settings.orgCode(), LAYER_CODE);
            String msg = "승격 성공: item_id=%d, public.lp_pa_cbnd %d건"
                    .formatted(result.itemId(), result.promotedRows());
            lastCadastralResult = msg;
            return Map.of("success", true, "itemId", result.itemId(),
                    "promotedRows", result.promotedRows(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] 연속지적 승격 실패: {}", e.getMessage(), e);
            lastCadastralResult = "승격 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastCadastralResult);
        }
    }

    private SyncTableDef findCadastralDef() {
        return tableMapper.load(settings.krasConfig()).stream()
                .filter(d -> CADASTRAL_TGT_TABLE.equals(d.tgtTableName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "base-tables.xml에 " + CADASTRAL_TGT_TABLE + " 정의가 없습니다."));
    }

    private JdbcTemplate jdbc() {
        return targetDbService.getConfiguredTargets().get(0).jdbc();
    }
}
