package geosync.kras;

import geosync.database.TargetDbService;
import geosync.settings.RuntimeSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KrasNextgenSchedulerTest {

    private final KrasNextgenSettingsService settingsService = mock(KrasNextgenSettingsService.class);
    private final TargetDbService targets = mock(TargetDbService.class);
    private final RuntimeSettingsService runtime = mock(RuntimeSettingsService.class);
    private final KrasIncrementalSyncService incremental = mock(KrasIncrementalSyncService.class);
    private final KrasFullLoadService fullLoad = mock(KrasFullLoadService.class);
    private final KrasPnuQueueService queue = mock(KrasPnuQueueService.class);
    private final KrasPnuQueueWorker worker = mock(KrasPnuQueueWorker.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KrasNextgenScheduler scheduler = new KrasNextgenScheduler(settingsService, targets, runtime,
            incremental, fullLoad, queue, worker, new KrasOperationLogService());

    private void schedules(Map<String, String> s) {
        when(settingsService.current()).thenReturn(new KrasNextgenSettingsService.Settings("", "", "", "yyyyMMdd", s));
    }

    private void target() {
        when(targets.getConfiguredTargets()).thenReturn(List.of(new TargetDbService.ActiveTarget("t", "u", jdbc)));
        when(runtime.orgCode()).thenReturn("12830");
    }

    @Test
    void monthlyCronMapsToMonthlyRunKind() {
        assertThat(KrasNextgenScheduler.jobKindFor("0 0 3 1 * *")).isEqualTo("MONTHLY");
        assertThat(KrasNextgenScheduler.jobKindFor("0 0 6 * * *")).isEqualTo("DAILY");
        assertThat(KrasNextgenScheduler.jobKindFor("0 0 6 * * MON")).isEqualTo("DAILY");
    }

    @Test
    void onlyValidGroupSchedulesAreRegisteredAndShownOnDashboard() {
        schedules(Map.of("correction", "0 0 1 * * *", "full-load", "잘못된 값"));
        scheduler.reload();

        assertThat(scheduler.nextRun(KrasNextgenGroup.CORRECTION)).isPresent();
        assertThat(scheduler.nextRun(KrasNextgenGroup.FULL_LOAD)).isEmpty();
        // 보정 적재 = 토지이동 + 대기열(켜진 PNU 서비스 전부)
        assertThat(scheduler.scheduleDisplay()).containsKeys("land_change", "land_info", "bldg_dong_info")
                .doesNotContainKeys("cadastral_file", "bldg_ho_info");
        assertThat(scheduler.scheduleDisplay().get("land_change")[0]).isEqualTo("보정 적재 · 0 0 1 * * *");
    }

    @Test
    void groupRunsEveryStepInOrderAndKeepsGoingAfterAFailedStep() {
        schedules(Map.of("full-load", "0 0 1 1 * *"));
        target();
        when(runtime.syncEnabled()).thenReturn(true);
        when(incremental.runMonthlyCorrection(any(), any(), any(), any())).thenReturn(List.of("TXT ok"));
        when(fullLoad.runCadastral(any(), any(), any(), any())).thenThrow(new IllegalStateException("SHP 다운로드 실패"));
        when(fullLoad.runUsezone(any(), any(), any(), any())).thenReturn(List.of("용도 ok"));
        when(fullLoad.runLandPrice(any(), any(), any(), any())).thenReturn(List.of("지가 ok"));
        when(queue.tableExists(jdbc)).thenReturn(false);

        scheduler.runGroup(KrasNextgenGroup.FULL_LOAD, false);

        verify(incremental).runMonthlyCorrection(jdbc, "12830", "SCHEDULE", "MONTHLY");
        verify(fullLoad).runLandPrice(jdbc, "12830", "SCHEDULE", "MONTHLY");
        assertThat(scheduler.lastResult("cadastral")).contains("실패: SHP 다운로드 실패");
        assertThat(scheduler.lastResult("full-load")).contains("TXT ok", "SHP 다운로드 실패", "용도 ok", "지가 ok",
                "pnu_work 테이블이 없습니다");
        assertThat(scheduler.running()).isNull();
    }

    @Test
    void scheduledRunIsSkippedWhenSyncDisabledButManualRunStillWorks() {
        schedules(Map.of());
        target();
        when(runtime.syncEnabled()).thenReturn(false);
        when(queue.tableExists(jdbc)).thenReturn(false);
        when(worker.drain(any(), any(), any(), any(), any()))
                .thenReturn(new KrasPnuQueueWorker.Result(0, 0, 0, 0, "x"));

        scheduler.runGroup(KrasNextgenGroup.CORRECTION, false);
        verify(incremental, never()).runDaily(any(), any(), any(LocalDate.class), any(), any());

        scheduler.runGroup(KrasNextgenGroup.CORRECTION, true);
        verify(incremental).runDaily(eq(jdbc), eq("12830"), any(LocalDate.class), eq("NOW"), eq("MANUAL"));
    }

    @Test
    void secondRunWhileOneIsRunningIsSkippedWithReason() throws Exception {
        schedules(Map.of());
        target();
        when(runtime.syncEnabled()).thenReturn(true);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(incremental.runMonthlyCorrection(any(), any(), any(), any())).thenAnswer(inv -> {
            inside.countDown();
            release.await();
            return List.of("ok");
        });
        Thread t = new Thread(() -> scheduler.runGroup(KrasNextgenGroup.FULL_LOAD, true));
        t.start();
        inside.await();

        assertThat(scheduler.runNow(KrasNextgenGroup.CORRECTION)).isFalse();
        scheduler.runGroup(KrasNextgenGroup.CORRECTION, false);
        assertThat(scheduler.lastResult("correction")).contains("건너뜀", "전체 적재");

        release.countDown();
        t.join();
    }
}
