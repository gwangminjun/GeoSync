package geosync.kras;

import geosync.settings.RuntimeSettingsService;
import geosync.synchronization.TableMapper;
import geosync.synchronization.model.SyncTableDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static geosync.kras.KrasIncrementalSyncService.result;

/**
 * 차세대 전체 적재 — 연속지적 SHP, 용도지역 SHP, 공시지가 TXT를 한 번에 끝까지(수집 → 발행 → public 승격) 돌린다.
 * 차세대 스케줄(KrasNextgenScheduler)과 /kras-db 화면의 연속지적 수집 버튼이 같은 코드를 쓴다.
 * 설계: docs/superpowers/specs/2026-10-14/2026-10-14-kras-nextgen-schedule-menu-design.md §6
 *
 * public 승격은 자동이다(D-1) — 차세대 확정 시 기존 연계를 종료하기 때문. 승격 단계의 기존 안전장치
 * (0건 거부, 건수 급감 거부, 용도지역 전체 레이어 완료 필수)는 DB 함수가 그대로 적용한다.
 * 단계마다 kras.ui_operation_log에 남긴다 — 화면에서 실행한 것과 같은 이력으로 보인다.
 */
@Service
public class KrasFullLoadService {

    static final String CADASTRAL_LAYER_CODE = "LP_PA_CBND";
    private static final String CADASTRAL_TGT_TABLE = "ods.lp_pa_cbnd";

    private final KrasApiClient krasApiClient;
    private final KrasWorkspaceScanner workspaceScanner;
    private final TableMapper tableMapper;
    private final RuntimeSettingsService settings;
    private final KrasCadastralIngestService cadastralIngest;
    private final KrasUsezoneIngestService usezoneIngest;
    private final KrasTxtIngestService txtIngest;
    private final KrasHistoryService historyService;
    private final KrasOperationLogService operationLog;

    public KrasFullLoadService(KrasApiClient krasApiClient, KrasWorkspaceScanner workspaceScanner,
                               TableMapper tableMapper, RuntimeSettingsService settings,
                               KrasCadastralIngestService cadastralIngest, KrasUsezoneIngestService usezoneIngest,
                               KrasTxtIngestService txtIngest, KrasHistoryService historyService,
                               KrasOperationLogService operationLog) {
        this.krasApiClient = krasApiClient;
        this.workspaceScanner = workspaceScanner;
        this.tableMapper = tableMapper;
        this.settings = settings;
        this.cadastralIngest = cadastralIngest;
        this.usezoneIngest = usezoneIngest;
        this.txtIngest = txtIngest;
        this.historyService = historyService;
        this.operationLog = operationLog;
    }

