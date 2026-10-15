package geosync.kras;

import geosync.database.TargetDbService;
import geosync.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 차세대 스케줄 화면 — "전체 적재 / 보정 적재" 두 묶음의 스케줄, PNU 대기열 설정·현황, 토지이동 연계 정보.
 * 설계: docs/superpowers/specs/2026-10-15/2026-10-15-kras-pnu-api-full-incremental-design.md §7
 * 저장은 conf/application.yml의 kras-nextgen 블록만 바꾸고, 저장 즉시 스케줄을 재등록한다(재시작 불필요).
 */
@Controller
public class KrasScheduleController {

    private static final Logger log = LoggerFactory.getLogger(KrasScheduleController.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final String NO_TABLE = "대기열 테이블(kras.pnu_work)이 아직 없습니다. 대상 DB에 "
            + "docs/database/2026-10-15/2026-10-15-kras-schema-migration-pnu-work.sql을 실행하면 생깁니다.";
    private static final Map<String, String> DATASET_LABELS = Map.ofEntries(
            Map.entry("land_info", "토지(임야)대장"), Map.entry("shr_ymb", "공유지연명부"),
            Map.entry("land_mov_hist", "토지이동연혁"), Map.entry("own_rgt_hist", "소유권변동연혁"),
            Map.entry("land_bldg_check", "토지·건물 존재"), Map.entry("land_use_plan_attr", "토지이용계획 속성"),
            Map.entry("land_use_plan_info", "토지이용계획 정보"), Map.entry("use_zone", "용도지역"),
            Map.entry("bldg_dong_info", "건물 동 목록"), Map.entry("bldg_ledg_gen_hds_info", "총괄표제부"),
            Map.entry("bldg_hds_info", "표제부(일반)"), Map.entry("cbldg_hds_info", "표제부(집합)"),
            Map.entry("bldg_ho_info", "호 목록"), Map.entry("cbldg_dfhs_info", "집합 전유부"),
            Map.entry("land_jiga", "개별공시지가"), Map.entry("house_info", "주택가격"),
            Map.entry("fin_dec_jiga", "확정 공시지가"), Map.entry("read_dec_jiga", "열람 공시지가"),
            Map.entry("land_attr", "토지특성"));

    private final KrasNextgenSettingsService settingsService;
    private final KrasNextgenScheduler scheduler;
    private final KrasHistoryService historyService;
    private final KrasIncrementalSyncService incremental;
    private final KrasPnuQueueService queue;
    private final KrasPnuQueueWorker worker;
    private final TargetDbService targetDbService;
    private final RuntimeSettingsService runtimeSettings;

    public KrasScheduleController(KrasNextgenSettingsService settingsService, KrasNextgenScheduler scheduler,
                                  KrasHistoryService historyService, KrasIncrementalSyncService incremental,
                                  KrasPnuQueueService queue, KrasPnuQueueWorker worker,
                                  TargetDbService targetDbService, RuntimeSettingsService runtimeSettings) {
        this.settingsService = settingsService;
        this.scheduler = scheduler;
        this.historyService = historyService;
        this.incremental = incremental;
        this.queue = queue;
        this.worker = worker;
        this.targetDbService = targetDbService;
        this.runtimeSettings = runtimeSettings;
    }

    @GetMapping("/kras-schedule")
    public String page(Model model) {
        String orgCd = runtimeSettings.orgCode();
        KrasNextgenSettingsService.Settings s = settingsService.current();
        JdbcTemplate jdbc = null;
        String dbError = null;
        try {
            jdbc = targetDbService.getConfiguredTargets().get(0).jdbc();
        } catch (Exception e) {
            dbError = "DB 연결을 확인할 수 없습니다: " + e.getMessage();
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (KrasNextgenGroup group : KrasNextgenGroup.values()) {
            String cron = s.schedule(group);
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", group.id);
            g.put("label", group.label);
            g.put("cron", cron);
            g.put("replacesPublic", group.replacesPublic());
            g.put("nextRun", scheduler.nextRun(group).map(t -> t.format(TIME)).orElse("—"));
            g.put("lastResult", scheduler.lastResult(group.id));
            List<Map<String, Object>> steps = new ArrayList<>();
            for (KrasNextgenJob step : group.steps) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", step.id);
                row.put("label", step.label);
                row.put("target", step.target);
                row.put("blocked", jdbc == null ? List.of(dbError) : gate(jdbc, orgCd, step, s.pnu()));
                row.put("lastResult", lastResult(jdbc, orgCd, step));
                steps.add(row);
            }
            g.put("steps", steps);
            groups.add(g);
        }

        model.addAttribute("currentPage", "kras-schedule");
        model.addAttribute("orgCode", orgCd);
        model.addAttribute("settings", s);
        model.addAttribute("pnu", s.pnu());
        model.addAttribute("configPath", settingsService.path().toAbsolutePath().toString());
        model.addAttribute("groups", groups);
        model.addAttribute("pnuDatasets", pnuDatasetRows(jdbc, s.pnu()));
        model.addAttribute("runningJob", scheduler.running());
        model.addAttribute("legacyConflict", KrasNextgenScheduler.isValidCron(s.schedule(KrasNextgenGroup.FULL_LOAD))
                && legacyScheduleActive());
        model.addAttribute("legacySchedule", runtimeSettings.krasSchedule());
        model.addAttribute("dbServiceCode", jdbc == null ? null : landChangeServiceCode(jdbc));
        model.addAttribute("baseline", jdbc == null ? null
                : incremental.baselineDate(jdbc, orgCd).map(Object::toString).orElse(null));
        model.addAttribute("recentRuns", jdbc == null ? List.of() : recentRuns(jdbc, orgCd));
        model.addAttribute("help", help(s));
        return "kras-schedule";
    }

    @PostMapping("/kras-schedule")
    public String save(@RequestParam MultiValueMap<String, String> params, RedirectAttributes ra) {
        Map<String, String> schedules = new LinkedHashMap<>();
        for (KrasNextgenGroup g : KrasNextgenGroup.values()) schedules.put(g.id, one(params, "schedule_" + g.id));
        KrasNextgenSettingsService.Settings s = new KrasNextgenSettingsService.Settings(
                one(params, "serviceId"), one(params, "startParam"), one(params, "endParam"),
                one(params, "dateFormat"), schedules, pnuFrom(params));
        List<String> errors = KrasNextgenSettingsService.validate(s);
        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", String.join(" ", errors));
            return "redirect:/kras-schedule";
        }
        try {
            settingsService.save(s);
            scheduler.reload();
            List<String> next = new ArrayList<>();
            for (KrasNextgenGroup g : KrasNextgenGroup.values()) {
                next.add(g.label + " " + scheduler.nextRun(g).map(t -> "다음 실행 " + t.format(TIME)).orElse("꺼짐"));
            }
            ra.addFlashAttribute("success", "적용했습니다(재시작 불필요). " + String.join(" · ", next));
        } catch (Exception e) {
            log.error("[KrasSchedule] 저장 실패", e);
            ra.addFlashAttribute("error", "저장 실패: " + e.getMessage());
        }
        return "redirect:/kras-schedule";
    }

