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
        schedules(Map.of("full-load", "0 0 0 1 * *", "layer", "0 30 7 * * *"));
        target();
        when(runtime.syncEnabled()).thenReturn(true);
        when(incremental.runMonthlyCorrection(any(), any(), any(), any())).thenReturn(List.of("TXT ok"));
        when(fullLoad.runCadastral(any(), any(), any(), any())).thenThrow(new IllegalStateException("SHP 다운로드 실패"));
        when(fullLoad.runUsezone(any(), any(), any(), any())).thenReturn(List.of("용도 ok"));
        when(queue.tableExists(jdbc)).thenReturn(false);

        // 전체 적재는 토지기본정보 TXT → 대기열 등록만. 레이어(SHP)는 2026-10-16에 레이어 적재로 분리했다.
        scheduler.runGroup(KrasNextgenGroup.FULL_LOAD, false);
        verify(incremental).runMonthlyCorrection(jdbc, "12830", "SCHEDULE", "MONTHLY");
        verify(fullLoad, never()).runCadastral(any(), any(), any(), any());
        verify(fullLoad, never()).runUsezone(any(), any(), any(), any());
        assertThat(scheduler.lastResult("full-load")).contains("TXT ok", "pnu_work 테이블이 없습니다");

        // 레이어 적재(매일): 연속지적이 실패해도 용도지역은 돈다.
        scheduler.runGroup(KrasNextgenGroup.LAYER, false);
        verify(fullLoad).runUsezone(jdbc, "12830", "SCHEDULE", "DAILY");
        assertThat(scheduler.lastResult("cadastral")).contains("실패: SHP 다운로드 실패");
        assertThat(scheduler.lastResult("layer")).contains("SHP 다운로드 실패", "용도 ok");
        assertThat(scheduler.running()).isNull();
    }

    @Test
    void layerGroupIsShownOnDashboardForShapeDatasets() {
        schedules(Map.of("layer", "0 30 7 * * *"));
        assertThat(scheduler.nextRun(KrasNextgenGroup.LAYER)).isPresent();
        assertThat(scheduler.scheduleDisplay().get("cadastral_file")[0]).isEqualTo("레이어 적재 · 0 30 7 * * *");
        assertThat(scheduler.scheduleDisplay()).containsKey("usezone_file").doesNotContainKey("land_basic_file");
        assertThat(KrasNextgenGroup.LAYER.replacesPublic()).isTrue();
        assertThat(KrasNextgenGroup.FULL_LOAD.replacesPublic()).isFalse();
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
    void manualRunIsRefusedButScheduledRunWaitsForTheRunningGroup() throws Exception {
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
        // 매월 1일처럼 스케줄이 겹치면 건너뛰지 않고, 앞 묶음이 끝난 뒤 이어서 돈다.
        when(queue.tableExists(jdbc)).thenReturn(false);
        Thread scheduled = new Thread(() -> scheduler.runGroup(KrasNextgenGroup.CORRECTION, false));
        scheduled.start();
        for (int i = 0; i < 100 && scheduler.lastResult("correction") == null; i++) Thread.sleep(20);
        assertThat(scheduler.lastResult("correction")).contains("대기", "전체 적재");
        verify(incremental, never()).runDaily(any(), any(), any(LocalDate.class), any(), any());

        release.countDown();
        t.join();
        scheduled.join(5000);
        assertThat(scheduled.isAlive()).isFalse();
        verify(incremental).runDaily(eq(jdbc), eq("12830"), any(LocalDate.class), eq("SCHEDULE"), any());
    }
}
