package geosync.kras;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 토지 증분 동기화 — 1회 기준선(토지기본정보 TXT) → 매일 토지이동 변동내역(land_change) → 월 1회 TXT 보정.
 * 설계: docs/superpowers/specs/2026-10-14/2026-10-14-kras-incremental-sync-remaining-design.md
 *
 * 기존 연계(SyncScheduler/DynamicScheduleManager, gateway 중계)와는 별개 경로다 — 그쪽은 건드리지 않는다.
 * 스케줄 등록·동시 실행 방지는 KrasNextgenScheduler가 맡는다(차세대 스케줄 화면 /kras-schedule).
 * 실행 기록은 화면 실행과 같은 kras.ui_operation_log에 남긴다(현황판 반영 대기·실패 사유가 그대로 보인다).
 */
@Service
public class KrasIncrementalSyncService {

    private static final String LAND_CHANGE = "land_change";
    private static final String LAND_BASIC = "land_basic_file";

    private final KrasHistoryService historyService;
    private final KrasOperationLogService operationLog;
    private final KrasDateRangeIngestService rangeIngest;
    private final KrasTxtIngestService txtIngest;
    private final LandChangeMapper mapper;

    public KrasIncrementalSyncService(KrasHistoryService historyService, KrasOperationLogService operationLog,
                                      KrasDateRangeIngestService rangeIngest, KrasTxtIngestService txtIngest,
                                      LandChangeMapper mapper) {
        this.historyService = historyService;
        this.operationLog = operationLog;
        this.rangeIngest = rangeIngest;
        this.txtIngest = txtIngest;
        this.mapper = mapper;
    }

    /**
     * 기준일 T0 — 반영까지 끝난 가장 최근 토지기본정보 TXT 수집일의 하루 전.
     * TXT 기준 시각이 내려받은 시각보다 이를 수 있어 하루를 겹친다(겹친 사건은 중복 방지 키가 한 번만 넣는다).
     */
    Optional<LocalDate> baselineDate(JdbcTemplate jdbc, String orgCd) {
        if (jdbc.queryForObject("SELECT to_regclass('kras.ui_operation_log')::text", String.class) == null) {
            return Optional.empty();
        }
        LocalDate collected = jdbc.queryForObject("""
            SELECT max(si.window_start) FROM kras.sync_item si
            WHERE si.org_cd=? AND si.dataset_code=? AND si.status='SUCCESS'
              AND EXISTS (SELECT 1 FROM kras.ui_operation_log ol
                          WHERE ol.org_cd=si.org_cd AND ol.item_id=si.item_id
                            AND ol.action='PROMOTE' AND ol.status IN ('SUCCESS','WARNING'))
            """, LocalDate.class, orgCd, LAND_BASIC);
        return Optional.ofNullable(collected).map(d -> d.minusDays(1));
    }

    /** 일일 증분을 지금 돌릴 수 없는 사유. 비어 있으면 실행 가능. */
    public List<String> dailyBlockedReasons(JdbcTemplate jdbc, String orgCd) {
        List<String> blocked = new ArrayList<>(historyService.readiness(jdbc, LAND_CHANGE));
        if (mapper.dateParams(LocalDate.now(), LocalDate.now()).isEmpty()) {
            blocked.add("처리일자 요청 필드명(시작일·종료일)이 설정되지 않았습니다.");
        }
        if (baselineDate(jdbc, orgCd).isEmpty()) blocked.add("기준선(토지기본정보 TXT 수집·반영) 이력이 없습니다.");
        return blocked;
    }

    public List<String> runDaily(JdbcTemplate jdbc, String orgCd, LocalDate today) {
        return runDaily(jdbc, orgCd, today, "SCHEDULE", "DAILY");
    }