    /** 폼의 PNU 설정. 서비스 체크가 기본 목록과 같으면 빈 목록(=기본)으로 저장해 기본값 변경을 따라가게 한다. */
    static KrasNextgenSettingsService.Pnu pnuFrom(MultiValueMap<String, String> params) {
        KrasNextgenSettingsService.Pnu d = KrasNextgenSettingsService.Pnu.defaults();
        List<String> checked = params.getOrDefault("pnuDataset", List.of()).stream()
                .filter(KrasPnuQueueService.ALL_DATASETS::contains).distinct().toList();
        List<String> datasets = new HashSet<>(checked).equals(new HashSet<>(KrasPnuQueueService.DEFAULT_DATASETS))
                ? List.of() : checked;
        return new KrasNextgenSettingsService.Pnu(datasets,
                num(params, "parallelism", d.parallelism()), num(params, "dailyCallLimit", d.dailyCallLimit()),
                num(params, "callIntervalMs", d.callIntervalMs()), one(params, "stopAt"),
                num(params, "circuitBreak", d.circuitBreak()), num(params, "refreshDays", d.refreshDays()),
                num(params, "rotateLimit", d.rotateLimit()),
                params.containsKey("priceRefreshAfter") ? one(params, "priceRefreshAfter") : d.priceRefreshAfter(),
                KrasNextgenSettingsService.list(one(params, "noDataCodes")));
    }

    private static String one(MultiValueMap<String, String> params, String key) {
        String v = params.getFirst(key);
        return v == null ? "" : v.trim();
    }