    /** 연속지적 SHP 다운로드 → 파싱 → kras 수집(공용 — /kras-db 수집 버튼도 이것을 부른다). */
    public KrasCadastralIngestService.IngestResult ingestCadastral(JdbcTemplate jdbc, String orgCd, String triggeredBy,
                                                                    String jobKind) throws Exception {
        SyncTableDef def = tableMapper.load(settings.krasConfig()).stream()
                .filter(d -> CADASTRAL_TGT_TABLE.equals(d.tgtTableName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("base-tables.xml에 " + CADASTRAL_TGT_TABLE + " 정의가 없습니다."));
        Path workDir = Path.of(settings.krasWorkDir(), orgCd);
        String baseName = krasApiClient.downloadLayer(def.srcTableName, workDir);
        List<Map<String, Object>> rows = workspaceScanner.loadShpFile(workDir.resolve(baseName + ".shp"), def);
        return cadastralIngest.ingest(jdbc, orgCd, CADASTRAL_LAYER_CODE, rows, triggeredBy, jobKind);
    }

    /** 연속지적 전체 1회: 수집 → public.lp_pa_cbnd 승격. 수집이 실패하면 승격하지 않는다. */
    public List<String> runCadastral(JdbcTemplate jdbc, String orgCd, String trigger, String jobKind) {
        List<String> blocked = historyService.readiness(jdbc, "cadastral_file");
        if (!blocked.isEmpty()) return blocked;
        List<String> out = new ArrayList<>();
        Map<String, Object> ingested = operationLog.execute(jdbc, orgCd, "cadastral_file", "INGEST", null,
                trigger + " 전체", () -> {
                    var r = ingestCadastral(jdbc, orgCd, trigger, jobKind);
                    return result(true, r.itemId(), "연속지적 수집 성공: %d건(도형 없음 %d건 제외)"
                            .formatted(r.rowCount(), r.skippedCount()));
                });
        out.add(String.valueOf(ingested.get("message")));
        if (!Boolean.TRUE.equals(ingested.get("success"))) return out;

        Map<String, Object> promoted = operationLog.execute(jdbc, orgCd, "cadastral_file", "PUBLIC", null,
                trigger + " 전체", () -> {
                    var p = cadastralIngest.promote(jdbc, orgCd, CADASTRAL_LAYER_CODE);
                    return result(true, p.itemId(), "public.lp_pa_cbnd 승격 성공: %d건".formatted(p.promotedRows()));
                });
        out.add(String.valueOf(promoted.get("message")));
        return out;
    }

    /**
     * 용도지역 전체 1회: 카탈로그 → 레이어 순회(실패 레이어는 같은 release로 1회 재시도) → 발행 → public 승격.
     * 재시도 후에도 실패 레이어가 남으면 발행·승격하지 않는다(D-6) — 다음 회차는 새 카탈로그부터.
     */
    public List<String> runUsezone(JdbcTemplate jdbc, String orgCd, String trigger, String jobKind) {
        List<String> blocked = new ArrayList<>(historyService.readiness(jdbc, "layer_list"));
        blocked.addAll(historyService.readiness(jdbc, "usezone_file"));
        if (!blocked.isEmpty()) return blocked.stream().distinct().toList();
        List<String> out = new ArrayList<>();
        String summary = trigger + " 전체";

        Map<String, Object> catalog = operationLog.execute(jdbc, orgCd, "layer_list", "INGEST", null, summary, () -> {
            var c = usezoneIngest.collectCatalog(jdbc, orgCd, trigger, jobKind);
            return result(true, c.itemId(), "카탈로그 수집 성공: %d개 레이어".formatted(c.layerCodes().size()));
        });
        out.add(String.valueOf(catalog.get("message")));
        if (!Boolean.TRUE.equals(catalog.get("success"))) return out;
        long catalogItemId = ((Number) catalog.get("itemId")).longValue();

        long releaseId = -1;
        boolean allLayersOk = false;
        for (int attempt = 1; attempt <= 2 && !allLayersOk; attempt++) {
            int round = attempt;
            Map<String, Object> sweep = operationLog.execute(jdbc, orgCd, "usezone_file", "SWEEP", null, summary, () -> {
                var r = usezoneIngest.runLayerSweep(jdbc, orgCd, catalogItemId, trigger, jobKind);
                long ok = r.layers().stream().filter(KrasUsezoneIngestService.LayerResult::success).count();
                Map<String, Object> m = result(ok == r.layers().size(), r.releaseId(),
                        "레이어 순회(%d회차): release_id=%d, %d/%d건 성공".formatted(round, r.releaseId(), ok, r.layers().size()));
                m.put("releaseId", r.releaseId());
                return m;
            });
            out.add(String.valueOf(sweep.get("message")));
            if (!Boolean.TRUE.equals(sweep.get("success"))) return out;
            releaseId = ((Number) sweep.get("releaseId")).longValue();
            allLayersOk = !Boolean.FALSE.equals(sweep.get("promotable"));
        }
        if (!allLayersOk) {
            out.add("재시도 후에도 실패한 레이어가 있어 발행·승격하지 않았습니다.");
            return out;
        }

        long rid = releaseId;
        Map<String, Object> published = operationLog.execute(jdbc, orgCd, "usezone_file", "PUBLISH", null,
                summary, () -> {
                    usezoneIngest.publishRelease(jdbc, rid);
                    return result(true, rid, "release 발행 성공: release_id=" + rid);
                });
        out.add(String.valueOf(published.get("message")));
        if (!Boolean.TRUE.equals(published.get("success"))) return out;

        Map<String, Object> promoted = operationLog.execute(jdbc, orgCd, "usezone_file", "PUBLIC", null,
                summary, () -> {
                    long n = usezoneIngest.syncPublic(jdbc);
                    return result(true, rid, "public.lt_c_uzone 승격 성공: %d건".formatted(n));
                });
        out.add(String.valueOf(promoted.get("message")));
        return out;
    }

    /** 공시지가 TXT 전체 1회: 원본 보존 테이블(kras.land_price_file_row)에 적재 — 반영 단계 없음. */
    public List<String> runLandPrice(JdbcTemplate jdbc, String orgCd, String trigger, String jobKind) {
        List<String> blocked = historyService.readiness(jdbc, "land_price_file");
        if (!blocked.isEmpty()) return blocked;
        Map<String, Object> ingested = operationLog.execute(jdbc, orgCd, "land_price_file", "INGEST", null,
                trigger + " 전체", () -> {
                    var r = txtIngest.ingestLandPrice(jdbc, orgCd, trigger, jobKind);
                    return result(r.promotable(), r.itemId(), r.promotable()
                            ? "공시지가 TXT 수집 성공: %d건".formatted(r.rowCount())
                            : "공시지가 TXT 수집됨(%d건), 파싱 경고: %s".formatted(r.rowCount(), r.warnings()));
                });
        return List.of(String.valueOf(ingested.get("message")));
    }
}
