package geosync.kras;

import geosync.common.xml.XmlUtil;
import geosync.database.TargetDbService;
import geosync.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /**
     * URL/템플릿에 쓰는 slug(하이픈) → dataset_code(언더스코어) → 모델 속성 접두어(camelCase).
     * 접두어는 landInfoStatus/landInfoEnabled/landInfoRunning/lastLandInfoResult 형태로 조립된다 —
     * 기존 템플릿 변수명과 그대로 맞아 템플릿은 안 건드려도 된다.
     */
    private record ImplementedService(String slug, String datasetCode, String modelPrefix, String group) {}

    private static final List<ImplementedService> IMPLEMENTED_SERVICES = List.of(
        new ImplementedService("land-info", "land_info", "landInfo", "토지"),
        new ImplementedService("land-bldg-check", "land_bldg_check", "landBldgCheck", "토지"),
        new ImplementedService("collective-building", "collective_building", "collectiveBuilding", "건물"),
        new ImplementedService("shr-ymb", "shr_ymb", "shrYmb", "건물"),
        new ImplementedService("own-rgt-hist", "own_rgt_hist", "ownRgtHist", "토지"),
        new ImplementedService("land-mov-hist", "land_mov_hist", "landMovHist", "토지"),
        new ImplementedService("collective-unit", "collective_unit", "collectiveUnit", "건물"),
        new ImplementedService("land-right", "land_right", "landRight", "토지"),
        new ImplementedService("unit-ownership-history", "unit_ownership_history", "unitOwnershipHistory", "건물"),
        new ImplementedService("integrated-building", "integrated_building", "integratedBuilding", "건물"),
        new ImplementedService("building-image", "building_image", "buildingImage", "건물")
    );

    /** 기간(날짜 범위) 조회 서비스(§10~12 계열) — PNU 단건과 최상위 식별자가 달라 별도 레지스트리. */
    private record DateRangeService(String slug, String datasetCode, String modelPrefix, String group) {}

    private static final List<DateRangeService> DATE_RANGE_SERVICES = List.of(
        new DateRangeService("land-change", "land_change", "landChange", "기간조회")
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
                                String businessTables, boolean needsBno, String dependsOn, String group) {}

    private static final List<SpecService> SPEC_SERVICES = List.of(
        new SpecService("use-zone", "use_zone", "KRAS000027", "용도지역지구",
            "kras.land_use_zone", false, "", "토지"),
        new SpecService("land-use-plan-attr", "land_use_plan_attr", "KRAS000025", "토지이용계획 속성",
            "kras.land_use_attribute", false, "", "토지"),
        new SpecService("land-use-plan-info", "land_use_plan_info", "KRAS000026", "토지이용계획 + 행위제한",
            "kras.land_use_plan, kras.land_use_restriction", false, "", "토지"),
        new SpecService("bldg-dong-info", "bldg_dong_info", "KRAS000102", "건물 동 정보 (건물 계열의 관문)",
            "kras.building_register", false, "", "건물"),
        new SpecService("bldg-ledg-gen-hds-info", "bldg_ledg_gen_hds_info", "KRAS000017", "건축물대장 총괄표제부",
            "kras.building_summary", false, "", "건물"),
        new SpecService("bldg-ho-info", "bldg_ho_info", "KRAS000103", "건물 호(전유) 정보",
            "kras.building_unit", true, "", "건물"),
        new SpecService("bldg-hds-info", "bldg_hds_info", "KRAS000014", "건축물대장 표제부 + 층별/소유자/변동",
            "kras.building_title, kras.building_floor, kras.building_title_owner, kras.building_title_change",
            true, "bldg_dong_info", "건물"),
        new SpecService("cbldg-hds-info", "cbldg_hds_info", "KRAS000015", "집합건물 표제부",
            "kras.building_title", true, "bldg_dong_info", "건물"),
        new SpecService("cbldg-dfhs-info", "cbldg_dfhs_info", "KRAS000016", "집합건물 전유부 + 면적/소유자/가격",
            "kras.building_exclusive, kras.building_exclusive_area, kras.building_exclusive_owner, kras.building_exclusive_price",
            true, "bldg_ho_info", "건물"),
        new SpecService("land-jiga", "land_jiga", "KOREPS00011", "공시지가 (KOREPS)",
            "kras.koreps_land_price", false, "", "가격"),
        new SpecService("house-info", "house_info", "KOREPS00033", "개별주택가격 (KOREPS)",
            "kras.house_price", false, "", "가격"),
        new SpecService("fin-dec-jiga", "fin_dec_jiga", "KOREPS00034", "공시결정지가 (KOREPS)",
            "kras.final_land_price", false, "", "가격"),
        new SpecService("read-dec-jiga", "read_dec_jiga", "KOREPS00035", "열람결정지가 (KOREPS)",
            "kras.read_land_price", false, "", "가격"),
        new SpecService("land-attr", "land_attr", "KOREPS00047", "토지속성 (KOREPS)",
            "kras.land_attribute", false, "", "토지")
    );

    /**
     * 추가 파라미터가 필요한 데이터셋과, 채워 넣을 키(순서 유지) + 기본값. "실제 수집" 패널은 이 맵에 있는
     * 데이터셋에서만 키별 입력칸을 보여준다 — 나머지는 PNU만으로 충분하다. 값은 JS에서 키별 입력칸을 만든 뒤
     * JSON으로 조립해 보낸다(사용자가 raw JSON을 직접 쓰지 않아도 됨).
     */
    private static final Map<String, Map<String, String>> EXTRA_PARAMS_HINTS = buildExtraParamsHints();

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Map<String, String>> buildExtraParamsHints() {
        Map<String, Map<String, String>> hints = new LinkedHashMap<>();
        hints.put("collective_unit", orderedMap("cbldg_seqno", "0285"));
        hints.put("land_right", orderedMap("cbldg_seqno", "0285", "dong", "", "flr", "1", "ho", "102", "sil", ""));
        hints.put("unit_ownership_history",
                orderedMap("cbldg_seqno", "0018", "dong", "", "flr", "1", "ho", "102", "sil", ""));
        hints.put("building_image", orderedMap("width", "800", "height", "600", "scale", "1000"));
        for (SpecService svc : SPEC_SERVICES) {
            if (svc.needsBno()) {
                hints.put(svc.datasetCode(), orderedMap("bldg_gbn_no", ""));
            }
        }
        return Collections.unmodifiableMap(hints);
    }

    /** 개별 매퍼가 없는 고정 데이터셋(SHP/전체 TXT) — 목록·상세 사이드바 분류용. */
    private record FixedDataset(String slug, String datasetCode, String label, String group) {}

    private static final List<FixedDataset> FIXED_DATASETS = List.of(
        new FixedDataset("cadastral-file", "cadastral_file", "연속지적 (SHP)", "공간(SHP)"),
        new FixedDataset("layer-list", "layer_list", "용도지역 레이어 목록", "공간(SHP)"),
        new FixedDataset("usezone-file", "usezone_file", "용도지역 (SHP)", "공간(SHP)"),
        new FixedDataset("land-basic-file", "land_basic_file", "토지기본정보 전체 TXT", "전체TXT")
    );

    /**
     * kras.md 규격 16개 항목 한눈에 보기. 번호는 규격서 목차 기준이다.
     * serviceId=null이면 호출 불가. docError=true는 규격서 자체가 잘못된 항목(§11·§12)이라 구현하지 않는다.
     * 서비스 ID를 여기서 손으로 적는 건 임시다 — 레지스트리 설계(2026-10-07)가 끝나면 그쪽에서 읽도록 바꾼다.
     */
    private record SpecItem(int specNo, String name, String serviceId, String slug, String datasetCode,
                            String pathLabel, boolean docError, String targetTable) {}

    private static final List<SpecItem> SPEC_ITEMS = List.of(
        new SpecItem(1, "토지(임야)대장", "KRAS000002", "land-info", "land_info", "PNU 단건", false,
            "kras.land_register, kras.land_owner"),
        new SpecItem(2, "공유지연명부", "KRAS000003", "shr-ymb", "shr_ymb", "PNU 단건", false,
            "kras.land_share"),
        new SpecItem(3, "토지(건물) 존재 여부", "KRAS000101", "land-bldg-check", "land_bldg_check", "PNU 단건", false,
            "kras.land_presence"),
        new SpecItem(4, "대지권등록부(건물조회)", null, "collective-building", "collective_building", "PNU", false,
            "kras.collective_building"),
        new SpecItem(5, "대지권등록부(전유부조회)", null, "collective-unit", "collective_unit", "PNU + 드릴다운", false,
            "kras.collective_unit"),
        new SpecItem(6, "대지권등록부", null, "land-right", "land_right", "PNU + 드릴다운", false,
            "kras.land_right, kras.unit_ownership_history"),
        new SpecItem(7, "토지이동연혁", "KRAS000006", "land-mov-hist", "land_mov_hist", "PNU 단건", false,
            "kras.land_movement_history, kras.land_movement_relation"),
        new SpecItem(8, "소유권변동연혁", "KRAS000007", "own-rgt-hist", "own_rgt_hist", "PNU 단건", false,
            "kras.land_ownership_history"),
        new SpecItem(9, "집합건물소유권연혁", null, "unit-ownership-history", "unit_ownership_history", "드릴다운", false,
            "kras.unit_ownership_history"),
        new SpecItem(10, "토지이동내역", null, "land-change", "land_change", "기간 조회 (최대 10일)", false,
            "kras.land_change_event → kras.parcel, kras.land_basic"),
        new SpecItem(11, "소유권변경내역", null, null, null, "—", true, null),
        new SpecItem(12, "집합건물소유권변경내역", null, null, null, "—", true, null),
        new SpecItem(13, "건물통합정보", null, "integrated-building", "integrated_building", "PNU", false,
            "kras.integrated_building, kras.building_parcel"),
        new SpecItem(14, "건물통합도면", null, "building-image", "building_image", "PNU", false,
            "kras.building_image"),
        new SpecItem(15, "SHAPE 다운로드", "KRAS000038", "cadastral-file", "cadastral_file",
            "파일(SHP) · 레이어 목록 KRAS000037 포함", false, "public.lp_pa_cbnd, public.lt_c_uzone"),
        new SpecItem(16, "토지기본정보 다운로드", "KRAS000040", "land-basic-file", "land_basic_file", "파일(TXT, 전체)", false,
            "kras.land_basic, kras.parcel")
    );

    /**
     * 규격 16개 항목 표시 행. 계약 상태는 kras.sync_dataset 값을 그대로 보여주기만 한다(바꾸지 않음).
     * 호출 가능 = 서비스 ID 있음 + 계약 VERIFIED + 활성. 세 조건 중 하나라도 빠지면 불가다.
     */
    static List<Map<String, Object>> toSpecItemRows(Map<String, Map<String, Object>> statusByCode) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SpecItem item : SPEC_ITEMS) {
            String contract = null;
            boolean enabled = false;
            if (item.datasetCode() != null) {
                Map<String, Object> st = statusByCode.getOrDefault(item.datasetCode(), Map.of());
                Object c = st.get("contract_status");
                contract = c == null ? "UNVERIFIED" : c.toString();
                enabled = Boolean.TRUE.equals(st.get("enabled"));
            }
            boolean callable = item.serviceId() != null && !item.docError() && "VERIFIED".equals(contract) && enabled;
            String approvalLabel = "VERIFIED".equals(contract) ? "승인" : "미승인";
            String state;
            if (item.docError()) {
                state = "규격서 오류 · 미구현";
            } else if (item.serviceId() == null) {
                state = "서비스 ID 없음 · 호출 불가";
            } else {
                state = "계약 " + approvalLabel;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("specNo", item.specNo());
            row.put("name", item.name());
            row.put("serviceId", item.serviceId() == null ? "없음" : item.serviceId());
            row.put("slug", item.slug());
            row.put("datasetCode", item.datasetCode());
            row.put("pathLabel", item.pathLabel());
            row.put("targetTable", item.targetTable() == null ? "—" : item.targetTable());
            // 테이블 이름은 버튼, 구분자(→ ,)는 글자로 — 조회 팝업 진입점.
            row.put("targetParts", item.targetTable() == null ? List.of("—")
                    : Stream.of(item.targetTable().split("(?<=[,→])|(?=[,→])")).map(String::trim)
                            .filter(t -> !t.isEmpty()).toList());
            row.put("docError", item.docError());
            row.put("state", state);
            row.put("callable", callable);
            row.put("contractStatus", contract);
            row.put("enabled", enabled);
            rows.add(row);
        }
        return rows;
    }

    private static final java.time.format.DateTimeFormatter RUN_TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final java.time.format.DateTimeFormatter RUN_DATE_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 규격 표 행에 운영 정보를 덧붙인다: 스케줄, 다음 실행, 마지막 적재.
     * 기본은 "수동" — 증분 동기화 스케줄이 켜진 행은 withIncrementalSchedule이 덮어쓴다.
     */
    /** 연계 수집 이력의 선택 목록에 넣는 데이터셋 코드: 규격 항목 + 파일(SHP/TXT) 데이터셋. */
    static List<String> historyDatasetCodes() {
        return Stream.concat(
                SPEC_ITEMS.stream().map(SpecItem::datasetCode).filter(java.util.Objects::nonNull),
                Stream.of("cadastral_file", "layer_list", "usezone_file", "land_basic_file"))
                .distinct().sorted().toList();
    }

    static void withRunInfo(List<Map<String, Object>> rows, Map<String, Map<String, Object>> lastByCode) {
        withRunInfo(rows, lastByCode, Set.of());
    }

    static void withRunInfo(List<Map<String, Object>> rows, Map<String, Map<String, Object>> lastByCode,
                             Set<String> pnuMapperCodes) {
        for (Map<String, Object> row : rows) {
            String code = (String) row.get("datasetCode");
            row.put("hasPnuMapper", code != null && pnuMapperCodes.contains(code));
            if (code == null) {
                row.put("schedule", "—");
                row.put("nextRun", "—");
                row.put("lastRun", "—");
                continue;
            }
            row.put("schedule", "수동");
            row.put("nextRun", "—");
            Map<String, Object> last = lastByCode.get(code);
            row.put("lastRun", last == null ? "실행 이력 없음" : describeRun(last));
        }
    }

    /** 증분 동기화 스케줄(일일 land_change, 월 보정 land_basic_file)이 켜져 있으면 그 행의 "수동"을 바꾼다. */
    static void withIncrementalSchedule(List<Map<String, Object>> rows, Map<String, String[]> scheduleByCode) {
        for (Map<String, Object> row : rows) {
            Object code = row.get("datasetCode");
            String[] s = code == null ? null : scheduleByCode.get(code);
            if (s == null) continue;
            row.put("schedule", s[0]);
            row.put("nextRun", s[1]);
        }
    }

    /** 마지막 적재 날짜만 보여준다 — 칸이 좁아 상태·기간까지 넣으면 줄바꿈이 심해진다. */
    private static String describeRun(Map<String, Object> last) {
        Object requested = last.get("requested_at");
        if (requested instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(RUN_DATE_FORMAT);
        }
        Object end = last.get("window_end_exclusive");
        return end == null ? "날짜 없음" : end.toString();
    }

    /** 데이터셋별 가장 최근 수집 건(sync_item). 없는 데이터셋은 맵에 없다. */
    private Map<String, Map<String, Object>> lastSyncByDataset(JdbcTemplate jdbc, List<String> codes) {
        if (codes.isEmpty()) return Map.of();
        String placeholders = String.join(",", codes.stream().map(c -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(settings.orgCode());
        args.addAll(codes);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT DISTINCT ON (dataset_code) dataset_code, status, window_start, window_end_exclusive, requested_at
            FROM kras.sync_item
            WHERE org_cd = ? AND dataset_code IN (""" + placeholders + """
            ) ORDER BY dataset_code, item_id DESC
            """, args.toArray());
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byCode.put((String) row.get("dataset_code"), row);
        }
        return byCode;
    }

    /** 기존 KrasWorker 동기화(kras.schedule)의 다음 실행 시각. 신규 경로와 무관하다. */
    String legacyNextRun() {
        String expr = settings.krasSchedule();
        if (expr == null || expr.isBlank() || "-".equals(expr.trim())) {
            return "기존 KRAS 동기화 사용 안 함";
        }
        try {
            java.time.LocalDateTime next = org.springframework.scheduling.support.CronExpression.parse(expr)
                    .next(java.time.LocalDateTime.now());
            return "기존 KRAS 동기화(KrasWorker) 다음 실행: "
                    + (next == null ? "없음" : next.format(RUN_TIME_FORMAT)) + " (cron " + expr + ")";
        } catch (IllegalArgumentException e) {
            return "기존 KRAS 동기화 스케줄 형식 오류: " + expr;
        }
    }

    /** verify/revert-dataset이 건드릴 수 있는 dataset_code 화이트리스트 — 임의 문자열로 다른 데이터셋을 켜지 못하게 막는다. */
    private static final Set<String> VERIFIABLE_DATASETS = Stream.of(
            Stream.of("cadastral_file", "layer_list", "usezone_file", "land_basic_file"),
            IMPLEMENTED_SERVICES.stream().map(ImplementedService::datasetCode),
            DATE_RANGE_SERVICES.stream().map(DateRangeService::datasetCode),
            SPEC_SERVICES.stream().map(SpecService::datasetCode)
    ).flatMap(s -> s).collect(Collectors.toUnmodifiableSet());

    private final KrasOperationLogService operationLog;
    private final KrasHistoryService historyService;
    private final KrasVerificationEvidenceService verificationEvidenceService;
    private final Map<String, AtomicBoolean> operationLocks = new ConcurrentHashMap<>();
    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;
    private final KrasApiClient krasApiClient;
    private final KrasCadastralIngestService cadastralIngestService;
    private final KrasPnuIngestService pnuIngestService;
    private final KrasDateRangeIngestService dateRangeIngestService;
    private final KrasNextgenScheduler nextgenScheduler;
    private final KrasFullLoadService fullLoadService;
    /** 지도 미리보기 — 지오서버 작업공간 주소(끝 / 없음, 비면 미리보기 비활성)와 배경 레이어(아래→위). */
    private final String geoserverUrl;
    private final List<String> previewBackgroundLayers;
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

    public KrasSchemaController(TargetDbService targetDbService, RuntimeSettingsService settings,
                                 KrasApiClient krasApiClient, KrasCadastralIngestService cadastralIngestService,
                                 KrasPnuIngestService pnuIngestService, List<KrasXmlServiceMapper> mappers,
                                 KrasDateRangeIngestService dateRangeIngestService,
                                 List<KrasDateRangeServiceMapper> dateRangeMappers,
                                 KrasUsezoneIngestService usezoneIngestService,
                                 KrasTxtIngestService txtIngestService,
                                 KrasOperationLogService operationLog, KrasHistoryService historyService,
                                 KrasVerificationEvidenceService verificationEvidenceService,
                                 KrasNextgenScheduler nextgenScheduler, KrasFullLoadService fullLoadService,
                                 @Value("${geoserver.url:}") String geoserverUrl,
                                 @Value("${geoserver.preview.background-layers:lp_adm_emd,lp_pa_cbnd}")
                                 String previewBackgroundLayers) {
        this.geoserverUrl = geoserverUrl == null ? "" : geoserverUrl.trim().replaceAll("/+$", "");
        this.previewBackgroundLayers = Stream.of(previewBackgroundLayers.split(","))
                .map(String::trim).filter(n -> n.matches("[a-z0-9_]+")).toList();
        this.nextgenScheduler = nextgenScheduler;
        this.fullLoadService = fullLoadService;
        this.operationLog = operationLog;
        this.historyService = historyService;
        this.verificationEvidenceService = verificationEvidenceService;
        this.targetDbService = targetDbService;
        this.settings = settings;
        this.krasApiClient = krasApiClient;
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
        model.addAttribute("extraParamsHints", EXTRA_PARAMS_HINTS);
        model.addAttribute("mapConfig", mapConfig(geoserverUrl));
        model.addAttribute("datasetIndex", buildDatasetIndex(jdbc));
        List<String> specCodes = SPEC_ITEMS.stream()
                .map(SpecItem::datasetCode).filter(java.util.Objects::nonNull).distinct().toList();
        List<Map<String, Object>> specRows = toSpecItemRows(batchDatasetStatus(jdbc, specCodes));
        withRunInfo(specRows, lastSyncByDataset(jdbc, specCodes), mappersByDatasetCode.keySet());
        withIncrementalSchedule(specRows, nextgenScheduler.scheduleDisplay());
        Map<String, Map<String, Object>> pendingByCode = historyService.pendingPromotion(jdbc, settings.orgCode(),
                specCodes.stream().filter(mappersByDatasetCode::containsKey).toList());
        for (Map<String, Object> row : specRows) {
            Map<String, Object> pending = pendingByCode.get(row.get("datasetCode"));
            row.put("pendingCount", pending == null ? 0L : ((Number) pending.get("pending_count")).longValue());
            row.put("pendingItemId", pending == null ? null : pending.get("latest_item_id"));
        }
        model.addAttribute("legacyNextRun", legacyNextRun());
        model.addAttribute("specItems", specRows);
        model.addAttribute("specCallableCount", specRows.stream().filter(r -> Boolean.TRUE.equals(r.get("callable"))).count());
        model.addAttribute("specNoIdCount", specRows.stream()
                .filter(r -> "없음".equals(r.get("serviceId")) && !Boolean.TRUE.equals(r.get("docError"))).count());
        model.addAttribute("specDocErrorCount", specRows.stream().filter(r -> Boolean.TRUE.equals(r.get("docError"))).count());
        model.addAttribute("datasetGroups", List.of("토지", "건물", "공간(SHP)", "가격", "전체TXT", "기간조회"));


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


        // 전체 TXT
        Map<String, Map<String, Object>> txtStatus = batchDatasetStatus(jdbc, List.of("land_basic_file"));
        Map<String, Object> basicSt = txtStatus.get("land_basic_file");
        model.addAttribute("landBasicFileStatus", basicSt != null ? basicSt.get("contract_status") : "UNVERIFIED");
        model.addAttribute("landBasicFileEnabled", basicSt != null && Boolean.TRUE.equals(basicSt.get("enabled")));
        model.addAttribute("landBasicFileRunning", landBasicFileRunning.get());
        model.addAttribute("lastLandBasicFileResult", lastLandBasicFileResult);


        return "kras-db";
    }

    /**
     * 지정한 dataset_code를 VERIFIED+enabled로 전환한다. 데이터셋은 한 번에 하나씩만 전환한다 —
     * 33개를 일괄 전환하지 않는다(각 API 응답이 문서와 실제로 일치하는지는 데이터셋마다 따로 확인해야 함).
     * 이 버튼은 "운영 응답으로 계약을 실제 확인했다"는 사람의 선언을 DB에 반영만 한다 —
     * 검증 자체를 이 버튼이 대신하지 않는다(design §5.3, §14-1).
     */
    /** 규격 표 "적재 DB 테이블" 칸의 테이블만 조회 팝업에서 열 수 있다. */
    static final Set<String> BROWSABLE_TABLES = SPEC_ITEMS.stream()
            .flatMap(i -> KrasTableBrowser.splitTables(i.targetTable()).stream())
            .collect(Collectors.toUnmodifiableSet());

    /** 적재 DB 테이블 조회 팝업 — 현재 기관 행만, 개인정보는 가려서. 설계 2026-10-15-kras-db-table-popup-design.md */
    @GetMapping("/kras-db/table")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> browseTable(@RequestParam String name,
                                                           @RequestParam(required = false) String col,
                                                           @RequestParam(required = false) String q,
                                                           @RequestParam(defaultValue = "0") int page) {
        try {
            return ResponseEntity.ok(KrasTableBrowser.browse(jdbc(), BROWSABLE_TABLES, name, settings.orgCode(), col, q, page));
        } catch (KrasTableBrowser.BrowseException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 지도 미리보기 대상: 규격 §15 SHAPE 다운로드 행의 테이블(과 그 파생 뷰). */
    static final Set<String> PREVIEW_TABLES = SPEC_ITEMS.stream().filter(i -> i.specNo() == 15)
            .flatMap(i -> KrasTableBrowser.splitTables(i.targetTable()).stream())
            .collect(Collectors.toUnmodifiableSet());

    /** 화면 JS(KRAS_MAP_CONFIG)에 넣는 값 — WMS 주소가 비면 [미리보기]를 누를 수 없다. */
    static Map<String, Object> mapConfig(String geoserverUrl) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("wmsUrl", geoserverUrl == null || geoserverUrl.isBlank() ? "" : geoserverUrl + "/wms");
        cfg.put("previewTables", PREVIEW_TABLES.stream().sorted().toList());
        return cfg;
    }

    /** 지도 미리보기 범위·레이어·포털 스타일(SLD). 설계 2026-10-15-kras-db-layer-map-preview-design.md §5.2 */
    @GetMapping("/kras-db/table/extent")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> tableExtent(@RequestParam String name,
                                                           @RequestParam(required = false) String col,
                                                           @RequestParam(required = false) String q,
                                                           @RequestParam(required = false) String key) {
        try {
            return ResponseEntity.ok(KrasTableBrowser.extent(jdbc(), BROWSABLE_TABLES, PREVIEW_TABLES, name,
                    settings.orgCode(), col, q, key, previewBackgroundLayers));
        } catch (KrasTableBrowser.BrowseException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 적재 테이블에서 파생된 뷰 목록(예: lt_c_uzone → 레이어별 v_lt_c_uzone_*) — 팝업의 "보기" 선택용. */
    @GetMapping("/kras-db/table/views")
    @ResponseBody
    public ResponseEntity<Object> tableViews(@RequestParam String name) {
        try {
            return ResponseEntity.ok(KrasTableBrowser.views(jdbc(), BROWSABLE_TABLES, name, settings.orgCode()));
        } catch (KrasTableBrowser.BrowseException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/kras-db/verify-dataset")
    public String verifyDataset(@RequestParam String datasetCode,
                                 @RequestParam(defaultValue = "false") boolean confirmed,
                                 @RequestParam(required = false) String verifiedBy,
                                 @RequestParam(required = false) String mapperVersion,
                                 @RequestParam(required = false) String responseSample,
                                 @RequestParam(required = false) String note,
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
        String message = datasetCode + " 데이터셋을 VERIFIED로 전환했습니다.";
        // 근거 저장 실패가 검증 전환 자체를 막으면 안 된다 — 위 UPDATE는 이미 커밋됐다.
        try {
            verificationEvidenceService.save(jdbc(), settings.orgCode(), datasetCode,
                    verifiedBy, mapperVersion, responseSample, note);
        } catch (Exception e) {
            log.error("[KrasSchema] {} 검증 근거 저장 실패: {}", datasetCode, e.getMessage(), e);
            message += " (단, 검증 근거 저장에는 실패했습니다: " + e.getMessage() + ")";
        }
        ra.addFlashAttribute("message", message);
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
        ra.addFlashAttribute("message", datasetCode + " 데이터셋 승인을 취소했습니다 (미승인 · 비활성).");
        return "redirect:/kras-db";
    }

    /**
     * 실제 API 응답을 붙여넣으면 이 데이터셋의 매퍼로 바로 파싱해 본다 — 승인 전에 "정상인지"를
     * 사람이 눈으로 태그를 대조하는 대신 기계적으로 확인한다. 담당자/매퍼 버전 같은 입력은 받지 않는다 —
     * 응답 자체가 맞으면 그걸로 충분하다는 판단(2026-10-14).
     * land_change/cadastral_file 등 PNU 단건 XML 매퍼가 아닌 데이터셋은 자동 확인을 지원하지 않는다 —
     * 그 경우 사람이 직접 확인했다는 전제로 승인 버튼을 바로 열어준다.
     */
    @PostMapping("/kras-db/verify-check")
    @ResponseBody
    public Map<String, Object> verifyCheck(@RequestParam String datasetCode,
                                            @RequestParam(required = false) String responseSample) {
        KrasXmlServiceMapper mapper = mappersByDatasetCode.get(datasetCode);
        if (mapper == null) {
            return Map.of("supported", false,
                    "message", "이 데이터셋은 응답 붙여넣기로 자동 확인을 지원하지 않습니다(파일·기간조회형). "
                            + "직접 확인했다면 아래에서 바로 승인하세요.");
        }
        if (responseSample == null || responseSample.isBlank()) {
            return Map.of("supported", true, "valid", false, "message", "응답 데이터를 먼저 붙여넣으세요.");
        }
        org.w3c.dom.Document doc;
        try {
            doc = XmlUtil.parse(responseSample.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Map.of("supported", true, "valid", false, "message", "XML 파싱 실패: " + e.getMessage());
        }
        String pnu = reconstructPnuFromResponse(doc);
        try {
            KrasXmlServiceMapper.MappingResult result = mapper.map(doc, pnu);
            if (result.rows().isEmpty()) {
                return Map.of("supported", true, "valid", false,
                        "message", "응답에서 데이터 행을 찾지 못했습니다 — BODY가 비었거나 구조가 다릅니다.");
            }
            if (!result.fieldWarnings().isEmpty()) {
                return Map.of("supported", true, "valid", false,
                        "message", "일부 필드를 읽지 못했습니다: " + String.join(" / ", result.fieldWarnings()));
            }
            List<String> tables = result.rows().stream().map(KrasXmlServiceMapper.StageRow::stageTable)
                    .distinct().toList();
            return Map.of("supported", true, "valid", true,
                    "message", "정상 — " + result.rows().size() + "건 파싱됨 (" + String.join(", ", tables) + ")");
        } catch (IllegalStateException e) {
            return Map.of("supported", true, "valid", false, "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("supported", true, "valid", false, "message", "확인 중 오류: " + e.getMessage());
        }
    }

    /**
     * 손으로 쓴 매퍼 대부분이 응답의 필지 식별 5태그(ADM_SECT_CD/LAND_LOC_CD/LEDG_GBN/BOBN/BUBN)를
     * 요청 PNU와 문자열 대조한다(§6.2) — 그 식별값이 응답 안에 있으면 그대로 조합해 되돌려주면
     * 신원 검사가 항상 통과해서, 진짜 보고 싶은 "나머지 필드가 맞는지"만 가린다.
     * 식별 태그가 없는 응답(일부 미검증 서비스)은 운영 점검용 chk-pnu로 대체한다.
     */
    private String reconstructPnuFromResponse(org.w3c.dom.Document doc) {
        String adm = XmlUtil.textOf(doc, "ADM_SECT_CD");
        String loc = XmlUtil.textOf(doc, "LAND_LOC_CD");
        String gbn = XmlUtil.textOf(doc, "LEDG_GBN");
        String bobn = XmlUtil.textOf(doc, "BOBN");
        String bubn = XmlUtil.textOf(doc, "BUBN");
        String combined = String.valueOf(adm) + loc + gbn + bobn + bubn;
        return combined.matches("\\d{19}") ? combined : settings.krasChkPnu();
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
                KrasCadastralIngestService.IngestResult result =
                        fullLoadService.ingestCadastral(targetJdbc, orgCd, "UI", "MANUAL");

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

    /**
     * 용도지역 §9.1 절차 1~4 — release 생성/seal + 레이어별 SHP 수집(레이어당 별도 트랜잭션, 부분 실패 허용).
     * 레이어가 수십 개면 KRAS를 그만큼 순차 호출해 오래 걸릴 수 있어 비동기로 돌린다(설계 §2차-1) —
     * 즉시 operationId를 반환하고 화면은 GET /kras-db/operations/{id}를 폴링한다.
     */
    @PostMapping("/kras-db/usezone/sweep")
    @ResponseBody
    public Map<String, Object> sweepUsezoneLayers(@RequestParam long catalogItemId) {
        if (!usezoneRunning.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        Map<String, Object> response = executeOperationAsync("usezone_file", "SWEEP", null,
                "catalog=" + catalogItemId, (targetJdbc, orgCd) -> {
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
        if (!"RUNNING".equals(response.get("status"))) usezoneRunning.set(false);
        return response;
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

    /**
     * 토지기본정보 전체 TXT(KRAS000040) 수집 — kras.stage_parcel/stage_land_basic까지만 채운다.
     * 기관 전체 파일이라 오래 걸릴 수 있어 비동기로 돌린다(설계 §2차-1).
     */
    @PostMapping("/kras-db/ingest/land-basic-file")
    @ResponseBody
    public Map<String, Object> ingestLandBasicFile() {
        if (!landBasicFileRunning.compareAndSet(false, true)) {
            return Map.of("error", "이미 실행 중입니다.");
        }
        Map<String, Object> response = executeOperationAsync("land_basic_file", "INGEST", null, "", (targetJdbc, orgCd) -> {
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
        if (!"RUNNING".equals(response.get("status"))) landBasicFileRunning.set(false);
        return response;
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

    /**
     * 기간 조회 매퍼의 자동 분할 수집. 긴 기간을 maxQueryDays 조각으로 나눠 순서대로 수집한다(승격은
     * 하지 않음 — 조각마다 생긴 수집 건을 KRAS 통계의 수집 이력에서 확인 후 승격한다).
     * startDate를 비우면 이 데이터셋이 마지막으로 성공한 수집의 종료일 다음부터 이어서 수집한다.
     */
    @PostMapping("/kras-db/ingest-range-auto/{slug}")
    @ResponseBody
    public Map<String, Object> ingestDateRangeAuto(@PathVariable String slug,
                                                     @RequestParam(required = false) String startDate,
                                                     @RequestParam String endDate,
                                                     @RequestParam(required = false) String extraParamsJson) {
        return executeOperation(dateRangeSlugToDatasetCode.getOrDefault(slug, ""), "INGEST", null,
                "자동분할 ~" + endDate, (targetJdbc, orgCd) -> {
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
                LocalDate end = LocalDate.parse(endDate);
                LocalDate start;
                if (startDate != null && !startDate.isBlank()) {
                    start = LocalDate.parse(startDate);
                } else {
                    start = dateRangeIngestService.nextWindowStart(targetJdbc, orgCd, mapper).orElse(null);
                    if (start == null) {
                        return Map.of("success", false,
                                "message", "이어서 시작할 이전 수집 이력이 없습니다 — 시작일을 직접 입력하세요.");
                    }
                }
                List<KrasDateRangeIngestService.ChunkResult> chunks =
                        dateRangeIngestService.ingestRange(targetJdbc, orgCd, mapper, start, end, extraParams, "UI");
                List<Map<String, Object>> chunkRows = chunks.stream().map(c -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("start", c.start().toString());
                    row.put("end", c.end().toString());
                    row.put("success", c.success());
                    row.put("itemId", c.itemId());
                    row.put("error", c.error());
                    return row;
                }).toList();
                long succeeded = chunks.stream().filter(KrasDateRangeIngestService.ChunkResult::success).count();
                boolean allSucceeded = !chunks.isEmpty() && succeeded == chunks.size();
                String msg = "%d/%d 조각 수집 완료%s".formatted(succeeded, chunks.size(),
                        allSucceeded ? " — KRAS 통계의 수집 이력에서 조각별로 확인 후 승격하세요."
                                : " — 중단된 조각부터 다시 시도하세요.");
                lastResultByDatasetCode.put(datasetCode, msg);
                return Map.of("success", succeeded > 0, "chunks", chunkRows, "message", msg);
            } catch (Exception e) {
                log.error("[KrasSchema] {} 자동분할 수집 실패: {}", datasetCode, e.getMessage(), e);
                String msg = "자동분할 수집 실패: " + e.getMessage();
                lastResultByDatasetCode.put(datasetCode, msg);
                return Map.of("success", false, "message", msg);
            } finally {
                running.set(false);
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

    /**
     * 목록·상세 사이드바용 — 4개 레지스트리(IMPLEMENTED/DATE_RANGE/SPEC/FIXED)를 그룹·상태와 함께
     * 하나의 목록으로 합친다. 새 레지스트리를 만들지 않고 기존 것을 그대로 합치기만 한다.
     */
    private List<Map<String, Object>> buildDatasetIndex(JdbcTemplate jdbc) {
        List<String> allCodes = Stream.of(
                IMPLEMENTED_SERVICES.stream().map(ImplementedService::datasetCode),
                DATE_RANGE_SERVICES.stream().map(DateRangeService::datasetCode),
                SPEC_SERVICES.stream().map(SpecService::datasetCode),
                FIXED_DATASETS.stream().map(FixedDataset::datasetCode)
        ).flatMap(s -> s).toList();
        Map<String, Map<String, Object>> statusByCode = batchDatasetStatus(jdbc, allCodes);
        Map<String, Map<String, Object>> evidenceByCode =
                verificationEvidenceService.latestByDataset(jdbc, settings.orgCode(), allCodes);

        List<Map<String, Object>> index = new ArrayList<>();
        for (ImplementedService svc : IMPLEMENTED_SERVICES) {
            index.add(datasetIndexRow(svc.slug(), svc.datasetCode(), svc.datasetCode(), svc.group(),
                    statusByCode, evidenceByCode, runningByDatasetCode.get(svc.datasetCode()).get()));
        }
        for (DateRangeService svc : DATE_RANGE_SERVICES) {
            index.add(datasetIndexRow(svc.slug(), svc.datasetCode(), svc.datasetCode(), svc.group(),
                    statusByCode, evidenceByCode, runningByDatasetCode.get(svc.datasetCode()).get()));
        }
        for (SpecService svc : SPEC_SERVICES) {
            index.add(datasetIndexRow(svc.slug(), svc.datasetCode(), svc.label(), svc.group(),
                    statusByCode, evidenceByCode, runningByDatasetCode.get(svc.datasetCode()).get()));
        }
        boolean fixedRunning = cadastralRunning.get() || usezoneRunning.get()
                || landBasicFileRunning.get();
        for (FixedDataset ds : FIXED_DATASETS) {
            boolean running = switch (ds.datasetCode()) {
                case "cadastral_file" -> cadastralRunning.get();
                case "layer_list", "usezone_file" -> usezoneRunning.get();
                case "land_basic_file" -> landBasicFileRunning.get();
                default -> fixedRunning;
            };
            index.add(datasetIndexRow(ds.slug(), ds.datasetCode(), ds.label(), ds.group(),
                    statusByCode, evidenceByCode, running));
        }
        return index;
    }

    private Map<String, Object> datasetIndexRow(String slug, String datasetCode, String label, String group,
                                                  Map<String, Map<String, Object>> statusByCode,
                                                  Map<String, Map<String, Object>> evidenceByCode,
                                                  boolean running) {
        Map<String, Object> status = statusByCode.get(datasetCode);
        Map<String, Object> evidence = evidenceByCode.get(datasetCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", slug);
        out.put("datasetCode", datasetCode);
        out.put("label", label);
        out.put("group", group);
        out.put("status", status != null ? status.get("contract_status") : "UNVERIFIED");
        out.put("enabled", status != null && Boolean.TRUE.equals(status.get("enabled")));
        out.put("running", running);
        out.put("lastVerifiedAt", evidence != null ? evidence.get("verified_at") : null);
        out.put("lastVerifiedBy", evidence != null ? evidence.get("verified_by") : null);
        out.put("hasPnuMapper", mappersByDatasetCode.containsKey(datasetCode));
        return out;
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

    /**
     * executeOperation()과 같은 락·이력·사전점검 흐름을 쓰지만, 실제 작업(operation)은 백그라운드
     * 스레드(KrasOperationLogService.runAsync, @Async)에 넘기고 즉시 operationId를 반환한다 —
     * 전체 TXT 수집·레이어 순회처럼 오래 걸리는 작업이 HTTP 요청을 붙잡지 않게 하기 위함(설계 §2차-1).
     * 화면은 이 operationId로 GET /kras-db/operations/{id}를 폴링해 진행 상태를 확인한다.
     * 락은 백그라운드 작업이 끝난 뒤(runAsync의 onComplete)에만 풀린다 — 동시에 같은 데이터셋을 또
     * 실행할 수 없다는 보장은 동기 버전과 동일하다.
     */
    private Map<String, Object> executeOperationAsync(String dataset, String action, Long itemId,
                                                        String requestSummary, Operation operation) {
        String orgCd = settings.orgCode();
        AtomicBoolean lock = operationLocks.computeIfAbsent(orgCd + ":" + dataset, key -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) {
            return Map.of("success", false, "message", "같은 연계의 작업이 이미 실행 중입니다.");
        }
        JdbcTemplate targetJdbc;
        Long operationId;
        try {
            targetJdbc = jdbc();
            operationId = operationLog.startOperation(targetJdbc, orgCd, dataset, action, itemId, requestSummary);
        } catch (Exception e) {
            lock.set(false);
            log.error("[KrasSchema] 비동기 작업 준비 실패", e);
            return Map.of("success", false,
                    "message", "실행 이력을 저장할 수 없어 작업을 시작하지 않았습니다. DB 연결과 kras 스키마 쓰기 권한을 확인하세요.");
        }
        JdbcTemplate finalJdbc = targetJdbc;
        operationLog.runAsync(finalJdbc, orgCd, dataset, action, operationId, itemId, () -> {
            historyService.requireReady(finalJdbc, dataset);
            if ("SWEEP".equals(action)) historyService.requireReady(finalJdbc, "layer_list");
            if (itemId != null) {
                List<String> statuses = finalJdbc.query(
                        "SELECT status FROM kras.sync_item WHERE item_id=? AND org_cd=? AND dataset_code=?",
                        (rs, rowNum) -> rs.getString(1), itemId, orgCd, dataset);
                if (statuses.isEmpty() || !"SUCCESS".equals(statuses.get(0))) {
                    throw new IllegalArgumentException("현재 기관·데이터셋의 검증된 수집 건만 반영할 수 있습니다.");
                }
            }
            return operation.run(finalJdbc, orgCd);
        }, () -> lock.set(false));
        return Map.of("success", true, "status", "RUNNING", "operationId", operationId, "datasetCode", dataset,
                "message", "백그라운드에서 실행 중입니다 — 진행 상태를 조회하세요.");
    }

    private JdbcTemplate jdbc() {
        return targetDbService.getConfiguredTargets().get(0).jdbc();
    }
}
