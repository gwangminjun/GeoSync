package geosync.kras;

import geosync.database.TargetDbService;
import geosync.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 토지 증분 동기화 — 1회 기준선(토지기본정보 TXT) → 매일 토지이동 변동내역(land_change) → 월 1회 TXT 보정.
 * 설계: docs/superpowers/specs/2026-10-14/2026-10-14-kras-incremental-sync-remaining-design.md
 *
 * 기존 연계(SyncScheduler/DynamicScheduleManager, gateway 중계)와는 별개 경로다 — 그쪽은 건드리지 않는다.
 * 설정은 conf/application.yml의 최상위 kras-incremental 블록에 둔다 — kras 블록 안에 두면 설정 화면 저장 시
 * kras 블록이 통째로 교체돼 지워진다. 스케줄(land-change.schedule, land-basic.correction-schedule)은 기동 시 한 번 읽는다. 비우면 꺼짐.
 * 실행 기록은 화면 실행과 같은 kras.ui_operation_log에 남긴다(현황판 반영 대기·실패 사유가 그대로 보인다).
 */
@Service
public class KrasIncrementalSyncService {

    private static final Logger log = LoggerFactory.getLogger(KrasIncrementalSyncService.class);
    private static final String LAND_CHANGE = "land_change";
    private static final String LAND_BASIC = "land_basic_file";

    private final TargetDbService targetDbService;
    private final RuntimeSettingsService settings;
    private final KrasHistoryService historyService;
    private final KrasOperationLogService operationLog;
    private final KrasDateRangeIngestService rangeIngest;
    private final KrasTxtIngestService txtIngest;
    private final LandChangeMapper mapper;
    private final String dailyCron;
    private final String correctionCron;
    /** ponytail: JVM 안의 락 — 서버 1대(Windows 서비스) 전제. 여러 대로 늘리면 pg_try_advisory_lock으로 바꾼다. */
    private final ReentrantLock lock = new ReentrantLock();