    private static int num(MultiValueMap<String, String> params, String key, int fallback) {
        String v = one(params, key);
        try {
            return v.isEmpty() ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;   // 검증에서 걸린다
        }
    }

    /** 설정 파일을 직접 고친 뒤 화면에서 다시 읽어 재등록. */
    @PostMapping("/kras-schedule/reload")
    public String reload(RedirectAttributes ra) {
        scheduler.reload();
        ra.addFlashAttribute("success", "설정 파일을 다시 읽어 스케줄을 재등록했습니다.");
        return "redirect:/kras-schedule";
    }

    /** 묶음(full-load / correction) 또는 단계 하나를 지금 실행. */
    @PostMapping("/kras-schedule/run/{id}")
    @ResponseBody
    public Map<String, Object> runNow(@PathVariable String id) {
        String label;
        boolean started;
        try {
            KrasNextgenGroup group = KrasNextgenGroup.byId(id);
            label = group.label;
            started = scheduler.runNow(group);
        } catch (IllegalArgumentException notGroup) {
            try {
                KrasNextgenJob step = KrasNextgenJob.byId(id);
                label = step.label;
                started = scheduler.runNow(step);
            } catch (IllegalArgumentException e) {
                return Map.of("success", false, "message", "알 수 없는 작업: " + id);
            }
        }
        if (!started) {
            String busy = scheduler.running();
            return Map.of("success", false, "message",
                    "다른 차세대 작업(" + (busy == null ? "알 수 없음" : busy) + ")이 실행 중입니다.");
        }
        return Map.of("success", true, "message", label + " 실행을 시작했습니다. 결과는 이 화면에서 확인하세요.");
    }

