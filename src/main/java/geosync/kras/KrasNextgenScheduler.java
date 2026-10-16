package geosync.kras;

import geosync.database.TargetDbService;
import geosync.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

import static geosync.kras.KrasIncrementalSyncService.result;

/**
 * 차세대 연계 스케줄 — 묶음 2개(전체 적재 / 보정 적재)에 cron을 걸고, 묶음 안의 단계를 순서대로 돌린다.
 * 설계: docs/superpowers/specs/2026-10-15/2026-10-15-kras-pnu-api-full-incremental-design.md §7
 *
 * - 공용 락: 차세대 작업끼리 겹치지 않는다. 겹치면 나중 것은 건너뛰고 사유를 남긴다.
 * - 스케줄 스레드 2개: 한 묶음이 오래 돌아도(대기열 처리) 다른 묶음의 시각에 "건너뜀"을 바로 남긴다
 *   (스레드 1개면 늦게 실행돼 버린다).
 * - 설정(kras-nextgen 블록)을 저장하면 reload()로 재등록 — 재시작 불필요.
 * - 기존 연계 스케줄(DynamicScheduleManager)과는 별개다. 그쪽은 건드리지 않는다.
 */
@Service
public class KrasNextgenScheduler {

    private static final Logger log = LoggerFactory.getLogger(KrasNextgenScheduler.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final String QUEUE_DATASET = "pnu_queue";

    private final KrasNextgenSettingsService settingsService;
    private final TargetDbService targetDbService;
    private final RuntimeSettingsService runtimeSettings;
    private final KrasIncrementalSyncService incremental;
    private final KrasFullLoadService fullLoad;
    private final KrasPnuQueueService queue;
    private final KrasPnuQueueWorker worker;
    private final KrasOperationLogService operationLog;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<KrasNextgenGroup, ScheduledFuture<?>> futures = new EnumMap<>(KrasNextgenGroup.class);
    /** 이번 기동 이후의 마지막 실행 결과(화면 표시용) — 묶음·단계 ID별. 영구 기록은 ui_operation_log에 있다. */
    private final Map<String, String> lastResults = new ConcurrentHashMap<>();
    /** ponytail: JVM 안의 락 — 서버 1대(Windows 서비스) 전제. 여러 대면 pg_try_advisory_lock으로 바꾼다. */
    private final ReentrantLock lock = new ReentrantLock();
    private volatile String running;
    private volatile String runningId;

    public KrasNextgenScheduler(KrasNextgenSettingsService settingsService, TargetDbService targetDbService,
                                RuntimeSettingsService runtimeSettings, KrasIncrementalSyncService incremental,
                                KrasFullLoadService fullLoad, KrasPnuQueueService queue, KrasPnuQueueWorker worker,
                                KrasOperationLogService operationLog) {
        this.settingsService = settingsService;
        this.targetDbService = targetDbService;
        this.runtimeSettings = runtimeSettings;
        this.incremental = incremental;
        this.fullLoad = fullLoad;
        this.queue = queue;
        this.worker = worker;
        this.operationLog = operationLog;
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("kras-nextgen-");
        scheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        reload();
    }

    /** 설정 파일의 cron으로 전부 다시 등록한다. 빈 값·잘못된 cron은 등록하지 않는다. */
    public synchronized void reload() {
        futures.values().forEach(f -> f.cancel(false));
        futures.clear();
        KrasNextgenSettingsService.Settings s = settingsService.current();
        for (KrasNextgenGroup group : KrasNextgenGroup.values()) {
            String cron = s.schedule(group);
            if (!isValidCron(cron)) continue;
            futures.put(group, scheduler.schedule(() -> runGroup(group, false), new CronTrigger(cron)));
        }
        log.info("[KrasNextgen] 스케줄 등록: {}", futures.isEmpty() ? "없음(전부 꺼짐)"
                : futures.keySet().stream().map(g -> g.id + "=" + s.schedule(g)).toList());
    }

    /** 묶음을 지금 1회 실행(백그라운드). 다른 차세대 작업이 실행 중이면 false. */
    public boolean runNow(KrasNextgenGroup group) {
        if (running != null) return false;
        scheduler.execute(() -> runGroup(group, true));
        return true;
    }

    /** 단계 하나만 지금 실행(고급 영역). */
    public boolean runNow(KrasNextgenJob step) {
        if (running != null) return false;
        scheduler.execute(() -> runStepAlone(step));
        return true;
    }

    /** 실행 중인 묶음·단계 이름(없으면 null). */
    public String running() { return running; }

    public String runningId() { return runningId; }

    public String lastResult(String id) { return lastResults.get(id); }

    public Optional<LocalDateTime> nextRun(KrasNextgenGroup group) {
        String cron = settingsService.current().schedule(group);
        return isValidCron(cron) ? Optional.ofNullable(CronExpression.parse(cron).next(LocalDateTime.now()))
                : Optional.empty();
    }

    /** 현황판(/kras-db) 표시용: 데이터셋 코드 → {스케줄, 다음 실행}. 꺼진 묶음의 데이터셋은 빠진다(그 행은 "수동"). */
    public Map<String, String[]> scheduleDisplay() {
        Map<String, String[]> out = new LinkedHashMap<>();
        KrasNextgenSettingsService.Settings s = settingsService.current();
        for (KrasNextgenGroup group : KrasNextgenGroup.values()) {
            String cron = s.schedule(group);
            if (!isValidCron(cron)) continue;
            String[] shown = {group.label + " · " + cron, nextRun(group).map(t -> t.format(TIME)).orElse("없음")};
            for (KrasNextgenJob step : group.steps) {
                if (step.datasetCode != null) out.putIfAbsent(step.datasetCode, shown);
                if (step == KrasNextgenJob.PNU_QUEUE) s.pnu().effectiveDatasets().forEach(d -> out.putIfAbsent(d, shown));
            }
        }
        return out;
    }

    void runGroup(KrasNextgenGroup group, boolean manual) {
        if (!manual && !runtimeSettings.syncEnabled()) {
            log.info("[KrasNextgen] sync.enabled=false — {} 건너뜀", group.id);
            return;
        }
        if (manual) {
            if (!lock.tryLock()) {
                log.warn("[KrasNextgen] 다른 차세대 작업({}) 실행 중 — {} 건너뜀", running, group.id);
                lastResults.put(group.id, LocalDateTime.now().format(TIME) + " 건너뜀: 다른 작업(" + running + ") 실행 중");
                return;
            }
        } else if (!lock.tryLock()) {
            // 스케줄 실행은 건너뛰지 않고 앞 작업이 끝나길 기다린다 — 매월 1일 전체 적재와 보정 적재가 겹쳐도 둘 다 돈다.
            // ponytail: 기다리는 동안 스레드 하나를 잡는다(풀 2개). 묶음이 더 늘어 동시에 여럿 기다리게 되면 큐로 바꾼다.
            log.info("[KrasNextgen] 다른 차세대 작업({}) 실행 중 — {} 은 끝난 뒤 이어서 실행", running, group.id);
            lastResults.put(group.id, LocalDateTime.now().format(TIME) + " 대기: 다른 작업(" + running + ") 끝나면 실행");
            lock.lock();
        }
        running = group.label;
        runningId = group.id;
        try {
            String trigger = manual ? "NOW" : "SCHEDULE";
            String jobKind = manual ? "MANUAL" : jobKindFor(settingsService.current().schedule(group));
            List<String> lines = new ArrayList<>();
            for (KrasNextgenJob step : group.steps) {
                running = group.label + " — " + step.label;
                String line = step(step, trigger, jobKind);
                lastResults.put(step.id, LocalDateTime.now().format(TIME) + " " + line);
                lines.add(step.label + ": " + line);
            }
            lastResults.put(group.id, LocalDateTime.now().format(TIME) + " " + String.join(" / ", lines));
            log.info("[KrasNextgen] {} {} 결과: {}", group.id, trigger, lines);
        } finally {
            running = null;
            runningId = null;
            lock.unlock();
        }
    }

    private void runStepAlone(KrasNextgenJob step) {
        if (!lock.tryLock()) {
            lastResults.put(step.id, LocalDateTime.now().format(TIME) + " 건너뜀: 다른 작업(" + running + ") 실행 중");
            return;
        }
        running = step.label;
        runningId = step.id;
        try {
            lastResults.put(step.id, LocalDateTime.now().format(TIME) + " " + step(step, "NOW", "MANUAL"));
        } finally {
            running = null;
            runningId = null;
            lock.unlock();
        }
    }

    /** 단계 하나 — 실패해도 예외를 밖으로 내지 않는다(묶음의 다음 단계는 계속 돈다). */
    String step(KrasNextgenJob step, String trigger, String jobKind) {
        try {
            JdbcTemplate jdbc = targetDbService.getConfiguredTargets().get(0).jdbc();
            String orgCd = runtimeSettings.orgCode();
            KrasNextgenSettingsService.Pnu cfg = settingsService.current().pnu();
            List<String> out = switch (step) {
                case LAND_BASIC -> incremental.runMonthlyCorrection(jdbc, orgCd, trigger, jobKind);
                case CADASTRAL -> fullLoad.runCadastral(jdbc, orgCd, trigger, jobKind);
                case USEZONE -> fullLoad.runUsezone(jdbc, orgCd, trigger, jobKind);
                case LAND_CHANGE -> landChange(jdbc, orgCd, cfg, trigger, jobKind);
                case PNU_FULL -> queueOp(jdbc, orgCd, "ENQUEUE", trigger + " 전체", () ->
                        "전체 적재 대기열 등록 %d건".formatted(queue.enqueueFull(jdbc, orgCd, cfg.effectiveDatasets(),
                                cfg.refreshDays())));
                case PNU_REFRESH -> queueOp(jdbc, orgCd, "ENQUEUE", trigger + " 재조회", () ->
                        "순환 재조회 %d건, 연 1회 가격 %d건 등록".formatted(
                                queue.enqueueRotation(jdbc, orgCd, cfg.effectiveDatasets(), cfg.refreshDays(),
                                        cfg.rotateLimit()),
                                queue.enqueueAnnual(jdbc, orgCd, cfg.effectiveDatasets(), cfg.priceRefreshAfter(),
                                        LocalDate.now())));
                case PNU_QUEUE -> drain(jdbc, orgCd, cfg, trigger, jobKind);
            };
            return String.join(" / ", out);
        } catch (Exception e) {
            log.error("[KrasNextgen] {} 실패: {}", step.id, e.getMessage(), e);
            return "실패: " + e.getMessage();
        }
    }

    /** 토지이동내역 수집·반영 뒤, 그동안 들어온 이벤트의 필지를 대기열(CHANGE)에 넣는다. */
    private List<String> landChange(JdbcTemplate jdbc, String orgCd, KrasNextgenSettingsService.Pnu cfg,
                                    String trigger, String jobKind) {
        OffsetDateTime since = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
        List<String> out = new ArrayList<>(incremental.runDaily(jdbc, orgCd, LocalDate.now(), trigger, jobKind));
        if (queue.tableExists(jdbc)) {
            out.addAll(queueOp(jdbc, orgCd, "ENQUEUE", trigger + " 변경", () ->
                    "바뀐 필지 대기열 등록 %d건".formatted(queue.enqueueChanged(jdbc, orgCd, cfg.effectiveDatasets(),
                            since))));
        }
        return out;
    }

    private List<String> drain(JdbcTemplate jdbc, String orgCd, KrasNextgenSettingsService.Pnu cfg, String trigger,
                               String jobKind) {
        Map<String, Object> r = operationLog.execute(jdbc, orgCd, QUEUE_DATASET, "QUEUE", null, trigger + " 처리", () -> {
            KrasPnuQueueWorker.Result res = worker.drain(jdbc, orgCd, cfg, trigger, jobKind);
            Map<String, Object> m = result(true, 0, res.message());
            m.remove("itemId");
            // 연속 실패로 멈춘 실행은 FAILED로 남겨 화면이 "중단"을 안내하게 한다.
            if (res.stopReason().startsWith("게이트웨이 수집이 연속")) m.put("success", false);
            return m;
        });
        return List.of(String.valueOf(r.get("message")));
    }

    private List<String> queueOp(JdbcTemplate jdbc, String orgCd, String action, String summary,
                                 java.util.concurrent.Callable<String> op) {
        if (!queue.tableExists(jdbc)) {
            return List.of("kras.pnu_work 테이블이 없습니다 — 2026-10-15 마이그레이션을 적용하세요.");
        }
        Map<String, Object> r = operationLog.execute(jdbc, orgCd, QUEUE_DATASET, action, null, summary, () -> {
            Map<String, Object> m = result(true, 0, op.call());
            m.remove("itemId");
            return m;
        });
        return List.of(String.valueOf(r.get("message")));
    }

    /** sync_run.job_kind — 일(day-of-month) 필드가 고정된 cron은 월 단위 실행으로 본다. */
    static String jobKindFor(String cron) {
        String[] f = cron.trim().split("\\s+");
        return f.length >= 4 && !"*".equals(f[3]) && !"?".equals(f[3]) ? "MONTHLY" : "DAILY";
    }

    static boolean isValidCron(String cron) {
        return cron != null && !cron.isBlank() && CronExpression.isValidExpression(cron.trim());
    }
}
