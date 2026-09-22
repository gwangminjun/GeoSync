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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * kras 스키마 신규 적재 화면(/kras-db). 기존 schedule.html/SyncController와 코드 레벨로 분리된
 * 새 컨트롤러다 — 기존 KrasWorker/SyncScheduler는 건드리지 않는다.
 * 설계: docs/superpowers/specs/2026-09-18-kras-ingest-implementation-design.md §5.
 *
 * PNU 단건 매퍼(land_info, land_bldg_check, ...)는 서비스가 늘어날 때마다 컨트롤러에 필드/엔드포인트
 * 쌍을 복붙하지 않도록 {@link KrasXmlServiceMapper} 구현체를 Spring이 전부 주입받아 레지스트리로
 * 관리한다 — 새 매퍼는 IMPLEMENTED_SERVICES에 한 줄만 추가하면 된다.
 */
@Controller
public class KrasSchemaController {

    private static final Logger log = LoggerFactory.getLogger(KrasSchemaController.class);

    private static final String LAYER_CODE = "LP_PA_CBND";
    private static final String CADASTRAL_TGT_TABLE = "ods.lp_pa_cbnd";

    /**
     * URL/템플릿에 쓰는 slug(하이픈) → dataset_code(언더스코어) → 모델 속성 접두어(camelCase).
     * 접두어는 landInfoStatus/landInfoEnabled/landInfoRunning/lastLandInfoResult 형태로 조립된다 —
     * 기존 템플릿 변수명과 그대로 맞아 템플릿은 안 건드려도 된다.
     */
    private record ImplementedService(String slug, String datasetCode, String modelPrefix) {}

    private static final List<ImplementedService> IMPLEMENTED_SERVICES = List.of(
        new ImplementedService("land-info", "land_info", "landInfo"),
        new ImplementedService("land-bldg-check", "land_bldg_check", "landBldgCheck"),
        new ImplementedService("collective-building", "collective_building", "collectiveBuilding"),
        new ImplementedService("shr-ymb", "shr_ymb", "shrYmb"),
        new ImplementedService("own-rgt-hist", "own_rgt_hist", "ownRgtHist"),
        new ImplementedService("land-mov-hist", "land_mov_hist", "landMovHist"),
        new ImplementedService("collective-unit", "collective_unit", "collectiveUnit"),
        new ImplementedService("land-right", "land_right", "landRight"),
        new ImplementedService("unit-ownership-history", "unit_ownership_history", "unitOwnershipHistory"),
        new ImplementedService("integrated-building", "integrated_building", "integratedBuilding"),
        new ImplementedService("building-image", "building_image", "buildingImage")
    );

    /** 기간(날짜 범위) 조회 서비스(§10~12 계열) — PNU 단건과 최상위 식별자가 달라 별도 레지스트리. */
    private record DateRangeService(String slug, String datasetCode, String modelPrefix) {}

    private static final List<DateRangeService> DATE_RANGE_SERVICES = List.of(
        new DateRangeService("land-change", "land_change", "landChange")
    );

    /**
     * PNU 단건 API 중 IMPLEMENTED_SERVICES를 제외한 14개. dataset_code/service_code/api-test 딥링크 id/
     * 승격 대상 업무 테이블은 kras.business_dataset(설계 원본)과 api-test.html의 APIS 목록에서 그대로
     * 가져온 것 — 이 14개는 아직 실제 파싱·적재 매퍼가 없고, 이 표는 검증 화면 안내용이다.
     */
    private record PnuApiInfo(String datasetCode, String serviceCode, String apiTestId, String businessTables) {}

