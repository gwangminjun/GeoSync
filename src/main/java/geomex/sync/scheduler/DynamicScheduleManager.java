package geomex.sync.scheduler;

import geomex.sync.service.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;

@Component
public class DynamicScheduleManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicScheduleManager.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final SyncScheduler syncScheduler;
    private final RuntimeSettingsService settings;

    private ScheduledFuture<?> krasTask;

    public DynamicScheduleManager(SyncScheduler syncScheduler, RuntimeSettingsService settings) {
        this.syncScheduler = syncScheduler;
        this.settings = settings;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(2);
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

        String krasCron = settings.krasSchedule();
        if (isValid(krasCron)) {
            krasTask = taskScheduler.schedule(syncScheduler::runKrasScheduledLoad, new CronTrigger(krasCron));
            log.info("[Scheduler] KRAS 스케줄 등록 (스캔+적재: lt_c_uzone, lp_pa_cbnd): {}", krasCron);
        } else {
            log.info("[Scheduler] KRAS 스케줄 비활성 (cron='{}')", krasCron);
        }
    }

    public String getKrasCron() {
        return settings.krasSchedule();
    }

    public boolean isKrasActive() {
        return krasTask != null && !krasTask.isDone();
    }

    public LocalDateTime getNextRunTime(String cron) {
        if (!isValid(cron)) return null;
        try {
            return CronExpression.parse(cron).next(LocalDateTime.now());
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
}
