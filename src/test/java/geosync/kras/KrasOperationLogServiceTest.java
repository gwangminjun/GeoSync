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
                jdbc, "46870", "land_info", "INGEST", null, "PNU=4687025021100010000", () -> {
                    throw new IllegalStateException("롤백 후 실패");
                });
        assertThat(result).containsEntry("success", false);
        verify(jdbc).queryForObject(contains("request_summary"), eq(Long.class), eq("46870"),
                eq("land_info"), eq("INGEST"), isNull(), eq("PNU=4687025021100010000"));
    }
    @Test
    void recordsFailureAfterOperationThrows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(12L);
        Map<String, Object> result = new KrasOperationLogService().execute(
                jdbc, "46870", "land_info", "INGEST", null, () -> {
                    throw new IllegalStateException("응답 파싱 실패");
                });
        assertThat(result).containsEntry("success", false).containsEntry("operationId", 12L);
        verify(jdbc).update(contains("ended_at"), eq("FAILED"), isNull(), isNull(),
                contains("응답 파싱 실패"), eq("{}"), eq(12L), eq("46870"));
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
                jdbc, "46870", "land_info", "PROMOTE", 8L,
                () -> Map.of("success", true, "itemId", 8L));
        assertThat(result).containsEntry("success", true).containsKey("auditWarning");
    }

    @Test
    void doesNotExecuteWithoutDurableStartRecord() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("권한 없음")).when(jdbc).execute(anyString());
        boolean[] called = {false};
        Map<String, Object> result = new KrasOperationLogService().execute(
                jdbc, "46870", "land_info", "INGEST", null, () -> {
                    called[0] = true;
                    return Map.of("success", true);
                });
        assertThat(called[0]).isFalse();
        assertThat(result).containsEntry("success", false);
    }
}