    /**
     * 일일 증분 1회: [커서, 어제]를 10일 조각으로 나눠 조각마다 수집 → 바로 반영. 실패하면 그 조각에서 멈춘다
     * (앞 조각 결과는 유지, 다음 실행이 커서부터 다시). 반환값은 사람이 읽을 결과 줄들.
     * @param trigger 실행 기록의 요청 요약 앞머리·triggered_by (SCHEDULE / NOW)
     * @param jobKind kras.sync_run.job_kind (DAILY / MONTHLY / MANUAL)
     */
    public List<String> runDaily(JdbcTemplate jdbc, String orgCd, LocalDate today, String trigger, String jobKind) {
        List<String> blocked = dailyBlockedReasons(jdbc, orgCd);
        if (!blocked.isEmpty()) return blocked;
        Optional<LocalDate> t0 = baselineDate(jdbc, orgCd);

        // 기준선이 커서보다 새로우면(월 보정 이후) 그 이전 사건은 이미 TXT에 들어 있다 — 다시 적용하면
        // 최신 상태를 과거 값으로 되돌리므로 기준선부터 시작한다.
        LocalDate cursor = rangeIngest.nextWindowStart(jdbc, orgCd, mapper).orElse(t0.get());
        LocalDate start = cursor.isAfter(t0.get()) ? cursor : t0.get();
        LocalDate end = today.minusDays(1);   // 오늘 처리분은 아직 확정되지 않았다고 본다
        if (start.isAfter(end)) return List.of("새로 조회할 구간이 없습니다 (다음 시작일 " + start + ").");

        List<String> out = new ArrayList<>();
        for (LocalDate[] w : KrasDateRangeIngestService.splitWindows(start, end, mapper.maxQueryDays())) {
            String range = w[0] + " ~ " + w[1];
            Map<String, Object> ingested = operationLog.execute(jdbc, orgCd, LAND_CHANGE, "INGEST", null,
                    trigger + " " + range, () -> {
                        var r = rangeIngest.ingest(jdbc, orgCd, mapper, w[0], w[1], Map.of(), trigger, jobKind);
                        return result(r.promotable(), r.itemId(), r.promotable()
                                ? "수집 성공 %s: %d건".formatted(range, countRows(jdbc, r.itemId()))
                                : "수집됨 %s, 파싱 경고로 반영 보류: %s".formatted(range, r.warnings()));
                    });
            out.add(String.valueOf(ingested.get("message")));
            if (!Boolean.TRUE.equals(ingested.get("success")) || Boolean.FALSE.equals(ingested.get("promotable"))) break;

            long itemId = ((Number) ingested.get("itemId")).longValue();
            Map<String, Object> promoted = operationLog.execute(jdbc, orgCd, LAND_CHANGE, "PROMOTE", itemId,
                    trigger + " " + range, () -> {
                        rangeIngest.promote(jdbc, orgCd, mapper, itemId);
                        return result(true, itemId, "반영 성공 " + range);
                    });
            out.add(String.valueOf(promoted.get("message")));
            if (!Boolean.TRUE.equals(promoted.get("success"))) break;
        }
        return out;
    }

    public List<String> runMonthlyCorrection(JdbcTemplate jdbc, String orgCd) {
        return runMonthlyCorrection(jdbc, orgCd, "SCHEDULE", "MONTHLY");
    }

    /** 월 보정 1회: 토지기본정보 TXT 재수집 → 반영 + 필지 상태(ACTIVE/NOT_OBSERVED) 보정. */
    public List<String> runMonthlyCorrection(JdbcTemplate jdbc, String orgCd, String trigger, String jobKind) {
        List<String> blocked = historyService.readiness(jdbc, LAND_BASIC);
        if (!blocked.isEmpty()) return blocked;

        Map<String, Object> ingested = operationLog.execute(jdbc, orgCd, LAND_BASIC, "INGEST", null,
                trigger + " 보정", () -> {
                    var r = txtIngest.ingestLandBasic(jdbc, orgCd, trigger, jobKind);
                    return result(r.promotable(), r.itemId(), r.promotable()
                            ? "월 보정 수집 성공: %d건".formatted(r.rowCount())
                            : "월 보정 수집됨(%d건), 파싱 경고로 반영 보류: %s".formatted(r.rowCount(), r.warnings()));
                });
        List<String> out = new ArrayList<>(List.of(String.valueOf(ingested.get("message"))));
        if (!Boolean.TRUE.equals(ingested.get("success")) || Boolean.FALSE.equals(ingested.get("promotable"))) {
            return out;
        }
        long itemId = ((Number) ingested.get("itemId")).longValue();
        Map<String, Object> promoted = operationLog.execute(jdbc, orgCd, LAND_BASIC, "PROMOTE", itemId,
                trigger + " 보정", () -> {
                    var c = txtIngest.promoteLandBasicWithCorrection(jdbc, orgCd, itemId);
                    String msg = c.guardTripped()
                            ? "반영 성공, 단 미관측 예정 필지가 많아(%d%% 초과) 상태 보정을 건너뜀 — 파일 누락 여부 확인 필요"
                                    .formatted((int) (KrasTxtIngestService.MAX_NOT_OBSERVED_RATIO * 100))
                            : "반영 성공: 재활성 %d건, 미관측 %d건".formatted(c.reactivated(), c.notObserved());
                    return result(!c.guardTripped(), itemId, msg);   // 가드 → WARNING으로 기록
                });
        out.add(String.valueOf(promoted.get("message")));
        return out;
    }

    /** 실행 기록(ui_operation_log)에 넘길 결과 — promotable=false면 WARNING으로 남는다. */
    static Map<String, Object> result(boolean promotable, long itemId, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("promotable", promotable);
        m.put("itemId", itemId);
        m.put("message", message);
        return m;
    }

    static long countRows(JdbcTemplate jdbc, long itemId) {
        Long n = jdbc.queryForObject("SELECT rows_valid FROM kras.sync_item WHERE item_id=?", Long.class, itemId);
        return n == null ? 0 : n;
    }
}
