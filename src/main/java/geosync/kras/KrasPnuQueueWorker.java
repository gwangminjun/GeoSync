package geosync.kras;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PNU 대기열 처리기 — kras.pnu_work에서 꺼내 기존 수집(KrasPnuIngestService.ingest)·반영(promote)을 부른다.
 * 설계: docs/superpowers/specs/2026-10-15/2026-10-15-kras-pnu-api-full-incremental-design.md §5
 *
 * - parallelism개 스레드가 같은 큐에서 FOR UPDATE SKIP LOCKED로 서로 다른 행을 꺼낸다.
 * - 멈추는 조건: 꺼낼 행 없음 / 종료 시각 / 하루 호출 한도 / 수집 연속 실패 circuit-break회.
 * - 끊겨도 큐에 남는다: lease(10분)가 지나면 다시 꺼내고, 수집만 된 행(pending_item_id)은 반영만 한다(§5.1).
 */
@Service
public class KrasPnuQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(KrasPnuQueueWorker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Result(int processed, int done, int retried, int failed, String stopReason) {
        public String message() {
            return "처리 %d건 (완료 %d · 재시도 대기 %d · 실패 %d) — %s".formatted(processed, done, retried, failed,
                    stopReason);
        }
    }

    private final KrasPnuIngestService ingestService;
    private final KrasPnuQueueService queue;
    private final KrasHistoryService historyService;
    private final Map<String, KrasXmlServiceMapper> mappers;
    /**
     * kras.parcel을 함께 갱신하는 서비스(land_info·shr_ymb·own_rgt_hist·land_bldg_check 등). 같은 필지를 동시에 처리하면
     * 먼저 수집한 건이 나중에 반영돼 guard_business_row가 "Older collection cannot overwrite newer"로 막는다
     * (2026-10-15 개발 DB 동시 8 확인). 이 서비스끼리는 같은 필지에서 수집→반영을 한 번에 하나씩 한다.
     */
    private final java.util.Set<String> parcelWriters;
    /** ponytail: JVM 안의 필지별 줄무늬 락 — 서버 1대 전제. 여러 대면 DB advisory lock으로 바꾼다. */
    private final Object[] pnuLocks = new Object[256];

    /** 하루 호출 한도용 — 날짜가 바뀌면 0부터. ponytail: 메모리 카운터라 재시작하면 그날 0부터 다시 센다. */
    private final AtomicReference<LocalDate> countDate = new AtomicReference<>(LocalDate.now());
    private final AtomicInteger callsToday = new AtomicInteger();
    private volatile boolean shuttingDown;
    private volatile int activeThreads;

    public KrasPnuQueueWorker(KrasPnuIngestService ingestService, KrasPnuQueueService queue,
                              KrasHistoryService historyService, List<KrasXmlServiceMapper> mappers) {
        this.ingestService = ingestService;
        this.queue = queue;
        this.historyService = historyService;
        this.mappers = mappers.stream().collect(Collectors.toMap(KrasXmlServiceMapper::datasetCode,
                Function.identity(), (a, b) -> a));
        this.parcelWriters = mappers.stream()
                .filter(m -> m.promotionSpecs().stream().anyMatch(s -> "kras.parcel".equals(s.businessTable())))
                .map(KrasXmlServiceMapper::datasetCode).collect(Collectors.toUnmodifiableSet());
        for (int i = 0; i < pnuLocks.length; i++) pnuLocks[i] = new Object();
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        shuttingDown = true;   // 처리 중인 행은 lease가 지나면 다음 실행이 다시 꺼낸다
    }

    public int activeThreads() { return activeThreads; }

    /** 켜진 서비스 중 지금 실행할 수 있는 것(계약 검증·활성·매퍼). 나머지는 건드리지 않고 건너뛴다. */
    public List<String> runnable(JdbcTemplate jdbc, List<String> datasets) {
        return datasets.stream().filter(mappers::containsKey)
                .filter(ds -> historyService.readiness(jdbc, ds).isEmpty()).toList();
    }

    public Result drain(JdbcTemplate jdbc, String orgCd, KrasNextgenSettingsService.Pnu cfg, String trigger,
                        String jobKind) {
        if (!queue.tableExists(jdbc)) {
            return new Result(0, 0, 0, 0, "kras.pnu_work 테이블이 없습니다 — 2026-10-15 마이그레이션을 적용하세요.");
        }
        List<String> runnable = runnable(jdbc, cfg.effectiveDatasets());
        if (runnable.isEmpty()) {
            return new Result(0, 0, 0, 0, "실행 가능한 PNU 서비스가 없습니다(운영 응답 검증·활성 필요).");
        }
        LocalDateTime deadline = deadline(cfg.stopAt(), LocalDateTime.now());
        Run run = new Run();
        ExecutorService pool = Executors.newFixedThreadPool(cfg.parallelism(), r -> {
            Thread t = new Thread(r, "kras-pnu-queue");
            t.setDaemon(true);
            return t;
        });
        log.info("[PnuQueue] 시작 org={} 서비스={} 동시={} 종료시각={}", orgCd, runnable, cfg.parallelism(), deadline);
        for (int i = 0; i < cfg.parallelism(); i++) {
            pool.execute(() -> loop(jdbc, orgCd, cfg, trigger, jobKind, runnable, deadline, run));
        }
        pool.shutdown();
        try {
            while (!pool.awaitTermination(1, TimeUnit.MINUTES)) {
                log.info("[PnuQueue] 진행 중 처리 {}건", run.processed.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.stop("처리기 중단(인터럽트)");
        }
        String reason = run.stopReason.get();
        if (reason == null) {
            long waiting = queue.waitingOutside(jdbc, orgCd, runnable);
            reason = waiting == 0 ? "대기열에 지금 처리할 행이 없습니다."
                    : "실행 가능한 서비스는 다 처리했습니다. 실행 조건이 안 됐거나 꺼진 서비스의 대기 " + waiting
                      + "건은 남아 있습니다(고급 > PNU 서비스).";
        }
        Result result = new Result(run.processed.get(), run.done.get(), run.retried.get(), run.failed.get(), reason);
        log.info("[PnuQueue] 끝 {}", result.message());
        return result;
    }

    /** 종료 시각 — 시작 시각 이후 처음 오는 그 시각(비어 있으면 없음). */
    static LocalDateTime deadline(String stopAt, LocalDateTime now) {
        if (stopAt == null || stopAt.isBlank()) return null;
        LocalDateTime t = now.toLocalDate().atTime(LocalTime.parse(stopAt));
        return t.isAfter(now) ? t : t.plusDays(1);
    }

    private final class Run {
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger retried = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicReference<String> stopReason = new AtomicReference<>();

        void stop(String reason) { stopReason.compareAndSet(null, reason); }

        boolean stopped() { return stopReason.get() != null || shuttingDown; }
    }

    private void loop(JdbcTemplate jdbc, String orgCd, KrasNextgenSettingsService.Pnu cfg, String trigger,
                      String jobKind, List<String> runnable, LocalDateTime deadline, Run run) {
        synchronized (this) { activeThreads++; }
        try {
            while (!run.stopped()) {
                if (deadline != null && LocalDateTime.now().isAfter(deadline)) {
                    run.stop("종료 시각(" + cfg.stopAt() + ")이 되어 멈췄습니다. 남은 행은 다음 실행에서 이어서 처리합니다.");
                    break;
                }
                if (cfg.dailyCallLimit() > 0 && callsUsedToday() >= cfg.dailyCallLimit()) {
                    run.stop("하루 호출 한도(" + cfg.dailyCallLimit() + "건)에 도달했습니다.");
                    break;
                }
                KrasPnuQueueService.Work w = queue.claim(jdbc, orgCd, runnable);
                if (w == null) break;
                if (run.stopped()) {
                    queue.release(jdbc, orgCd, w);
                    break;
                }
                process(jdbc, orgCd, cfg, trigger, jobKind, w, run);
                if (cfg.callIntervalMs() > 0) Thread.sleep(cfg.callIntervalMs());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[PnuQueue] 처리 스레드 오류: {}", e.getMessage(), e);
            run.stop("처리 스레드 오류: " + e.getMessage());
        } finally {
            synchronized (this) { activeThreads--; }
        }
    }

    void process(JdbcTemplate jdbc, String orgCd, KrasNextgenSettingsService.Pnu cfg, String trigger,
                 String jobKind, KrasPnuQueueService.Work w, Run run) {
        if (parcelWriters.contains(w.datasetCode())) {
            synchronized (pnuLocks[Math.floorMod(w.pnu().hashCode(), pnuLocks.length)]) {
                processUnlocked(jdbc, orgCd, cfg, trigger, jobKind, w, run);
            }
        } else {
            processUnlocked(jdbc, orgCd, cfg, trigger, jobKind, w, run);
        }
    }

    private void processUnlocked(JdbcTemplate jdbc, String orgCd, KrasNextgenSettingsService.Pnu cfg,
                                 String trigger, String jobKind, KrasPnuQueueService.Work w, Run run) {
        run.processed.incrementAndGet();
        KrasXmlServiceMapper mapper = mappers.get(w.datasetCode());
        Long itemId = w.pendingItemId();
        boolean collecting = itemId == null;
        try {
            if (collecting) {
                countCall();
                KrasPnuIngestService.IngestResult r = ingestService.ingest(jdbc, orgCd, mapper, w.pnu(),
                        extraParams(w.extraParamsJson()), trigger, jobKind);
                run.consecutiveFailures.set(0);
                if (!r.promotable()) {
                    queue.markFailed(jdbc, orgCd, w, "필드 검증 경고(item_id=" + r.itemId() + "): " + r.warnings(), false);
                    run.failed.incrementAndGet();
                    return;
                }
                itemId = r.itemId();
                queue.markCollected(jdbc, orgCd, w, itemId);
            }
            ingestService.promote(jdbc, orgCd, mapper, itemId);
            queue.enqueueChildren(jdbc, orgCd, w.datasetCode(), w.pnu(), cfg.effectiveDatasets());
            queue.markDone(jdbc, orgCd, w, itemId);
            run.done.incrementAndGet();
        } catch (Exception e) {
            KrasPnuIngestService.GatewayCodeException code = gatewayCode(e);
            if (code != null && cfg.noDataCodes().contains(code.code)) {
                // ponytail: "자료 없음"은 반영하지 않는다 — 이전에 받은 행이 있으면 남는다. 실응답으로 처리 방침 확정 후 바꾼다.
                run.consecutiveFailures.set(0);
                queue.markDone(jdbc, orgCd, w, null);
                run.done.incrementAndGet();
                return;
            }
            boolean transientError = transientError(e);
            if (!transientError) {
                // 저장·매핑 오류(값 길이 초과, 부모 없음 등)는 다시 불러도 같다 — 호출을 낭비하지 않고 바로 실패로 둔다.
                queue.markFailed(jdbc, orgCd, w, "처리 오류: " + reason(e), false);
                run.failed.incrementAndGet();
                log.warn("[PnuQueue] {} {} 처리 오류(재시도 안 함): {}", w.datasetCode(), w.pnu(), e.getMessage());
                return;
            }
            if (run.consecutiveFailures.incrementAndGet() >= cfg.circuitBreak()) {
                run.stop("게이트웨이 수집이 연속 " + cfg.circuitBreak() + "회 실패해 멈췄습니다: " + e.getMessage()
                        + " — 남은 행은 다음 실행에서 이어서 처리합니다.");
            }
            queue.markFailed(jdbc, orgCd, w, reason(e), true);
            if (w.attemptCount() > KrasPnuQueueService.RETRY_DELAYS.length) run.failed.incrementAndGet();
            else run.retried.incrementAndGet();
            log.warn("[PnuQueue] {} {} 실패({}회차): {}", w.datasetCode(), w.pnu(), w.attemptCount(), e.getMessage());
        }
    }

    /**
     * 다시 하면 될 수 있는 오류: 게이트웨이 호출 실패·오류 코드, DB 연결 끊김 같은 일시 오류.
     * 그 외(저장 제약 위반, 매핑·반영 오류)는 같은 응답이면 같은 결과라 재시도하지 않는다.
     */
    static boolean transientError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof KrasPnuIngestService.GatewayCallException
                    || t instanceof KrasPnuIngestService.GatewayCodeException
                    || t instanceof org.springframework.dao.TransientDataAccessException
                    || t instanceof org.springframework.dao.DataAccessResourceFailureException
                    || t instanceof org.springframework.dao.RecoverableDataAccessException
                    || t instanceof java.sql.SQLTransientException
                    || t instanceof java.sql.SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 화면용 오류 요약 — Spring 예외 메시지는 SQL 전문이 앞에 붙어 500자에서 잘리면 정작 DB 오류가 안 보인다.
     * 바깥 메시지의 앞부분(어느 단계인지)과 가장 안쪽 원인(실제 오류)을 붙인다.
     */
    static String reason(Throwable e) {
        String outer = String.valueOf(e.getMessage());
        int cut = outer.indexOf(": ");
        String head = cut > 0 && cut < 80 ? outer.substring(0, cut) : "";
        Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e);
        String rootMsg = root == e ? outer : root.getMessage();
        return (head.isEmpty() ? "" : head + ": ") + rootMsg;
    }

    private static KrasPnuIngestService.GatewayCodeException gatewayCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof KrasPnuIngestService.GatewayCodeException g) return g;
        }
        return null;
    }

    private static Map<String, String> extraParams(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("extra_params 형식 오류: " + e.getMessage(), e);
        }
    }

    private void countCall() {
        rollDate();
        callsToday.incrementAndGet();
    }

    int callsUsedToday() {
        rollDate();
        return callsToday.get();
    }

    private void rollDate() {
        LocalDate today = LocalDate.now();
        LocalDate prev = countDate.get();
        if (!prev.equals(today) && countDate.compareAndSet(prev, today)) callsToday.set(0);
    }
}
