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
 * 설계: docs/superpowers/specs/2026-09-18/2026-09-18-kras-ingest-implementation-design.md §5.
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
     * kras.md에 응답 규격이 없어 KrasSpecMapperConfig에 선언으로 구현한 14개.
     * conn_svc_id와 요청 파라미터는 GatewayPaths/KrasApiClient에서 확인된 값이고, 막힌 건 응답 구조뿐이다 —
     * 태그명은 업무 테이블 컬럼명에서 유추한 가설이라 실응답 확인 전에는 VERIFIED로 올리면 안 된다.
     *
     * @param needsBno  건물식별번호(bldg_gbn_no)가 있어야 호출되는 서비스인가(GatewayPaths.BLDG_GBN_NO_PATHS 기준).
     * @param dependsOn 이 서비스보다 먼저 승격돼 있어야 하는 데이터셋(FK/드릴다운 부모). 없으면 빈 문자열.
     */
    private record SpecService(String slug, String datasetCode, String serviceCode, String label,
                                String businessTables, boolean needsBno, String dependsOn) {}

    private static final List<SpecService> SPEC_SERVICES = List.of(
        new SpecService("use-zone", "use_zone", "KRAS000027", "용도지역지구",
            "kras.land_use_zone", false, ""),
        new SpecService("land-use-plan-attr", "land_use_plan_attr", "KRAS000025", "토지이용계획 속성",
            "kras.land_use_attribute", false, ""),
        new SpecService("land-use-plan-info", "land_use_plan_info", "KRAS000026", "토지이용계획 + 행위제한",
            "kras.land_use_plan, kras.land_use_restriction", false, ""),
        new SpecService("bldg-dong-info", "bldg_dong_info", "KRAS000102", "건물 동 정보 (건물 계열의 관문)",
            "kras.building_register", false, ""),
        new SpecService("bldg-ledg-gen-hds-info", "bldg_ledg_gen_hds_info", "KRAS000017", "건축물대장 총괄표제부",
            "kras.building_summary", false, ""),
        new SpecService("bldg-ho-info", "bldg_ho_info", "KRAS000103", "건물 호(전유) 정보",
            "kras.building_unit", true, ""),
        new SpecService("bldg-hds-info", "bldg_hds_info", "KRAS000014", "건축물대장 표제부 + 층별/소유자/변동",
            "kras.building_title, kras.building_floor, kras.building_title_owner, kras.building_title_change",
            true, "bldg_dong_info"),
        new SpecService("cbldg-hds-info", "cbldg_hds_info", "KRAS000015", "집합건물 표제부",
            "kras.building_title", true, "bldg_dong_info"),
        new SpecService("cbldg-dfhs-info", "cbldg_dfhs_info", "KRAS000016", "집합건물 전유부 + 면적/소유자/가격",
            "kras.building_exclusive, kras.building_exclusive_area, kras.building_exclusive_owner, kras.building_exclusive_price",
            true, "bldg_ho_info"),
        new SpecService("land-jiga", "land_jiga", "KOREPS00011", "공시지가 (KOREPS)",
            "kras.koreps_land_price", false, ""),
        new SpecService("house-info", "house_info", "KOREPS00033", "개별주택가격 (KOREPS)",
            "kras.house_price", false, ""),
        new SpecService("fin-dec-jiga", "fin_dec_jiga", "KOREPS00034", "공시결정지가 (KOREPS)",
            "kras.final_land_price", false, ""),
        new SpecService("read-dec-jiga", "read_dec_jiga", "KOREPS00035", "열람결정지가 (KOREPS)",
            "kras.read_land_price", false, ""),
        new SpecService("land-attr", "land_attr", "KOREPS00047", "토지속성 (KOREPS)",
            "kras.land_attribute", false, "")
    );

    /** verify/revert-dataset이 건드릴 수 있는 dataset_code 화이트리스트 — 임의 문자열로 다른 데이터셋을 켜지 못하게 막는다. */
    private static final Set<String> VERIFIABLE_DATASETS = Stream.of(
            Stream.of("cadastral_file", "layer_list", "usezone_file", "land_basic_file", "land_price_file"),
            IMPLEMENTED_SERVICES.stream().map(ImplementedService::datasetCode),
            DATE_RANGE_SERVICES.stream().map(DateRangeService::datasetCode),
            SPEC_SERVICES.stream().map(SpecService::datasetCode)
    ).flatMap(s -> s).collect(Collectors.toUnmodifiableSet());

    private final KrasOperationLogService operationLog;
    private final KrasHistoryService historyService;
    private final Map<String, AtomicBoolean> operationLocks = new ConcurrentHashMap<>();
    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;
    private final KrasApiClient krasApiClient;
    private final KrasWorkspaceScanner workspaceScanner;
    private final TableMapper tableMapper;
    private final KrasCadastralIngestService cadastralIngestService;
    private final KrasPnuIngestService pnuIngestService;
    private final KrasDateRangeIngestService dateRangeIngestService;
    private final KrasUsezoneIngestService usezoneIngestService;
    private final KrasTxtIngestService txtIngestService;
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

    /** 전체 TXT 2종 — 파일 하나를 통째로 받는 작업이라 데이터셋별로 실행 상태를 따로 잡는다. */
    private final AtomicBoolean landBasicFileRunning = new AtomicBoolean(false);
    private volatile String lastLandBasicFileResult;
    private final AtomicBoolean landPriceFileRunning = new AtomicBoolean(false);
    private volatile String lastLandPriceFileResult;

    public KrasSchemaController(TargetDbService targetDbService, RuntimeSettingsService settings,
                                 KrasApiClient krasApiClient, KrasWorkspaceScanner workspaceScanner,
                                 TableMapper tableMapper, KrasCadastralIngestService cadastralIngestService,
                                 KrasPnuIngestService pnuIngestService, List<KrasXmlServiceMapper> mappers,
                                 KrasDateRangeIngestService dateRangeIngestService,
                                 List<KrasDateRangeServiceMapper> dateRangeMappers,
                                 KrasUsezoneIngestService usezoneIngestService,
                                 KrasTxtIngestService txtIngestService,
                                 KrasOperationLogService operationLog, KrasHistoryService historyService) {
        this.operationLog = operationLog;
        this.historyService = historyService;
        this.targetDbService = targetDbService;
        this.settings = settings;
        this.krasApiClient = krasApiClient;
        this.workspaceScanner = workspaceScanner;
        this.tableMapper = tableMapper;
        this.cadastralIngestService = cadastralIngestService;
        this.pnuIngestService = pnuIngestService;
        this.dateRangeIngestService = dateRangeIngestService;
        this.usezoneIngestService = usezoneIngestService;
        this.txtIngestService = txtIngestService;
        this.mappersByDatasetCode = mappers.stream()
                .collect(Collectors.toUnmodifiableMap(KrasXmlServiceMapper::datasetCode, m -> m));
        Map<String, String> slugs = new java.util.LinkedHashMap<>();
        for (ImplementedService svc : IMPLEMENTED_SERVICES) slugs.put(svc.slug(), svc.datasetCode());
        for (SpecService svc : SPEC_SERVICES) slugs.put(svc.slug(), svc.datasetCode());
        this.slugToDatasetCode = Map.copyOf(slugs);
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
        for (SpecService svc : SPEC_SERVICES) {
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

        model.addAttribute("specServices", loadSpecServiceRows(jdbc));

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

        // 전체 TXT 2종
        Map<String, Map<String, Object>> txtStatus = batchDatasetStatus(jdbc,
                List.of("land_basic_file", "land_price_file"));
        Map<String, Object> basicSt = txtStatus.get("land_basic_file");
        model.addAttribute("landBasicFileStatus", basicSt != null ? basicSt.get("contract_status") : "UNVERIFIED");
        model.addAttribute("landBasicFileEnabled", basicSt != null && Boolean.TRUE.equals(basicSt.get("enabled")));
        model.addAttribute("landBasicFileRunning", landBasicFileRunning.get());
        model.addAttribute("lastLandBasicFileResult", lastLandBasicFileResult);
        Map<String, Object> priceSt = txtStatus.get("land_price_file");
        model.addAttribute("landPriceFileStatus", priceSt != null ? priceSt.get("contract_status") : "UNVERIFIED");
        model.addAttribute("landPriceFileEnabled", priceSt != null && Boolean.TRUE.equals(priceSt.get("enabled")));
        model.addAttribute("landPriceFileRunning", landPriceFileRunning.get());
        model.addAttribute("lastLandPriceFileResult", lastLandPriceFileResult);

        model.addAttribute("landBasicCount", jdbc.queryForObject("SELECT count(*) FROM kras.land_basic", Long.class));
        model.addAttribute("landPriceFileRowCount",
                jdbc.queryForObject("SELECT count(*) FROM kras.land_price_file_row WHERE org_cd=?",
                        Long.class, settings.orgCode()));

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
        return executeOperation("cadastral_file", "INGEST", null, (targetJdbc, orgCd) -> {
            if (!cadastralRunning.compareAndSet(false, true)) {
                return Map.of("error", "이미 실행 중입니다.");
            }
            try {
                SyncTableDef def = findCadastralDef();
                Path workDir = Path.of(settings.krasWorkDir(), orgCd);
                String baseName = krasApiClient.downloadLayer(def.srcTableName, workDir);
                Path shpPath = workDir.resolve(baseName + ".shp");
                List<Map<String, Object>> rows = workspaceScanner.loadShpFile(shpPath, def);

                KrasCadastralIngestService.IngestResult result =
                        cadastralIngestService.ingest(targetJdbc, orgCd, LAYER_CODE, rows, "UI");

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
        });
    }

    /** 승격만(설계 절차 7~8). 실제 GeoServer 테이블(public.lp_pa_cbnd)을 여기서만 건드린다. */
    @PostMapping("/kras-db/promote/cadastral")
    @ResponseBody
    public Map<String, Object> promoteCadastral() {
        return executeOperation("cadastral_file", "PUBLIC", null, (targetJdbc, orgCd) -> {
            try {
                KrasCadastralIngestService.PromoteResult result =
                        cadastralIngestService.promote(targetJdbc, orgCd, LAYER_CODE);
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
        });
    }

    /** 용도지역 §9.1 절차 0 — KRAS 레이어 목록을 layer_list item + sync_record로 동결. */
    @PostMapping("/kras-db/usezone/collect-catalog")
    @ResponseBody
    public Map<String, Object> collectUsezoneCatalog() {
        return executeOperation("layer_list", "INGEST", null, (targetJdbc, orgCd) -> {
            if (!usezoneRunning.compareAndSet(false, true)) {
                return Map.of("error", "이미 실행 중입니다.");
            }
            try {
                KrasUsezoneIngestService.CatalogResult result =
                        usezoneIngestService.collectCatalog(targetJdbc, orgCd, "UI");
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
        });
    }

    /** 용도지역 §9.1 절차 1~4 — release 생성/seal + 레이어별 SHP 수집(레이어당 별도 트랜잭션, 부분 실패 허용). */
    @PostMapping("/kras-db/usezone/sweep")
    @ResponseBody
    public Map<String, Object> sweepUsezoneLayers(@RequestParam long catalogItemId) {
        return executeOperation("usezone_file", "SWEEP", null, "catalog=" + catalogItemId, (targetJdbc, orgCd) -> {
            if (!usezoneRunning.compareAndSet(false, true)) {
                return Map.of("error", "이미 실행 중입니다.");
            }
            try {
                KrasUsezoneIngestService.SweepResult result =
                        usezoneIngestService.runLayerSweep(targetJdbc, orgCd, catalogItemId, "UI");
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
        });
    }

    /** 용도지역 §9.1 절차 5 — publish_spatial_release()가 전체 완전성을 검증(부분 실패 시 여기서 막힘). */
    @PostMapping("/kras-db/usezone/publish")
    @ResponseBody
    public Map<String, Object> publishUsezoneRelease(@RequestParam long releaseId) {
        return executeOperation("usezone_file", "PUBLISH", null, "release=" + releaseId, (targetJdbc, orgCd) -> {
            try {
                usezoneIngestService.publishRelease(targetJdbc, releaseId);
                String msg = "release 발행 성공: release_id=" + releaseId;
                lastUsezoneResult = msg;
                return Map.of("success", true, "releaseId", releaseId, "message", msg);
            } catch (Exception e) {
                log.error("[KrasSchema] 용도지역 release 발행 실패: {}", e.getMessage(), e);
                lastUsezoneResult = "release 발행 실패: " + e.getMessage();
                return Map.of("success", false, "message", lastUsezoneResult);
            }
        });
    }

    /** 용도지역 §9.1 절차 6 — 기존 kras.sync_public_usezone() 재사용, public.lt_c_uzone만 여기서 건드림. */
    @PostMapping("/kras-db/usezone/sync-public")
    @ResponseBody
    public Map<String, Object> syncUsezonePublic() {
        return executeOperation("usezone_file", "PUBLIC", null, (targetJdbc, orgCd) -> {
            try {
                long n = usezoneIngestService.syncPublic(targetJdbc);
                String msg = "public 승격 성공: public.lt_c_uzone " + n + "건";
                lastUsezoneResult = msg;
                return Map.of("success", true, "rowCount", n, "message", msg);
            } catch (Exception e) {
                log.error("[KrasSchema] 용도지역 public 승격 실패: {}", e.getMessage(), e);
                lastUsezoneResult = "public 승격 실패: " + e.getMessage();
                return Map.of("success", false, "message", lastUsezoneResult);
            }
        });
    }

    /** 토지기본정보 전체 TXT(KRAS000040) 수집 — kras.stage_parcel/stage_land_basic까지만 채운다. */
    @PostMapping("/kras-db/ingest/land-basic-file")
    @ResponseBody
    public Map<String, Object> ingestLandBasicFile() {
        return executeOperation("land_basic_file", "INGEST", null, (targetJdbc, orgCd) -> {
            if (!landBasicFileRunning.compareAndSet(false, true)) {
                return Map.of("error", "이미 실행 중입니다.");
            }
            try {
                KrasTxtIngestService.IngestResult result =
                        txtIngestService.ingestLandBasic(targetJdbc, orgCd, "UI");
                String msg = result.promotable()
                        ? "수집 성공: item_id=%d, %d건 — 건수를 확인한 뒤 승격하세요.".formatted(result.itemId(), result.rowCount())
                        : "수집됨(item_id=%d, %d건), 단 파싱 경고로 승격 보류: %s"
                                .formatted(result.itemId(), result.rowCount(), result.warnings());
                lastLandBasicFileResult = msg;
                return Map.of("success", true, "itemId", result.itemId(), "rowCount", result.rowCount(),
                        "promotable", result.promotable(), "warnings", result.warnings(), "message", msg);
            } catch (Exception e) {
                log.error("[KrasSchema] 토지기본정보 TXT 수집 실패: {}", e.getMessage(), e);
                lastLandBasicFileResult = "수집 실패: " + e.getMessage();
                return Map.of("success", false, "message", lastLandBasicFileResult);
            } finally {
                landBasicFileRunning.set(false);
            }
        });
    }

    /** 토지기본정보 승격 — kras.parcel/kras.land_basic 자연키 UPSERT. */
    @PostMapping("/kras-db/promote/land-basic-file")
    @ResponseBody
    public Map<String, Object> promoteLandBasicFile(@RequestParam long itemId) {
        return executeOperation("land_basic_file", "PROMOTE", itemId, (targetJdbc, orgCd) -> {
            if (!landBasicFileRunning.compareAndSet(false, true)) {
                return Map.of("error", "이미 실행 중입니다.");
            }
            try {
                txtIngestService.promoteLandBasic(targetJdbc, orgCd, itemId);
                String msg = "승격 성공: item_id=" + itemId;
                lastLandBasicFileResult = msg;
                return Map.of("success", true, "itemId", itemId, "message", msg);
            } catch (Exception e) {
                log.error("[KrasSchema] 토지기본정보 승격 실패: {}", e.getMessage(), e);
                lastLandBasicFileResult = "승격 실패: " + e.getMessage();
                return Map.of("success", false, "message", lastLandBasicFileResult);
            } finally {
                landBasicFileRunning.set(false);
            }
        });
    }

    /** 공시지가 전체 TXT(KRAS000039) 수집 — kras.land_price_file_row 직행이라 승격 단계가 없다. */
    @PostMapping("/kras-db/ingest/land-price-file")
    @ResponseBody
    public Map<String, Object> ingestLandPriceFile() {
        return executeOperation("land_price_file", "INGEST", null, (targetJdbc, orgCd) -> {
            if (!landPriceFileRunning.compareAndSet(false, true)) {
                return Map.of("error", "이미 실행 중입니다.");
            }
            try {
                KrasTxtIngestService.IngestResult result =
                        txtIngestService.ingestLandPrice(targetJdbc, orgCd, "UI");
                String msg = result.promotable()
                        ? "수집 성공: item_id=%d, %d건".formatted(result.itemId(), result.rowCount())
                        : "수집됨(item_id=%d, %d건), 단 파싱 경고로 SUCCESS 보류: %s"
                                .formatted(result.itemId(), result.rowCount(), result.warnings());
                lastLandPriceFileResult = msg;
                return Map.of("success", true, "itemId", result.itemId(), "rowCount", result.rowCount(),
                        "promotable", result.promotable(), "warnings", result.warnings(), "message", msg);
            } catch (Exception e) {
                log.error("[KrasSchema] 공시지가 TXT 수집 실패: {}", e.getMessage(), e);
                lastLandPriceFileResult = "수집 실패: " + e.getMessage();
                return Map.of("success", false, "message", lastLandPriceFileResult);
            } finally {
                landPriceFileRunning.set(false);
            }
        });
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
        return executeOperation(slugToDatasetCode.getOrDefault(slug, ""), "INGEST", null, "PNU=" + pnu, (targetJdbc, orgCd) -> {
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
            historyService.requirePnuInput(orgCd, pnu, datasetCode, extraParams);
            AtomicBoolean running = runningByDatasetCode.get(datasetCode);
            if (!running.compareAndSet(false, true)) {
                return Map.of("error", "이미 실행 중입니다.");
            }
            try {
                KrasPnuIngestService.IngestResult result =
                        pnuIngestService.ingest(targetJdbc, orgCd, mapper, pnu, extraParams, "UI");
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
        });
    }

    /** PNU 단건 매퍼 공용 승격만(설계 절차 9). 승격 패턴(A/B/C)은 매퍼가 declare한 StagePromotionSpec이 결정한다. */
    @PostMapping("/kras-db/promote/{slug}")
    @ResponseBody
    public Map<String, Object> promotePnuMapper(@PathVariable String slug, @RequestParam long itemId) {
        return executeOperation(slugToDatasetCode.getOrDefault(slug, ""), "PROMOTE", itemId, (targetJdbc, orgCd) -> {
            String datasetCode = slugToDatasetCode.get(slug);
            if (datasetCode == null) {
                return Map.of("success", false, "message", "알 수 없는 서비스: " + slug);
            }
            KrasXmlServiceMapper mapper = mappersByDatasetCode.get(datasetCode);
            try {
                KrasPnuIngestService.PromoteResult result = pnuIngestService.promote(targetJdbc, orgCd, mapper, itemId);
                String msg = "승격 성공: item_id=%d".formatted(result.itemId());
                lastResultByDatasetCode.put(datasetCode, msg);
                return Map.of("success", true, "itemId", result.itemId(), "message", msg);
            } catch (Exception e) {
                log.error("[KrasSchema] {} 승격 실패: {}", datasetCode, e.getMessage(), e);
                String msg = "승격 실패: " + e.getMessage();
                lastResultByDatasetCode.put(datasetCode, msg);
                return Map.of("success", false, "message", msg);
            }
        });
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
        return executeOperation(dateRangeSlugToDatasetCode.getOrDefault(slug, ""), "INGEST", null,
                startDate + " ~ " + endDate, (targetJdbc, orgCd) -> {
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
                        dateRangeIngestService.ingest(targetJdbc, orgCd, mapper, start, end, extraParams, "UI");
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
        });
    }

    /** 기간(날짜 범위) 매퍼 공용 승격만. */
    @PostMapping("/kras-db/promote-range/{slug}")
    @ResponseBody
    public Map<String, Object> promoteDateRangeMapper(@PathVariable String slug, @RequestParam long itemId) {
        return executeOperation(dateRangeSlugToDatasetCode.getOrDefault(slug, ""), "PROMOTE", itemId, (targetJdbc, orgCd) -> {
            String datasetCode = dateRangeSlugToDatasetCode.get(slug);
            if (datasetCode == null) {
                return Map.of("success", false, "message", "알 수 없는 서비스: " + slug);
            }
            KrasDateRangeServiceMapper mapper = dateRangeMappersByDatasetCode.get(datasetCode);
            try {
                KrasDateRangeIngestService.PromoteResult result =
                        dateRangeIngestService.promote(targetJdbc, orgCd, mapper, itemId);
                String msg = "승격 성공: item_id=%d".formatted(result.itemId());
                lastResultByDatasetCode.put(datasetCode, msg);
                return Map.of("success", true, "itemId", result.itemId(), "message", msg);
            } catch (Exception e) {
                log.error("[KrasSchema] {} 승격 실패: {}", datasetCode, e.getMessage(), e);
                String msg = "승격 실패: " + e.getMessage();
                lastResultByDatasetCode.put(datasetCode, msg);
                return Map.of("success", false, "message", msg);
            }
        });
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper EXTRA_PARAMS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** {"cbldg_seqno":"0285"} 형태의 JSON 문자열을 Map으로 파싱한다. 비어있으면 빈 Map. */
    private static Map<String, String> parseExtraParams(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        var node = EXTRA_PARAMS_MAPPER.readTree(json);
        if (!node.isObject()) throw new IllegalArgumentException("문자열 값을 가진 JSON 객체를 입력하세요.");
        Map<String, String> params = new HashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().isBlank() || !field.getValue().isTextual()) {
                throw new IllegalArgumentException("추가 파라미터의 키와 값은 문자열이어야 합니다.");
            }
            params.put(field.getKey(), field.getValue().textValue());
        }
        return params;
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

    /**
     * SPEC_SERVICES 14개를 템플릿의 카드 루프용 행으로 만든다 — 카드를 14개 복붙하지 않고
     * th:each 한 번으로 찍는다. 서비스가 늘면 SPEC_SERVICES에 한 줄만 추가하면 된다.
     */
    private List<Map<String, Object>> loadSpecServiceRows(JdbcTemplate jdbc) {
        Map<String, Map<String, Object>> statusByCode = batchDatasetStatus(jdbc,
                SPEC_SERVICES.stream().map(SpecService::datasetCode).toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (SpecService svc : SPEC_SERVICES) {
            Map<String, Object> status = statusByCode.get(svc.datasetCode());
            Map<String, Object> out = new HashMap<>();
            out.put("slug", svc.slug());
            out.put("datasetCode", svc.datasetCode());
            out.put("serviceCode", svc.serviceCode());
            out.put("label", svc.label());
            out.put("businessTables", svc.businessTables());
            out.put("needsBno", svc.needsBno());
            out.put("dependsOn", svc.dependsOn());
            out.put("apiTestId", (svc.serviceCode().startsWith("KOREPS") ? "conn/" : "conn/") + svc.datasetCode());
            out.put("status", status != null ? status.get("contract_status") : "UNVERIFIED");
            out.put("enabled", status != null && Boolean.TRUE.equals(status.get("enabled")));
            out.put("running", runningByDatasetCode.get(svc.datasetCode()).get());
            out.put("lastResult", lastResultByDatasetCode.get(svc.datasetCode()));
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

    @FunctionalInterface
    private interface Operation {
        Map<String, Object> run(JdbcTemplate jdbc, String orgCd) throws Exception;
    }

    private Map<String, Object> executeOperation(String dataset, String action, Long itemId, Operation operation) {
        return executeOperation(dataset, action, itemId, "", operation);
    }

    private Map<String, Object> executeOperation(String dataset, String action, Long itemId,
                                                 String requestSummary, Operation operation) {
        String orgCd = settings.orgCode();
        AtomicBoolean lock = operationLocks.computeIfAbsent(orgCd + ":" + dataset, key -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) {
            return Map.of("success", false, "message", "같은 연계의 작업이 이미 실행 중입니다.");
        }
        try {
            JdbcTemplate targetJdbc = jdbc();
            return operationLog.execute(targetJdbc, orgCd, dataset, action, itemId, requestSummary, () -> {
                historyService.requireReady(targetJdbc, dataset);
                if ("SWEEP".equals(action)) historyService.requireReady(targetJdbc, "layer_list");
                if (itemId != null) {
                    List<String> statuses = targetJdbc.query(
                            "SELECT status FROM kras.sync_item WHERE item_id=? AND org_cd=? AND dataset_code=?",
                            (rs, rowNum) -> rs.getString(1), itemId, orgCd, dataset);
                    if (statuses.isEmpty() || !"SUCCESS".equals(statuses.get(0))) {
                        throw new IllegalArgumentException("현재 기관·데이터셋의 검증된 수집 건만 반영할 수 있습니다.");
                    }
                }
                return operation.run(targetJdbc, orgCd);
            });
        } catch (Exception e) {
            log.error("[KrasSchema] 작업 준비 실패", e);
            return Map.of("success", false, "message", "작업 준비 실패: DB 연결 및 설정을 확인하세요.");
        } finally {
            lock.set(false);
        }
    }

    private JdbcTemplate jdbc() {
        return targetDbService.getConfiguredTargets().get(0).jdbc();
    }
}