    private static final List<PnuApiInfo> PNU_APIS = List.of(
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
    private static final Set<String> VERIFIABLE_DATASETS = Stream.of(
            Stream.of("cadastral_file", "layer_list", "usezone_file"),
            IMPLEMENTED_SERVICES.stream().map(ImplementedService::datasetCode),
            DATE_RANGE_SERVICES.stream().map(DateRangeService::datasetCode),
            PNU_APIS.stream().map(PnuApiInfo::datasetCode)
    ).flatMap(s -> s).collect(Collectors.toUnmodifiableSet());

    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;
    private final KrasApiClient krasApiClient;
    private final KrasWorkspaceScanner workspaceScanner;
    private final TableMapper tableMapper;
    private final KrasCadastralIngestService cadastralIngestService;
    private final KrasPnuIngestService pnuIngestService;
    private final KrasDateRangeIngestService dateRangeIngestService;
    private final KrasUsezoneIngestService usezoneIngestService;
    private final Map<String, String> slugToDatasetCode;
    private final Map<String, KrasXmlServiceMapper> mappersByDatasetCode;
    private final Map<String, String> dateRangeSlugToDatasetCode;
    private final Map<String, KrasDateRangeServiceMapper> dateRangeMappersByDatasetCode;

    /** 기존 SyncScheduler의 krasLoadRunning과 별개 — 신규 탭 전용 실행 상태(design §5.2). */
    private final AtomicBoolean cadastralRunning = new AtomicBoolean(false);
    private volatile String lastCadastralResult;
    private final Map<String, AtomicBoolean> runningByDatasetCode = new ConcurrentHashMap<>();
    private final Map<String, String> lastResultByDatasetCode = new ConcurrentHashMap<>();

    private final AtomicBoolean usezoneRunning = new AtomicBoolean(false);
    private volatile String lastUsezoneResult;

    public KrasSchemaController(TargetDbService targetDbService, RuntimeSettingsService settings,
                                 KrasApiClient krasApiClient, KrasWorkspaceScanner workspaceScanner,
                                 TableMapper tableMapper, KrasCadastralIngestService cadastralIngestService,
                                 KrasPnuIngestService pnuIngestService, List<KrasXmlServiceMapper> mappers,
                                 KrasDateRangeIngestService dateRangeIngestService,
                                 List<KrasDateRangeServiceMapper> dateRangeMappers,
                                 KrasUsezoneIngestService usezoneIngestService) {
        this.targetDbService = targetDbService;
        this.settings = settings;
        this.krasApiClient = krasApiClient;
        this.workspaceScanner = workspaceScanner;
        this.tableMapper = tableMapper;
        this.cadastralIngestService = cadastralIngestService;
        this.pnuIngestService = pnuIngestService;
        this.dateRangeIngestService = dateRangeIngestService;
        this.usezoneIngestService = usezoneIngestService;
        this.mappersByDatasetCode = mappers.stream()
                .collect(Collectors.toUnmodifiableMap(KrasXmlServiceMapper::datasetCode, m -> m));
        this.slugToDatasetCode = IMPLEMENTED_SERVICES.stream()
                .collect(Collectors.toUnmodifiableMap(ImplementedService::slug, ImplementedService::datasetCode));
        this.dateRangeMappersByDatasetCode = dateRangeMappers.stream()
                .collect(Collectors.toUnmodifiableMap(KrasDateRangeServiceMapper::datasetCode, m -> m));
        this.dateRangeSlugToDatasetCode = DATE_RANGE_SERVICES.stream()
                .collect(Collectors.toUnmodifiableMap(DateRangeService::slug, DateRangeService::datasetCode));
        for (ImplementedService svc : IMPLEMENTED_SERVICES) {
            runningByDatasetCode.put(svc.datasetCode(), new AtomicBoolean(false));
        }
        for (DateRangeService svc : DATE_RANGE_SERVICES) {
            runningByDatasetCode.put(svc.datasetCode(), new AtomicBoolean(false));
        }
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

        Map<String, Map<String, Object>> statusByCode = batchDatasetStatus(jdbc,
                IMPLEMENTED_SERVICES.stream().map(ImplementedService::datasetCode).toList());
        for (ImplementedService svc : IMPLEMENTED_SERVICES) {
            Map<String, Object> st = statusByCode.get(svc.datasetCode());
            model.addAttribute(svc.modelPrefix() + "Status", st != null ? st.get("contract_status") : "UNVERIFIED");
            model.addAttribute(svc.modelPrefix() + "Enabled", st != null && Boolean.TRUE.equals(st.get("enabled")));
            model.addAttribute(svc.modelPrefix() + "Running", runningByDatasetCode.get(svc.datasetCode()).get());
            model.addAttribute("last" + capitalize(svc.modelPrefix()) + "Result",
                    lastResultByDatasetCode.get(svc.datasetCode()));
        }

        Map<String, Map<String, Object>> rangeStatusByCode = batchDatasetStatus(jdbc,
                DATE_RANGE_SERVICES.stream().map(DateRangeService::datasetCode).toList());
        for (DateRangeService svc : DATE_RANGE_SERVICES) {
            Map<String, Object> st = rangeStatusByCode.get(svc.datasetCode());
            model.addAttribute(svc.modelPrefix() + "Status", st != null ? st.get("contract_status") : "UNVERIFIED");
            model.addAttribute(svc.modelPrefix() + "Enabled", st != null && Boolean.TRUE.equals(st.get("enabled")));
            model.addAttribute(svc.modelPrefix() + "Running", runningByDatasetCode.get(svc.datasetCode()).get());
            model.addAttribute("last" + capitalize(svc.modelPrefix()) + "Result",
                    lastResultByDatasetCode.get(svc.datasetCode()));
        }

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

        // 용도지역(usezone_file) — layer_list/usezone_file 두 데이터셋 다 계약 검증 필요(guard_item_transition).
        Map<String, Map<String, Object>> usezoneDatasetStatus = batchDatasetStatus(jdbc,
                List.of("layer_list", "usezone_file"));
        Map<String, Object> layerListSt = usezoneDatasetStatus.get("layer_list");
        model.addAttribute("layerListStatus", layerListSt != null ? layerListSt.get("contract_status") : "UNVERIFIED");
        model.addAttribute("layerListEnabled", layerListSt != null && Boolean.TRUE.equals(layerListSt.get("enabled")));
        Map<String, Object> usezoneFileSt = usezoneDatasetStatus.get("usezone_file");
        model.addAttribute("usezoneFileStatus", usezoneFileSt != null ? usezoneFileSt.get("contract_status") : "UNVERIFIED");
        model.addAttribute("usezoneFileEnabled", usezoneFileSt != null && Boolean.TRUE.equals(usezoneFileSt.get("enabled")));
        model.addAttribute("usezoneRunning", usezoneRunning.get());
        model.addAttribute("lastUsezoneResult", lastUsezoneResult);

        List<Long> catalogItemIds = jdbc.query("""
            SELECT item_id FROM kras.sync_item
            WHERE org_cd=? AND dataset_code='layer_list' AND status='SUCCESS'
            ORDER BY item_id DESC LIMIT 1
            """, (rs, i) -> rs.getLong(1), settings.orgCode());
        model.addAttribute("latestCatalogItemId", catalogItemIds.isEmpty() ? null : catalogItemIds.get(0));

        List<Map<String, Object>> releases = jdbc.queryForList("""
            SELECT release_id, status, catalog_item_id FROM kras.spatial_release
            WHERE org_cd=? ORDER BY release_id DESC LIMIT 1
            """, settings.orgCode());
        if (!releases.isEmpty()) {
            Map<String, Object> release = releases.get(0);
            model.addAttribute("usezoneReleaseId", release.get("release_id"));
            model.addAttribute("usezoneReleaseStatus", release.get("status"));
            model.addAttribute("usezoneReleaseLayers", jdbc.queryForList("""
                SELECT e.layer_code, si.status, si.rows_valid
                FROM kras.spatial_release_expected e
                LEFT JOIN kras.spatial_release_member m ON m.release_id=e.release_id AND m.layer_code=e.layer_code
                LEFT JOIN kras.sync_item si ON si.item_id=m.item_id
                WHERE e.release_id=?
                ORDER BY e.layer_code
                """, release.get("release_id")));
        } else {
            model.addAttribute("usezoneReleaseId", null);
            model.addAttribute("usezoneReleaseStatus", null);
            model.addAttribute("usezoneReleaseLayers", List.of());
        }

        Long uzoneKrasCount = jdbc.queryForObject("SELECT count(*) FROM kras.lt_c_uzone", Long.class);
        Long uzonePublicCount = jdbc.queryForObject("SELECT count(*) FROM public.lt_c_uzone", Long.class);
        model.addAttribute("uzoneKrasCount", uzoneKrasCount);
        model.addAttribute("uzonePublicCount", uzonePublicCount);

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

    /** 용도지역 §9.1 절차 0 — KRAS 레이어 목록을 layer_list item + sync_record로 동결. */
    @PostMapping("/kras-db/usezone/collect-catalog")
    @ResponseBody
    public Map<String, Object> collectUsezoneCatalog() {
        if (!usezoneRunning.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        try {
            KrasUsezoneIngestService.CatalogResult result =
                    usezoneIngestService.collectCatalog(jdbc(), settings.orgCode(), "UI");
            String msg = "카탈로그 수집 성공: item_id=%d, %d개 레이어".formatted(result.itemId(), result.layerCodes().size());
            lastUsezoneResult = msg;
            return Map.of("success", true, "itemId", result.itemId(), "layerCount", result.layerCodes().size(),
                    "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] 용도지역 카탈로그 수집 실패: {}", e.getMessage(), e);
            lastUsezoneResult = "카탈로그 수집 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastUsezoneResult);
        } finally {
            usezoneRunning.set(false);
        }
    }

    /** 용도지역 §9.1 절차 1~4 — release 생성/seal + 레이어별 SHP 수집(레이어당 별도 트랜잭션, 부분 실패 허용). */
    @PostMapping("/kras-db/usezone/sweep")
    @ResponseBody
    public Map<String, Object> sweepUsezoneLayers(@RequestParam long catalogItemId) {
        if (!usezoneRunning.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        try {
            KrasUsezoneIngestService.SweepResult result =
                    usezoneIngestService.runLayerSweep(jdbc(), settings.orgCode(), catalogItemId, "UI");
            long okCount = result.layers().stream().filter(KrasUsezoneIngestService.LayerResult::success).count();
            String msg = "레이어 순회 완료: release_id=%d, %d/%d건 성공"
                    .formatted(result.releaseId(), okCount, result.layers().size());
            lastUsezoneResult = msg;
            return Map.of("success", true, "releaseId", result.releaseId(), "layers", result.layers(),
                    "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] 용도지역 레이어 순회 실패: {}", e.getMessage(), e);
            lastUsezoneResult = "레이어 순회 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastUsezoneResult);
        } finally {
            usezoneRunning.set(false);
        }
    }

    /** 용도지역 §9.1 절차 5 — publish_spatial_release()가 전체 완전성을 검증(부분 실패 시 여기서 막힘). */
    @PostMapping("/kras-db/usezone/publish")
    @ResponseBody
    public Map<String, Object> publishUsezoneRelease(@RequestParam long releaseId) {
        try {
            usezoneIngestService.publishRelease(jdbc(), releaseId);
            String msg = "release 발행 성공: release_id=" + releaseId;
            lastUsezoneResult = msg;
            return Map.of("success", true, "releaseId", releaseId, "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] 용도지역 release 발행 실패: {}", e.getMessage(), e);
            lastUsezoneResult = "release 발행 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastUsezoneResult);
        }
    }

    /** 용도지역 §9.1 절차 6 — 기존 kras.sync_public_usezone() 재사용, public.lt_c_uzone만 여기서 건드림. */
    @PostMapping("/kras-db/usezone/sync-public")
    @ResponseBody
    public Map<String, Object> syncUsezonePublic() {
        try {
            long n = usezoneIngestService.syncPublic(jdbc());
            String msg = "public 승격 성공: public.lt_c_uzone " + n + "건";
            lastUsezoneResult = msg;
            return Map.of("success", true, "rowCount", n, "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] 용도지역 public 승격 실패: {}", e.getMessage(), e);
            lastUsezoneResult = "public 승격 실패: " + e.getMessage();
            return Map.of("success", false, "message", lastUsezoneResult);
        }
    }

    /**
     * PNU 단건 매퍼 공용 테스트 수집(설계 절차 1~8). slug(land-info 등)는 IMPLEMENTED_SERVICES에
     * 등록된 것만 허용 — datasetCode는 이 slug로부터 서버에서 조회하고, 클라이언트가 임의 dataset_code로
     * 다른 매퍼를 부르지 못하게 한다. extraParamsJson은 PNU만으로 안 되는 드릴다운 서비스(collective_unit
     * 등)용 — 운영자가 직접 입력한 key:value를 그대로 KRAS 요청에 전달한다(파라미터명 추측 안 함).
     */
    @PostMapping("/kras-db/ingest/{slug}")
    @ResponseBody
    public Map<String, Object> ingestPnuMapper(@PathVariable String slug, @RequestParam String pnu,
                                                @RequestParam(required = false) String extraParamsJson) {
        String datasetCode = slugToDatasetCode.get(slug);
        if (datasetCode == null) {
            return Map.of("success", false, "message", "알 수 없는 서비스: " + slug);
        }
        Map<String, String> extraParams;
        try {
            extraParams = parseExtraParams(extraParamsJson);
        } catch (Exception e) {
            return Map.of("success", false, "message", "추가 파라미터 JSON 형식 오류: " + e.getMessage());
        }
        KrasXmlServiceMapper mapper = mappersByDatasetCode.get(datasetCode);
        AtomicBoolean running = runningByDatasetCode.get(datasetCode);
        if (!running.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        try {
            KrasPnuIngestService.IngestResult result =
                    pnuIngestService.ingest(jdbc(), settings.orgCode(), mapper, pnu, extraParams, "UI");
            String msg = result.promotable()
                    ? "수집 성공: item_id=%d — 값을 확인한 뒤 승격하세요.".formatted(result.itemId())
                    : "수집됨(item_id=%d), 단 필드 파싱 경고로 승격 보류: %s".formatted(result.itemId(), result.warnings());
            lastResultByDatasetCode.put(datasetCode, msg);
            return Map.of("success", true, "itemId", result.itemId(), "promotable", result.promotable(),
                    "warnings", result.warnings(), "preview", result.preview(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] {} 수집 실패: {}", datasetCode, e.getMessage(), e);
            String msg = "수집 실패: " + e.getMessage();
            lastResultByDatasetCode.put(datasetCode, msg);
            return Map.of("success", false, "message", msg);
        } finally {
            running.set(false);
        }
    }

    /** PNU 단건 매퍼 공용 승격만(설계 절차 9). 승격 패턴(A/B/C)은 매퍼가 declare한 StagePromotionSpec이 결정한다. */
    @PostMapping("/kras-db/promote/{slug}")
    @ResponseBody
    public Map<String, Object> promotePnuMapper(@PathVariable String slug, @RequestParam long itemId) {
        String datasetCode = slugToDatasetCode.get(slug);
        if (datasetCode == null) {
            return Map.of("success", false, "message", "알 수 없는 서비스: " + slug);
        }
        KrasXmlServiceMapper mapper = mappersByDatasetCode.get(datasetCode);
        try {
            KrasPnuIngestService.PromoteResult result = pnuIngestService.promote(jdbc(), settings.orgCode(), mapper, itemId);
            String msg = "승격 성공: item_id=%d".formatted(result.itemId());
            lastResultByDatasetCode.put(datasetCode, msg);
            return Map.of("success", true, "itemId", result.itemId(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] {} 승격 실패: {}", datasetCode, e.getMessage(), e);
            String msg = "승격 실패: " + e.getMessage();
            lastResultByDatasetCode.put(datasetCode, msg);
            return Map.of("success", false, "message", msg);
        }
    }

    /**
     * 기간(날짜 범위) 매퍼 공용 테스트 수집(land_change 등). startDate/endDate는 YYYY-MM-DD.
     * extraParamsJson은 실제 KRAS 요청 파라미터(시작일/종료일 등 실제 필드명이 미확인이라 운영자가
     * 직접 채운다) — startDate/endDate 자체는 우리 쪽 window/scope_key 계산용으로 별도로 쓴다.
     */
    @PostMapping("/kras-db/ingest-range/{slug}")
    @ResponseBody
    public Map<String, Object> ingestDateRangeMapper(@PathVariable String slug,
                                                       @RequestParam String startDate,
                                                       @RequestParam String endDate,
                                                       @RequestParam(required = false) String extraParamsJson) {
        String datasetCode = dateRangeSlugToDatasetCode.get(slug);
        if (datasetCode == null) {
            return Map.of("success", false, "message", "알 수 없는 서비스: " + slug);
        }
        Map<String, String> extraParams;
        try {
            extraParams = parseExtraParams(extraParamsJson);
        } catch (Exception e) {
            return Map.of("success", false, "message", "추가 파라미터 JSON 형식 오류: " + e.getMessage());
        }
        KrasDateRangeServiceMapper mapper = dateRangeMappersByDatasetCode.get(datasetCode);
        AtomicBoolean running = runningByDatasetCode.get(datasetCode);
        if (!running.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            KrasDateRangeIngestService.IngestResult result =
                    dateRangeIngestService.ingest(jdbc(), settings.orgCode(), mapper, start, end, extraParams, "UI");
            String msg = result.promotable()
                    ? "수집 성공: item_id=%d(%d건) — 값을 확인한 뒤 승격하세요.".formatted(result.itemId(), result.preview().size())
                    : "수집됨(item_id=%d), 단 필드 파싱 경고로 승격 보류: %s".formatted(result.itemId(), result.warnings());
            lastResultByDatasetCode.put(datasetCode, msg);
            return Map.of("success", true, "itemId", result.itemId(), "promotable", result.promotable(),
                    "warnings", result.warnings(), "preview", result.preview(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] {} 수집 실패: {}", datasetCode, e.getMessage(), e);
            String msg = "수집 실패: " + e.getMessage();
            lastResultByDatasetCode.put(datasetCode, msg);
            return Map.of("success", false, "message", msg);
        } finally {
            running.set(false);
        }
    }

    /** 기간(날짜 범위) 매퍼 공용 승격만. */
    @PostMapping("/kras-db/promote-range/{slug}")
    @ResponseBody
    public Map<String, Object> promoteDateRangeMapper(@PathVariable String slug, @RequestParam long itemId) {
        String datasetCode = dateRangeSlugToDatasetCode.get(slug);
        if (datasetCode == null) {
            return Map.of("success", false, "message", "알 수 없는 서비스: " + slug);
        }
        KrasDateRangeServiceMapper mapper = dateRangeMappersByDatasetCode.get(datasetCode);
        try {
            KrasDateRangeIngestService.PromoteResult result =
                    dateRangeIngestService.promote(jdbc(), settings.orgCode(), mapper, itemId);
            String msg = "승격 성공: item_id=%d".formatted(result.itemId());
            lastResultByDatasetCode.put(datasetCode, msg);
            return Map.of("success", true, "itemId", result.itemId(), "message", msg);
        } catch (Exception e) {
            log.error("[KrasSchema] {} 승격 실패: {}", datasetCode, e.getMessage(), e);
            String msg = "승격 실패: " + e.getMessage();
            lastResultByDatasetCode.put(datasetCode, msg);
            return Map.of("success", false, "message", msg);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper EXTRA_PARAMS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** {"cbldg_seqno":"0285"} 형태의 JSON 문자열을 Map으로 파싱한다. 비어있으면 빈 Map. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> parseExtraParams(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return EXTRA_PARAMS_MAPPER.readValue(json, Map.class);
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** 주어진 dataset_code들의 contract_status/enabled를 한 번에 조회한다. */
    private Map<String, Map<String, Object>> batchDatasetStatus(JdbcTemplate jdbc, List<String> codes) {
        String placeholders = String.join(",", codes.stream().map(c -> "?").toList());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dataset_code, contract_status, enabled FROM kras.sync_dataset WHERE dataset_code IN (" + placeholders + ")",
                codes.toArray());
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byCode.put((String) row.get("dataset_code"), row);
        }
        return byCode;
    }

    /** PNU_APIS 14개의 현재 contract_status/enabled를 한 번에 조회해 템플릿용 행으로 합친다. */
    private List<Map<String, Object>> loadPnuDatasetRows(JdbcTemplate jdbc) {
        Map<String, Map<String, Object>> statusByCode = batchDatasetStatus(jdbc,
                PNU_APIS.stream().map(PnuApiInfo::datasetCode).toList());

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