    @GetMapping("/kras-schedule/status")
    @ResponseBody
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", scheduler.runningId());
        out.put("runningLabel", scheduler.running());
        Map<String, Object> last = new LinkedHashMap<>();
        for (KrasNextgenGroup g : KrasNextgenGroup.values()) last.put(g.id, scheduler.lastResult(g.id));
        for (KrasNextgenJob j : KrasNextgenJob.values()) last.put(j.id, scheduler.lastResult(j.id));
        out.put("lastResults", last);
        return out;
    }

    // ── 대기열 현황 (설계 §7.1) ───────────────────────────────────────

    @GetMapping("/kras-schedule/queue")
    @ResponseBody
    public Map<String, Object> queueSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JdbcTemplate jdbc = targetDbService.getConfiguredTargets().get(0).jdbc();
            String orgCd = runtimeSettings.orgCode();
            if (!queue.tableExists(jdbc)) {
                out.put("error", NO_TABLE);
                return out;
            }
            Map<String, Object> sum = queue.summary(jdbc, orgCd);
            out.putAll(sum);
            out.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
            boolean draining = KrasNextgenJob.PNU_QUEUE.label.equals(stepOf(scheduler.running()));
            out.put("draining", draining);
            out.put("threads", worker.activeThreads());
            out.put("parallelism", settingsService.current().pnu().parallelism());
            out.put("notices", notices(jdbc, orgCd, sum, draining));
            long total = n(sum.get("full_total"));
            long finished = n(sum.get("full_finished"));
            long lastHour = n(sum.get("last_hour"));
            if (total > 0) {
                out.put("progress", (int) (finished * 100 / total));
                long remaining = total - finished;
                out.put("eta", remaining == 0 ? "완료" : lastHour == 0 ? "—"
                        : LocalDateTime.now().plusMinutes(remaining * 60 / lastHour).format(TIME));
            }
            out.put("datasetLabels", DATASET_LABELS);
        } catch (Exception e) {
            out.put("error", "대기열을 조회할 수 없습니다: " + e.getMessage());
        }
        return out;
    }

    @GetMapping("/kras-schedule/queue/rows")
    @ResponseBody
    public Map<String, Object> queueRows(@RequestParam(defaultValue = "RETRY") String status,
                                         @RequestParam(required = false) String q) {
        try {
            JdbcTemplate jdbc = targetDbService.getConfiguredTargets().get(0).jdbc();
            if (!queue.tableExists(jdbc)) return Map.of("error", NO_TABLE);
            return Map.of("rows", queue.rows(jdbc, runtimeSettings.orgCode(), status, q));
        } catch (Exception e) {
            return Map.of("error", "목록을 조회할 수 없습니다: " + e.getMessage());
        }
    }

    /** 실패 행 재시도 — keys(dataset|pnu|extra_key)가 없으면 전부. 처리는 다음 실행 또는 "지금 실행"에서. */
    @PostMapping("/kras-schedule/queue/retry")
    @ResponseBody
    public Map<String, Object> retry(@RequestParam(required = false) List<String> keys) {
        try {
            JdbcTemplate jdbc = targetDbService.getConfiguredTargets().get(0).jdbc();
            if (!queue.tableExists(jdbc)) return Map.of("success", false, "message", NO_TABLE);
            int n = queue.retryFailed(jdbc, runtimeSettings.orgCode(), keys == null ? List.of() : keys);
            return Map.of("success", true, "message",
                    n + "건을 다시 대기로 돌렸습니다. 다음 보정 적재 또는 [지금 실행]에서 처리됩니다.");
        } catch (Exception e) {
            return Map.of("success", false, "message", "재시도 실패: " + e.getMessage());
        }
    }

    /** 안내 문구 — 상황마다 한 줄. */
    private List<String> notices(JdbcTemplate jdbc, String orgCd, Map<String, Object> sum, boolean draining) {
        List<String> out = new ArrayList<>();
        String next = scheduler.nextRun(KrasNextgenGroup.CORRECTION).map(t -> t.format(TIME)).orElse(null);
        String when = next == null ? "보정 적재 스케줄이 꺼져 있어 [지금 실행]을 눌러야" : "다음 보정 적재(" + next + ")에서";
        if (!draining && n(sum.get("retry")) > 0) {
            out.add("ⓘ 처리기가 끝난 뒤 남은 재시도 " + n(sum.get("retry")) + "건은 " + when + " 처리됩니다."
                    + (next == null ? "" : " 바로 처리하려면 보정 적재 [지금 실행]을 누르세요."));
        }
        if (!draining && n(sum.get("interrupted")) > 0) {
            out.add("⚠ " + n(sum.get("interrupted")) + "건이 처리 도중 중단되어 다음 실행에서 다시 시도합니다.");
        }
        long blocked = queue.waitingOutside(jdbc, orgCd,
                worker.runnable(jdbc, settingsService.current().pnu().effectiveDatasets()));
        if (blocked > 0) {
            out.add("ⓘ 실행 조건이 안 됐거나 꺼진 서비스의 대기 " + blocked
                    + "건은 처리되지 않고 남아 있습니다. 고급 > PNU 서비스에서 실행 조건을 확인하세요.");
        }
        List<Map<String, Object>> last = jdbc.queryForList("""
            SELECT to_char(started_at, 'YYYY-MM-DD HH24:MI') AS started, status, message FROM kras.ui_operation_log
            WHERE org_cd=? AND dataset_code=? AND action='QUEUE' ORDER BY operation_id DESC LIMIT 1
            """, orgCd, KrasNextgenScheduler.QUEUE_DATASET);
        if (!last.isEmpty() && !draining) {
            Map<String, Object> r = last.get(0);
            if ("FAILED".equals(r.get("status"))) {
                out.add("⚠ 마지막 실행이 중단됐습니다: " + r.get("started") + " " + r.get("message"));
            } else if ("RUNNING".equals(r.get("status"))) {
                out.add("⚠ 마지막 실행(" + r.get("started") + ")이 서버 재시작 등으로 끝나지 못했습니다. "
                        + "남은 대기열은 다음 실행에서 이어서 처리합니다.");
            } else if (String.valueOf(r.get("message")).contains("종료 시각")) {
                out.add("ⓘ 마지막 실행(" + r.get("started") + ")은 종료 시각에 멈췄습니다. 남은 행은 " + when + " 이어서 처리됩니다.");
            }
        }
        return out;
    }

    private static String stepOf(String running) {
        if (running == null) return null;
        int i = running.indexOf(" — ");
        return i < 0 ? running : running.substring(i + 3);
    }

    private static long n(Object v) {
        return v instanceof Number num ? num.longValue() : 0;
    }

    /** 도움말 팝업의 값 — 고정 문구 대신 현재 설정값을 넣는다(설계 §7.2). */
    private Map<String, Object> help(KrasNextgenSettingsService.Settings s) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("leaseMinutes", KrasPnuQueueService.LEASE_MINUTES);
        h.put("retryDelays", "1시간 → 4시간 → 1일");
        h.put("maxRetries", KrasPnuQueueService.RETRY_DELAYS.length);
        h.put("circuitBreak", s.pnu().circuitBreak());
        h.put("correctionSchedule", scheduler.nextRun(KrasNextgenGroup.CORRECTION)
                .map(t -> "다음 보정 적재(" + t.format(TIME) + ")").orElse("보정 적재 스케줄이 꺼져 있으므로 [지금 실행]"));
        return h;
    }

    private List<Map<String, Object>> pnuDatasetRows(JdbcTemplate jdbc, KrasNextgenSettingsService.Pnu pnu) {
        List<String> enabled = pnu.effectiveDatasets();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String ds : KrasPnuQueueService.ALL_DATASETS) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", ds);
            r.put("label", DATASET_LABELS.getOrDefault(ds, ds));
            r.put("enabled", enabled.contains(ds));
            r.put("child", KrasPnuQueueService.CHILD_DATASETS.contains(ds));
            List<String> blocked;
            try {
                blocked = jdbc == null ? List.of("DB 연결 없음") : historyService.readiness(jdbc, ds);
            } catch (Exception e) {
                blocked = List.of("확인 불가");
            }
            r.put("blocked", blocked);
            rows.add(r);
        }
        return rows;
    }

    private List<String> gate(JdbcTemplate jdbc, String orgCd, KrasNextgenJob step, KrasNextgenSettingsService.Pnu pnu) {
        try {
            if (step == KrasNextgenJob.LAND_CHANGE) return incremental.dailyBlockedReasons(jdbc, orgCd);
            if (step.gateDatasets.isEmpty()) {
                if (!queue.tableExists(jdbc)) return List.of("kras.pnu_work 테이블이 없습니다(2026-10-15 마이그레이션).");
                return worker.runnable(jdbc, pnu.effectiveDatasets()).isEmpty()
                        ? List.of("실행 가능한 PNU 서비스가 없습니다(운영 응답 검증·활성 필요).") : List.of();
            }
            List<String> reasons = new ArrayList<>();
            for (String ds : step.gateDatasets) reasons.addAll(historyService.readiness(jdbc, ds));
            return reasons.stream().distinct().toList();
        } catch (Exception e) {
            return List.of("실행 조건을 확인할 수 없습니다: " + e.getMessage());
        }
    }

    /** 이번 기동 이후 결과가 있으면 그것, 없으면 실행 기록의 마지막 행. */
    private String lastResult(JdbcTemplate jdbc, String orgCd, KrasNextgenJob step) {
        String mem = scheduler.lastResult(step.id);
        if (mem != null || jdbc == null || step.datasetCode == null) return mem;
        try {
            if (jdbc.queryForObject("SELECT to_regclass('kras.ui_operation_log')::text", String.class) == null) return null;
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, message, started_at FROM kras.ui_operation_log
                WHERE org_cd=? AND dataset_code=? ORDER BY operation_id DESC LIMIT 1
                """, orgCd, step.datasetCode);
            if (rows.isEmpty()) return null;
            Map<String, Object> r = rows.get(0);
            return r.get("started_at") + " " + r.get("status") + " " + (r.get("message") == null ? "" : r.get("message"));
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> recentRuns(JdbcTemplate jdbc, String orgCd) {
        try {
            if (jdbc.queryForObject("SELECT to_regclass('kras.ui_operation_log')::text", String.class) == null) {
                return List.of();
            }
            return jdbc.queryForList("""
                SELECT started_at, dataset_code, action, status, request_summary, message
                FROM kras.ui_operation_log
                WHERE org_cd=? AND (request_summary LIKE 'SCHEDULE%' OR request_summary LIKE 'NOW%')
                ORDER BY operation_id DESC LIMIT 30
                """, orgCd);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String landChangeServiceCode(JdbcTemplate jdbc) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT service_code FROM kras.sync_dataset WHERE dataset_code='land_change'", String.class);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 기존 KRAS 동기화(KrasWorker) 스케줄이 켜져 있는지 — 같은 public 테이블을 쓰므로 경고용. */
    private boolean legacyScheduleActive() {
        String expr = runtimeSettings.krasSchedule();
        return expr != null && !expr.isBlank() && !"-".equals(expr.trim());
    }
}
