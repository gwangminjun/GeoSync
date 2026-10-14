package geosync.kras;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 10일 제한(kras.md §10) 같은 maxQueryDays를 넘는 기간을 청크로 나누는 로직.
 * 설계: docs/superpowers/specs/2026-10-07/2026-10-07-kras-incremental-sync-design.md §4.2
 */
class KrasDateRangeWindowSplitTest {

    @Test
    void windowWithinLimitStaysAsOneChunk() {
        List<LocalDate[]> chunks = KrasDateRangeIngestService.splitWindows(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 10);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10));
    }

    @Test
    void oneDayOverLimitSplitsIntoTwoChunks() {
        List<LocalDate[]> chunks = KrasDateRangeIngestService.splitWindows(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 11), 10);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10));
        assertThat(chunks.get(1)).containsExactly(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 11));
    }

    @Test
    void twentyOneDaysSplitsIntoThreeChunksNoGapNoOverlap() {
        List<LocalDate[]> chunks = KrasDateRangeIngestService.splitWindows(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 21), 10);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10));
        assertThat(chunks.get(1)).containsExactly(LocalDate.of(2026, 1, 11), LocalDate.of(2026, 1, 20));
        assertThat(chunks.get(2)).containsExactly(LocalDate.of(2026, 1, 21), LocalDate.of(2026, 1, 21));
    }

    @Test
    void singleDayWindowIsOneChunk() {
        List<LocalDate[]> chunks = KrasDateRangeIngestService.splitWindows(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), 10);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));
    }

    @Test
    void startAfterEndThrows() {
        assertThatThrownBy(() -> KrasDateRangeIngestService.splitWindows(
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveMaxDaysThrows() {
        assertThatThrownBy(() -> KrasDateRangeIngestService.splitWindows(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextWindowStartReturnsEmptyWhenNothingWasReflected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(LocalDate.class), eq("12830"), eq("land_change"))).thenReturn(null);

        Optional<LocalDate> next = new KrasDateRangeIngestService(null, null)
                .nextWindowStart(jdbc, "12830", new LandChangeMapper("", "", "", ""));

        assertThat(next).isEmpty();
    }

    @Test
    void nextWindowStartCountsOnlyReflectedOrEmptyWindows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(LocalDate.class), eq("12830"), eq("land_change")))
                .thenReturn(LocalDate.of(2026, 10, 6));

        Optional<LocalDate> next = new KrasDateRangeIngestService(null, null)
                .nextWindowStart(jdbc, "12830", new LandChangeMapper("", "", "", ""));

        assertThat(next).contains(LocalDate.of(2026, 10, 6));
        verify(jdbc).queryForObject(argThat((String sql) -> sql.contains("rows_valid = 0")
                && sql.contains("FROM kras.land_change_event b WHERE b.source_item_id = si.item_id")),
                eq(LocalDate.class), eq("12830"), eq("land_change"));
    }
}
