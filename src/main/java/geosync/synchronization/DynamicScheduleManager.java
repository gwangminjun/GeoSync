package geosync.synchronization;

import geosync.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DynamicScheduleManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicScheduleManager.class);

    /** @every 2h, @every 30m, @every 1h30m 형식 */
    private static final Pattern INTERVAL_PATTERN =
            Pattern.compile("@every\\s+(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?",
                            Pattern.CASE_INSENSITIVE);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final SyncScheduler syncScheduler;
    private final RuntimeSettingsService settings;

    private ScheduledFuture<?> krasTask;
    private ScheduledFuture<?> fileDownloadTask;

    public DynamicScheduleManager(SyncScheduler syncScheduler, RuntimeSettingsService settings) {
        this.syncScheduler = syncScheduler;
        this.settings = settings;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(3);
        this.taskScheduler.setThreadNamePrefix("dyn-scheduler-");
        this.taskScheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        reloadSchedules();
    }

    public synchronized void reloadSchedules() {
        cancelTask("KRAS", krasTask);
        krasTask = null;

        String schedule = settings.krasSchedule();
        if (isInterval(schedule)) {
            try {
                Duration interval = parseInterval(schedule);
                PeriodicTrigger trigger = new PeriodicTrigger(interval);
                trigger.setFixedRate(false);
                trigger.setInitialDelay(interval);
                krasTask = taskScheduler.schedule(syncScheduler::runKrasScheduledLoad, trigger);
                log.info("[Scheduler] KRAS 인터벌 스케줄 등록: {} ({}분마다)", schedule, interval.toMinutes());
            } catch (Exception e) {
                log.warn("[Scheduler] KRAS 인터벌 파싱 실패 '{}': {}", schedule, e.getMessage());
            }
        } else if (isValid(schedule)) {
            krasTask = taskScheduler.schedule(syncScheduler::runKrasScheduledLoad, new CronTrigger(schedule));
            log.info("[Scheduler] KRAS cron 스케줄 등록 (lt_c_uzone, lp_pa_cbnd): {}", schedule);
        } else {
            log.info("[Scheduler] KRAS 스케줄 비활성 (schedule='{}')", schedule);
        }

        cancelTask("FILE_DL", fileDownloadTask);
        fileDownloadTask = null;

        String fdSchedule = settings.fileDownloadSchedule();
        if (fdSchedule != null && !fdSchedule.isBlank() && !"-".equals(fdSchedule.trim())) {
            if (isInterval(fdSchedule)) {
                try {
                    Duration interval = parseInterval(fdSchedule);
                    PeriodicTrigger trigger = new PeriodicTrigger(interval);
                    trigger.setFixedRate(false);
                    trigger.setInitialDelay(interval);
                    fileDownloadTask = taskScheduler.schedule(syncScheduler::runKrasFileDownload, trigger);
                    log.info("[Scheduler] 파일 내려받기 인터벌 스케줄 등록: {} ({}분마다)", fdSchedule, interval.toMinutes());
                } catch (Exception e) {
                    log.warn("[Scheduler] 파일 내려받기 인터벌 파싱 실패 '{}': {}", fdSchedule, e.getMessage());
                }
            } else if (isValid(fdSchedule)) {
                fileDownloadTask = taskScheduler.schedule(syncScheduler::runKrasFileDownload, new CronTrigger(fdSchedule));
                log.info("[Scheduler] 파일 내려받기 cron 스케줄 등록: {}", fdSchedule);
            } else {
                log.info("[Scheduler] 파일 내려받기 스케줄 비활성 (schedule='{}')", fdSchedule);
            }
        } else {
            log.info("[Scheduler] 파일 내려받기 스케줄 비활성 (schedule='{}')", fdSchedule);
        }
    }

    public String getKrasCron() {
        return settings.krasSchedule();
    }

    /** 스케줄 표현식이 인터벌(@every) 형식이면 true */
    public boolean isKrasInterval() {
        return isInterval(settings.krasSchedule());
    }

    public boolean isKrasActive() {
        return krasTask != null && !krasTask.isDone();
    }

    public String getFileDownloadCron() {
        return settings.fileDownloadSchedule();
    }

    public boolean isFileDownloadInterval() {
        return isInterval(settings.fileDownloadSchedule());
    }

    public boolean isFileDownloadActive() {
        return fileDownloadTask != null && !fileDownloadTask.isDone();
    }

    public LocalDateTime getNextRunTime(String schedule) {
        if (isInterval(schedule)) {
            try {
                return LocalDateTime.now().plus(parseInterval(schedule));
            } catch (Exception e) {
                return null;
            }
        }
        if (!isValid(schedule)) return null;
        try {
            return CronExpression.parse(schedule).next(LocalDateTime.now());
        } catch (Exception e) {
            return null;
        }
    }

    private void cancelTask(String name, ScheduledFuture<?> task) {
        if (task != null && !task.isDone()) {
            task.cancel(false);
            log.info("[Scheduler] {} 이전 스케줄 취소", name);
        }
    }

    private boolean isValid(String cron) {
        if (cron == null || cron.isBlank() || "-".equals(cron.trim())) return false;
        try {
            new CronTrigger(cron);
            return true;
        } catch (Exception e) {
            log.warn("[Scheduler] 유효하지 않은 cron '{}': {}", cron, e.getMessage());
            return false;
        }
    }

    private boolean isInterval(String schedule) {
        return schedule != null && schedule.trim().toLowerCase().startsWith("@every");
    }

    private Duration parseInterval(String schedule) {
        Matcher m = INTERVAL_PATTERN.matcher(schedule.trim());
        if (!m.find()) throw new IllegalArgumentException("잘못된 인터벌 형식: " + schedule);
        long h   = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
        long min = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
        long s   = m.group(3) != null ? Long.parseLong(m.group(3)) : 0;
        Duration d = Duration.ofHours(h).plusMinutes(min).plusSeconds(s);
        if (d.isZero()) throw new IllegalArgumentException("인터벌이 0: " + schedule);
        return d;
    }
}