    public KrasIncrementalSyncService(TargetDbService targetDbService, RuntimeSettingsService settings,
                                      KrasHistoryService historyService, KrasOperationLogService operationLog,
                                      KrasDateRangeIngestService rangeIngest, KrasTxtIngestService txtIngest,
                                      LandChangeMapper mapper,
                                      @Value("${kras-incremental.land-change.schedule:}") String dailyCron,
                                      @Value("${kras-incremental.land-basic.correction-schedule:}") String correctionCron) {
        this.targetDbService = targetDbService;
        this.settings = settings;
        this.historyService = historyService;
        this.operationLog = operationLog;
        this.rangeIngest = rangeIngest;
        this.txtIngest = txtIngest;
        this.mapper = mapper;
        this.dailyCron = dailyCron.trim();
        this.correctionCron = correctionCron.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerSchedules() {
        if (!isValidCron(dailyCron) && !isValidCron(correctionCron)) {
            log.info("[KrasIncremental] 증분 동기화 스케줄 꺼짐 (daily='{}', correction='{}')", dailyCron, correctionCron);
            return;
        }
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kras-incremental-");
        scheduler.initialize();
        if (isValidCron(dailyCron)) {
            scheduler.schedule(() -> runScheduled("DAILY"), new CronTrigger(dailyCron));
            log.info("[KrasIncremental] 일일 증분 스케줄 등록: {}", dailyCron);
        }
        if (isValidCron(correctionCron)) {
            scheduler.schedule(() -> runScheduled("MONTHLY"), new CronTrigger(correctionCron));
            log.info("[KrasIncremental] 월 보정 스케줄 등록: {}", correctionCron);
        }
    }

    /** 현황판 표시용: 데이터셋 코드 → {스케줄, 다음 실행}. 꺼진 스케줄은 빠진다(그 행은 "수동" 그대로). */
    public Map<String, String[]> scheduleDisplay() {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (isValidCron(dailyCron)) out.put(LAND_CHANGE, new String[]{"일일 증분 · " + dailyCron, nextRun(dailyCron)});
        if (isValidCron(correctionCron)) {
            out.put(LAND_BASIC, new String[]{"월 보정 · " + correctionCron, nextRun(correctionCron)});
        }
        return out;
    }

    private static String nextRun(String cron) {
        LocalDateTime next = CronExpression.parse(cron).next(LocalDateTime.now());
        return next == null ? "없음" : next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private void runScheduled(String kind) {
        try {
            if (!settings.syncEnabled()) {
                log.info("[KrasIncremental] sync.enabled=false — {} 건너뜀", kind);
                return;
            }
            JdbcTemplate jdbc = targetDbService.getConfiguredTargets().get(0).jdbc();
            List<String> result = "DAILY".equals(kind)
                    ? runDaily(jdbc, settings.orgCode(), LocalDate.now())
                    : runMonthlyCorrection(jdbc, settings.orgCode());
            log.info("[KrasIncremental] {} 결과: {}", kind, result);
        } catch (Exception e) {
            log.error("[KrasIncremental] {} 실행 실패: {}", kind, e.getMessage(), e);
        }
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

    /**
     * 일일 증분 1회: [커서, 어제]를 10일 조각으로 나눠 조각마다 수집 → 바로 반영. 실패하면 그 조각에서 멈춘다
     * (앞 조각 결과는 유지, 다음 실행이 커서부터 다시). 반환값은 사람이 읽을 결과 줄들.
     */
    public List<String> runDaily(JdbcTemplate jdbc, String orgCd, LocalDate today) {
        if (!lock.tryLock()) return List.of("다른 증분 작업이 실행 중이라 건너뜀");
        try {
            List<String> blocked = new ArrayList<>(historyService.readiness(jdbc, LAND_CHANGE));
            if (mapper.dateParams(today, today).isEmpty()) {
                blocked.add("처리일자 요청 필드명(kras-incremental.land-change.start-param/end-param)이 설정되지 않았습니다.");
            }
            Optional<LocalDate> t0 = baselineDate(jdbc, orgCd);
            if (t0.isEmpty()) blocked.add("기준선(토지기본정보 TXT 수집·반영) 이력이 없습니다.");
            if (!blocked.isEmpty()) return blocked;

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
                        "SCHEDULE " + range, () -> {
                            var r = rangeIngest.ingest(jdbc, orgCd, mapper, w[0], w[1], Map.of(), "SCHEDULE", "DAILY");
                            return result(r.promotable(), r.itemId(), r.promotable()
                                    ? "수집 성공 %s: %d건".formatted(range, countRows(jdbc, r.itemId()))
                                    : "수집됨 %s, 파싱 경고로 반영 보류: %s".formatted(range, r.warnings()));
                        });
                out.add(String.valueOf(ingested.get("message")));
                if (!Boolean.TRUE.equals(ingested.get("success")) || Boolean.FALSE.equals(ingested.get("promotable"))) break;

                long itemId = ((Number) ingested.get("itemId")).longValue();
                Map<String, Object> promoted = operationLog.execute(jdbc, orgCd, LAND_CHANGE, "PROMOTE", itemId,
                        "SCHEDULE " + range, () -> {
                            rangeIngest.promote(jdbc, orgCd, mapper, itemId);
                            return result(true, itemId, "반영 성공 " + range);
                        });
                out.add(String.valueOf(promoted.get("message")));
                if (!Boolean.TRUE.equals(promoted.get("success"))) break;
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** 월 보정 1회: 토지기본정보 TXT 재수집 → 반영 + 필지 상태(ACTIVE/NOT_OBSERVED) 보정. */
    public List<String> runMonthlyCorrection(JdbcTemplate jdbc, String orgCd) {
        if (!lock.tryLock()) return List.of("다른 증분 작업이 실행 중이라 건너뜀");
        try {
            List<String> blocked = historyService.readiness(jdbc, LAND_BASIC);
            if (!blocked.isEmpty()) return blocked;

            Map<String, Object> ingested = operationLog.execute(jdbc, orgCd, LAND_BASIC, "INGEST", null,
                    "SCHEDULE MONTHLY", () -> {
                        var r = txtIngest.ingestLandBasic(jdbc, orgCd, "SCHEDULE", "MONTHLY");
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
                    "SCHEDULE MONTHLY", () -> {
                        var c = txtIngest.promoteLandBasicWithCorrection(jdbc, orgCd, itemId);
                        String msg = c.guardTripped()
                                ? "반영 성공, 단 미관측 예정 필지가 많아(%d%% 초과) 상태 보정을 건너뜀 — 파일 누락 여부 확인 필요"
                                        .formatted((int) (KrasTxtIngestService.MAX_NOT_OBSERVED_RATIO * 100))
                                : "반영 성공: 재활성 %d건, 미관측 %d건".formatted(c.reactivated(), c.notObserved());
                        return result(!c.guardTripped(), itemId, msg);   // 가드 → WARNING으로 기록
                    });
            out.add(String.valueOf(promoted.get("message")));
            return out;
        } finally {
            lock.unlock();
        }
    }

    private static Map<String, Object> result(boolean promotable, long itemId, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("promotable", promotable);
        m.put("itemId", itemId);
        m.put("message", message);
        return m;
    }

    private static long countRows(JdbcTemplate jdbc, long itemId) {
        Long n = jdbc.queryForObject("SELECT rows_valid FROM kras.sync_item WHERE item_id=?", Long.class, itemId);
        return n == null ? 0 : n;
    }

    private static boolean isValidCron(String cron) {
        if (cron.isEmpty() || "-".equals(cron)) return false;
        try {
            CronExpression.parse(cron);
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("[KrasIncremental] 유효하지 않은 cron '{}': {}", cron, e.getMessage());
            return false;
        }
    }
}
