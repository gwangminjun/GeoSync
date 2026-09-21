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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /**
     * PNU 단건 API 중 land_info/land_bldg_check를 제외한 17개. dataset_code/service_code/api-test 딥링크 id/
     * 승격 대상 업무 테이블은 kras.business_dataset(설계 원본)과 api-test.html의 APIS 목록에서 그대로 가져온 것 —
     * 이 17개는 아직 실제 파싱·적재 매퍼가 없고, 이 표는 검증 화면 안내용이다.
     */
    private record PnuApiInfo(String datasetCode, String serviceCode, String apiTestId, String businessTables) {}

    private static final List<PnuApiInfo> PNU_APIS = List.of(
        new PnuApiInfo("shr_ymb", "KRAS000003", "conn/shr_ymb", "kras.land_share"),
        new PnuApiInfo("land_mov_hist", "KRAS000006", "conn/land_mov_hist", "kras.land_movement_history, kras.land_movement_relation"),
        new PnuApiInfo("own_rgt_hist", "KRAS000007", "conn/own_rgt_hist", "kras.land_ownership_history"),
        new PnuApiInfo("bldg_hds_info", "KRAS000014", "conn/bldg_hds_info", "kras.building_title, kras.building_floor, kras.building_title_owner, kras.building_title_change"),
        new PnuApiInfo("cbldg_hds_info", "KRAS000015", "conn/cbldg_hds_info", "kras.building_title"),
        new PnuApiInfo("cbldg_dfhs_info", "KRAS000016", "conn/cbldg_dfhs_info", "kras.building_exclusive, kras.building_exclusive_area, kras.building_exclusive_owner, kras.building_exclusive_price"),
        new PnuApiInfo("bldg_ledg_gen_hds_info", "KRAS000017", "conn/bldg_ledg_gen_hds_info", "kras.building_summary"),
        new PnuApiInfo("land_use_plan_attr", "KRAS000025", "conn/land_use_plan_attr", "kras.land_use_attribute"),
        new PnuApiInfo("land_use_plan_info", "KRAS000026", "conn/land_use_plan_info", "kras.land_use_plan, kras.land_use_restriction, kras.land_use_plan_asset"),
        new PnuApiInfo("use_zone", "KRAS000027", "conn/use_zone", "kras.land_use_zone"),
        new PnuApiInfo("bldg_dong_info", "KRAS000102", "conn/bldg_dong_info", "kras.building_register"),
        new PnuApiInfo("bldg_ho_info", "KRAS000103", "conn/bldg_ho_info", "kras.building_unit"),
        new PnuApiInfo("land_jiga", "KOREPS00011", "conn/land_jiga", "kras.koreps_land_price"),
        new PnuApiInfo("house_info", "KOREPS00033", "conn/house_info", "kras.house_price"),
        new PnuApiInfo("fin_dec_jiga", "KOREPS00034", "conn/fin_dec_jiga", "kras.final_land_price"),
        new PnuApiInfo("read_dec_jiga", "KOREPS00035", "conn/read_dec_jiga", "kras.read_land_price"),
        new PnuApiInfo("land_attr", "KOREPS00047", "conn/land_attr", "kras.land_attribute")
    );

    /** verify/revert-dataset이 건드릴 수 있는 dataset_code 화이트리스트 — 임의 문자열로 다른 데이터셋을 켜지 못하게 막는다. */
    private static final Set<String> VERIFIABLE_DATASETS = Stream.concat(
            Stream.of("cadastral_file", "land_info", "land_bldg_check", "collective_building"),
            PNU_APIS.stream().map(PnuApiInfo::datasetCode)
    ).collect(Collectors.toUnmodifiableSet());

    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;
    private final KrasApiClient krasApiClient;
    private final KrasWorkspaceScanner workspaceScanner;
    private final TableMapper tableMapper;
    private final KrasCadastralIngestService cadastralIngestService;
    private final KrasPnuIngestService pnuIngestService;
    private final LandInfoMapper landInfoMapper;
    private final LandBldgCheckMapper landBldgCheckMapper;
    private final CollectiveBuildingMapper collectiveBuildingMapper;

    /** 기존 SyncScheduler의 krasLoadRunning과 별개 — 신규 탭 전용 실행 상태(design §5.2). */
    private final AtomicBoolean cadastralRunning = new AtomicBoolean(false);
    private volatile String lastCadastralResult;
    private final AtomicBoolean landInfoRunning = new AtomicBoolean(false);
    private volatile String lastLandInfoResult;
    private final AtomicBoolean landBldgCheckRunning = new AtomicBoolean(false);
    private volatile String lastLandBldgCheckResult;
    private final AtomicBoolean collectiveBuildingRunning = new AtomicBoolean(false);
    private volatile String lastCollectiveBuildingResult;

    public KrasSchemaController(TargetDbService targetDbService, RuntimeSettingsService settings,
                                 KrasApiClient krasApiClient, KrasWorkspaceScanner workspaceScanner,
                                 TableMapper tableMapper, KrasCadastralIngestService cadastralIngestService,
                                 KrasPnuIngestService pnuIngestService, LandInfoMapper landInfoMapper,
                                 LandBldgCheckMapper landBldgCheckMapper,
                                 CollectiveBuildingMapper collectiveBuildingMapper) {
        this.targetDbService = targetDbService;
        this.settings = settings;
        this.krasApiClient = krasApiClient;
        this.workspaceScanner = workspaceScanner;
        this.tableMapper = tableMapper;
        this.cadastralIngestService = cadastralIngestService;
        this.pnuIngestService = pnuIngestService;
        this.landInfoMapper = landInfoMapper;
        this.landBldgCheckMapper = landBldgCheckMapper;
        this.collectiveBuildingMapper = collectiveBuildingMapper;
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

        Map<String, Object> landInfoDataset = jdbc.queryForMap("""
            SELECT contract_status, enabled FROM kras.sync_dataset WHERE dataset_code='land_info'
            """);
        model.addAttribute("landInfoStatus", landInfoDataset.get("contract_status"));
        model.addAttribute("landInfoEnabled", Boolean.TRUE.equals(landInfoDataset.get("enabled")));
        model.addAttribute("landInfoRunning", landInfoRunning.get());
        model.addAttribute("lastLandInfoResult", lastLandInfoResult);

        Map<String, Object> landBldgCheckDataset = jdbc.queryForMap("""
            SELECT contract_status, enabled FROM kras.sync_dataset WHERE dataset_code='land_bldg_check'
            """);
        model.addAttribute("landBldgCheckStatus", landBldgCheckDataset.get("contract_status"));
        model.addAttribute("landBldgCheckEnabled", Boolean.TRUE.equals(landBldgCheckDataset.get("enabled")));
        model.addAttribute("landBldgCheckRunning", landBldgCheckRunning.get());
        model.addAttribute("lastLandBldgCheckResult", lastLandBldgCheckResult);

        Map<String, Object> collectiveBuildingDataset = jdbc.queryForMap("""
            SELECT contract_status, enabled FROM kras.sync_dataset WHERE dataset_code='collective_building'
            """);
        model.addAttribute("collectiveBuildingStatus", collectiveBuildingDataset.get("contract_status"));
        model.addAttribute("collectiveBuildingEnabled", Boolean.TRUE.equals(collectiveBuildingDataset.get("enabled")));
        model.addAttribute("collectiveBuildingRunning", collectiveBuildingRunning.get());
        model.addAttribute("lastCollectiveBuildingResult", lastCollectiveBuildingResult);

        model.addAttribute("pnuDatasets", loadPnuDatasetRows(jdbc));

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
     * 지정한 dataset_code를 VERIFIED+enabled로 전환한다. 데이터셋은 한 번에 하나씩만 전환한다 —
     * 33개를 일괄 전환하지 않는다(각 API 응답이 문서와 실제로 일치하는지는 데이터셋마다 따로 확인해야 함).
     * 이 버튼은 "운영 응답으로 계약을 실제 확인했다"는 사람의 선언을 DB에 반영만 한다 —
     * 검증 자체를 이 버튼이 대신하지 않는다(design §5.3, §14-1).
     */
    @PostMapping("/kras-db/verify-dataset")
    public String verifyDataset(@RequestParam String datasetCode,
                                 @RequestParam(defaultValue = "false") boolean confirmed,
                                 RedirectAttributes ra) {
        if (!VERIFIABLE_DATASETS.contains(datasetCode)) {
            ra.addFlashAttribute("message", "알 수 없는 dataset_code입니다: " + datasetCode);
            return "redirect:/kras-db";
        }
        if (!confirmed) {
            ra.addFlashAttribute("message", "확인 체크박스를 선택해야 활성화할 수 있습니다.");
            return "redirect:/kras-db";
        }
        jdbc().update("""
            UPDATE kras.sync_dataset SET contract_status='VERIFIED', enabled=true
            WHERE dataset_code=?
            """, datasetCode);
        ra.addFlashAttribute("message", datasetCode + " 데이터셋을 VERIFIED로 전환했습니다.");
        return "redirect:/kras-db";
    }

    /**
     * VERIFIED로 전환했던 dataset_code를 UNVERIFIED+disabled로 되돌린다.
     * 이미 SUCCESS로 확정된 sync_item은 guard_item_transition이 불변으로 막아주므로
     * 원복해도 과거 적재 결과는 건드리지 않는다 — 앞으로의 신규 수집만 다시 막힌다.
     */
    @PostMapping("/kras-db/revert-dataset")
    public String revertDataset(@RequestParam String datasetCode, RedirectAttributes ra) {
        if (!VERIFIABLE_DATASETS.contains(datasetCode)) {
            ra.addFlashAttribute("message", "알 수 없는 dataset_code입니다: " + datasetCode);
            return "redirect:/kras-db";
        }
        jdbc().update("""
            UPDATE kras.sync_dataset SET contract_status='UNVERIFIED', enabled=false
            WHERE dataset_code=?
            """, datasetCode);
        ra.addFlashAttribute("message", datasetCode + " 데이터셋 검증을 원복했습니다 (UNVERIFIED · disabled).");
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

    /**
     * PNU 테스트 수집(설계 절차 1~8). 실제 KRAS 응답을 받아 kras.stage_* 3개 테이블에 적재한다.
     * 필드 파싱 경고가 하나라도 있으면 item을 SUCCESS로 올리지 않는다 — 승격 버튼은 그때 비활성 상태로 둔다.
     */
    @PostMapping("/kras-db/ingest/land-info")
    @ResponseBody
    public Map<String, Object> ingestLandInfo(@RequestParam String pnu) {
        if (!landInfoRunning.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        try {
            KrasPnuIngestService.IngestResult result =
                    pnuIngestService.ingest(jdbc(), settings.orgCode(), landInfoMapper, pnu, "UI");
            String msg = result.promotable()
                    ? "수집 성공: item_id=%d — 값을 확인한 뒤 승격하세요.".formatted(result.itemId())
                    : "수집됨(item_id=%d), 단 필드 파싱 경고로 승격 보류: %s".formatted(result.itemId(), result.warnings());
            lastLandInfoResult = msg;
            return Map.of("success", true, "itemId", result.itemId(), "promotable", result.promotable(),
                    "warnings", result.warnings(), "preview", result.preview(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] land_info 수집 실패: {}", e.getMessage(), e);
            lastLandInfoResult = "수집 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastLandInfoResult);
        } finally {
            landInfoRunning.set(false);
        }
    }

    /** land_info 승격만(설계 절차 9). kras.parcel/land_register/land_owner를 pnu 자연키로 upsert한다. */
    @PostMapping("/kras-db/promote/land-info")
    @ResponseBody
    public Map<String, Object> promoteLandInfo(@RequestParam long itemId) {
        try {
            KrasPnuIngestService.PromoteResult result =
                    pnuIngestService.promote(jdbc(), settings.orgCode(), landInfoMapper, itemId);
            String msg = "승격 성공: item_id=%d".formatted(result.itemId());
            lastLandInfoResult = msg;
            return Map.of("success", true, "itemId", result.itemId(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] land_info 승격 실패: {}", e.getMessage(), e);
            lastLandInfoResult = "승격 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastLandInfoResult);
        }
    }

    /** land_bldg_check 테스트 수집(설계 절차 1~8). 날짜/숫자 필드가 없어 파싱 경고가 발생하지 않는다. */
    @PostMapping("/kras-db/ingest/land-bldg-check")
    @ResponseBody
    public Map<String, Object> ingestLandBldgCheck(@RequestParam String pnu) {
        if (!landBldgCheckRunning.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        try {
            KrasPnuIngestService.IngestResult result =
                    pnuIngestService.ingest(jdbc(), settings.orgCode(), landBldgCheckMapper, pnu, "UI");
            String msg = "수집 성공: item_id=%d — 값을 확인한 뒤 승격하세요.".formatted(result.itemId());
            lastLandBldgCheckResult = msg;
            return Map.of("success", true, "itemId", result.itemId(), "promotable", result.promotable(),
                    "warnings", result.warnings(), "preview", result.preview(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] land_bldg_check 수집 실패: {}", e.getMessage(), e);
            lastLandBldgCheckResult = "수집 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastLandBldgCheckResult);
        } finally {
            landBldgCheckRunning.set(false);
        }
    }

    /** land_bldg_check 승격만(설계 절차 9). kras.parcel/land_presence를 pnu 자연키로 upsert한다. */
    @PostMapping("/kras-db/promote/land-bldg-check")
    @ResponseBody
    public Map<String, Object> promoteLandBldgCheck(@RequestParam long itemId) {
        try {
            KrasPnuIngestService.PromoteResult result =
                    pnuIngestService.promote(jdbc(), settings.orgCode(), landBldgCheckMapper, itemId);
            String msg = "승격 성공: item_id=%d".formatted(result.itemId());
            lastLandBldgCheckResult = msg;
            return Map.of("success", true, "itemId", result.itemId(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] land_bldg_check 승격 실패: {}", e.getMessage(), e);
            lastLandBldgCheckResult = "승격 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastLandBldgCheckResult);
        }
    }

    /**
     * collective_building 테스트 수집(설계 절차 1~8). conn_svc_id가 미확인 상태라 지금은
     * CollectiveBuildingMapper.connSvcId()가 예외를 던져 여기서 바로 실패로 끝난다 — 값이 채워지면
     * 코드 변경 없이 바로 동작한다.
     */
    @PostMapping("/kras-db/ingest/collective-building")
    @ResponseBody
    public Map<String, Object> ingestCollectiveBuilding(@RequestParam String pnu) {
        if (!collectiveBuildingRunning.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        try {
            KrasPnuIngestService.IngestResult result =
                    pnuIngestService.ingest(jdbc(), settings.orgCode(), collectiveBuildingMapper, pnu, "UI");
            String msg = result.promotable()
                    ? "수집 성공: item_id=%d — 값을 확인한 뒤 승격하세요.".formatted(result.itemId())
                    : "수집됨(item_id=%d), 단 필드 파싱 경고로 승격 보류: %s".formatted(result.itemId(), result.warnings());
            lastCollectiveBuildingResult = msg;
            return Map.of("success", true, "itemId", result.itemId(), "promotable", result.promotable(),
                    "warnings", result.warnings(), "preview", result.preview(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] collective_building 수집 실패: {}", e.getMessage(), e);
            lastCollectiveBuildingResult = "수집 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastCollectiveBuildingResult);
        } finally {
            collectiveBuildingRunning.set(false);
        }
    }

    /** collective_building 승격만(설계 절차 9, 패턴 C — pnu+cbldg_seqno 신원 매칭). */
    @PostMapping("/kras-db/promote/collective-building")
    @ResponseBody
    public Map<String, Object> promoteCollectiveBuilding(@RequestParam long itemId) {
        try {
            KrasPnuIngestService.PromoteResult result =
                    pnuIngestService.promote(jdbc(), settings.orgCode(), collectiveBuildingMapper, itemId);
            String msg = "승격 성공: item_id=%d".formatted(result.itemId());
            lastCollectiveBuildingResult = msg;
            return Map.of("success", true, "itemId", result.itemId(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] collective_building 승격 실패: {}", e.getMessage(), e);
            lastCollectiveBuildingResult = "승격 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastCollectiveBuildingResult);
        }
    }

    /** PNU_APIS 17개의 현재 contract_status/enabled를 한 번에 조회해 템플릿용 행으로 합친다. */
    private List<Map<String, Object>> loadPnuDatasetRows(JdbcTemplate jdbc) {
        List<String> codes = PNU_APIS.stream().map(PnuApiInfo::datasetCode).toList();
        String placeholders = String.join(",", codes.stream().map(c -> "?").toList());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dataset_code, contract_status, enabled FROM kras.sync_dataset WHERE dataset_code IN (" + placeholders + ")",
                codes.toArray());
        Map<String, Map<String, Object>> statusByCode = new HashMap<>();
        for (Map<String, Object> row : rows) {
            statusByCode.put((String) row.get("dataset_code"), row);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (PnuApiInfo api : PNU_APIS) {
            Map<String, Object> status = statusByCode.get(api.datasetCode());
            Map<String, Object> out = new HashMap<>();
            out.put("datasetCode", api.datasetCode());
            out.put("serviceCode", api.serviceCode());
            out.put("apiTestId", api.apiTestId());
            out.put("businessTables", api.businessTables());
            out.put("status", status != null ? status.get("contract_status") : "UNVERIFIED");
            out.put("enabled", status != null && Boolean.TRUE.equals(status.get("enabled")));
            result.add(out);
        }
        return result;
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
