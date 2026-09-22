package geosync.kras;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KrasOperationLogServiceTest {
    @Test
    void retainsRequestIdentityWhenIngestRollsBack() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(12L);
        Map<String, Object> result = new KrasOperationLogService().execute(
                jdbc, "12830", "land_info", "INGEST", null, "PNU=1283025021100010000", () -> {
                    throw new IllegalStateException("롤백 후 실패");
                });
        assertThat(result).containsEntry("success", false);
        verify(jdbc).queryForObject(contains("request_summary"), eq(Long.class), eq("12830"),
                eq("land_info"), eq("INGEST"), isNull(), eq("PNU=1283025021100010000"));
    }
    @Test
    void recordsFailureAfterOperationThrows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(12L);
        Map<String, Object> result = new KrasOperationLogService().execute(
                jdbc, "12830", "land_info", "INGEST", null, () -> {
                    throw new IllegalStateException("응답 파싱 실패");
                });
        assertThat(result).containsEntry("success", false).containsEntry("operationId", 12L);
        verify(jdbc).update(contains("ended_at"), eq("FAILED"), isNull(), isNull(),
                contains("응답 파싱 실패"), eq("{}"), eq(12L), eq("12830"));
    }

    @Test
    void distinguishesPartialSweepFromCompleteSuccess() {
        Map<String, Object> result = Map.of("success", true, "layers", List.of(
                new KrasUsezoneIngestService.LayerResult("A", true, "완료"),
                new KrasUsezoneIngestService.LayerResult("B", false, "다운로드 실패")));
        assertThat(KrasOperationLogService.outcome(result)).isEqualTo("PARTIAL");
        assertThat(KrasOperationLogService.outcome(Map.of("success", true, "promotable", false)))
                .isEqualTo("WARNING");
    }

    @Test
    void doesNotReportCommittedOperationAsFailedWhenLogUpdateFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(12L);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new IllegalStateException("로그 저장 실패"));
        Map<String, Object> result = new KrasOperationLogService().execute(
                jdbc, "12830", "land_info", "PROMOTE", 8L,
                () -> Map.of("success", true, "itemId", 8L));
        assertThat(result).containsEntry("success", true).containsKey("auditWarning");
    }

    @Test
    void runAsyncFinishesOperationAndAlwaysInvokesOnComplete() {
        // @Async 프록시가 없는 단위 테스트에서는 그냥 동기 호출이 된다 — 여기서는 로직만 검증한다
        // (프록시를 거쳐야 진짜 백그라운드 스레드로 도는 것 자체는 Spring 컨테이너 몫).
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(12L);
        boolean[] completed = {false};
        new KrasOperationLogService().runAsync(jdbc, "12830", "usezone_file", "SWEEP", 12L, null,
                () -> Map.of("success", true, "releaseId", 7L), () -> completed[0] = true);
        assertThat(completed[0]).isTrue();
        verify(jdbc).update(contains("ended_at"), eq("SUCCESS"), isNull(), eq(7L),
                eq("SUCCESS"), eq("{}"), eq(12L), eq("12830"));
    }

    @Test
    void runAsyncInvokesOnCompleteEvenWhenOperationThrows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(12L);
        boolean[] completed = {false};
        new KrasOperationLogService().runAsync(jdbc, "12830", "land_basic_file", "INGEST", 12L, null,
                () -> { throw new IllegalStateException("다운로드 실패"); }, () -> completed[0] = true);
        assertThat(completed[0]).isTrue();
        verify(jdbc).update(contains("ended_at"), eq("FAILED"), isNull(), isNull(),
                contains("다운로드 실패"), eq("{}"), eq(12L), eq("12830"));
    }

    @Test
    void doesNotExecuteWithoutDurableStartRecord() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("권한 없음")).when(jdbc).execute(anyString());
        boolean[] called = {false};
        Map<String, Object> result = new KrasOperationLogService().execute(
                jdbc, "12830", "land_info", "INGEST", null, () -> {
                    called[0] = true;
                    return Map.of("success", true);
                });
        assertThat(called[0]).isFalse();
        assertThat(result).containsEntry("success", false);
    }
}
