package geosync.kras;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static geosync.kras.KrasLandChangeReflectionService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * decide()는 설계 문서(2026-10-07-kras-incremental-sync-design.md §4.3)의 반영 규칙을 그대로
 * 검증한다: after_pnu는 upsert, 이 배치의 어떤 after_pnu에도 없는 before_pnu는 close.
 * 운영 응답으로 확인 전인 가정(V-4: 분할·합병이 여러 행으로 온다)에 기반한 테스트다.
 */
class KrasLandChangeReflectionServiceTest {

    private static EventRow event(String bf, String af, LocalDate hndlYmd, String landMovNo) {
        return new EventRow(bf, af, "10200", "1", "0399", "0036", "08", new BigDecimal("100"), hndlYmd, landMovNo);
    }

    @Test
    void attributeOnlyChangeUpsertsWithoutClosingItself() {
        // 분할·합병이 아니라 지목처럼 속성만 바뀐 경우: before == after
        ReflectionPlan plan = decide(List.of(event("A", "A", LocalDate.of(2026, 10, 1), "1")));

        assertThat(plan.upserts()).extracting(Upsert::pnu).containsExactly("A");
        assertThat(plan.closes()).isEmpty();
    }

    @Test
    void splitProducesTwoUpsertsAndClosesTheOriginal() {
        // 필지 A가 B, C로 분할: 같은 before_pnu를 가진 이벤트 두 건
        ReflectionPlan plan = decide(List.of(
                event("A", "B", LocalDate.of(2026, 10, 1), "1"),
                event("A", "C", LocalDate.of(2026, 10, 1), "2")));

        assertThat(plan.upserts()).extracting(Upsert::pnu).containsExactlyInAnyOrder("B", "C");
        assertThat(plan.closes()).extracting(Close::pnu).containsExactly("A");
    }

    @Test
    void mergeProducesOneUpsertAndClosesBothOriginals() {
        // 필지 A, B가 C로 합병
        ReflectionPlan plan = decide(List.of(
                event("A", "C", LocalDate.of(2026, 10, 1), "1"),
                event("B", "C", LocalDate.of(2026, 10, 1), "2")));

        assertThat(plan.upserts()).extracting(Upsert::pnu).containsExactly("C");
        assertThat(plan.closes()).extracting(Close::pnu).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void mergeIntoOneOfTheOriginalsDoesNotCloseThatOne() {
        // A, B가 합병되어 A로 남는 경우: A는 닫히지 않고 B만 닫힌다
        ReflectionPlan plan = decide(List.of(
                event("A", "A", LocalDate.of(2026, 10, 1), "1"),
                event("B", "A", LocalDate.of(2026, 10, 1), "2")));

        assertThat(plan.upserts()).extracting(Upsert::pnu).containsExactly("A");
        assertThat(plan.closes()).extracting(Close::pnu).containsExactly("B");
    }

    @Test
    void pureDeletionWithNoSuccessorOnlyCloses() {
        // after_pnu가 없는 말소
        ReflectionPlan plan = decide(List.of(event("A", null, LocalDate.of(2026, 10, 1), "1")));

        assertThat(plan.upserts()).isEmpty();
        assertThat(plan.closes()).extracting(Close::pnu).containsExactly("A");
    }

    @Test
    void newParcelWithNoPredecessorOnlyUpserts() {
        // before_pnu가 없는 신규 등록
        ReflectionPlan plan = decide(List.of(event(null, "B", LocalDate.of(2026, 10, 1), "1")));

        assertThat(plan.upserts()).extracting(Upsert::pnu).containsExactly("B");
        assertThat(plan.closes()).isEmpty();
    }

    @Test
    void eventWithNeitherPnuProducesNoAction() {
        ReflectionPlan plan = decide(List.of(event(null, null, LocalDate.of(2026, 10, 1), "1")));

        assertThat(plan.upserts()).isEmpty();
        assertThat(plan.closes()).isEmpty();
    }

    @Test
    void laterEventByDateWinsWhenSamePnuAppearsTwice() {
        EventRow earlier = new EventRow("A", "A", "10200", "1", "0399", "0036", "05",
                new BigDecimal("100"), LocalDate.of(2026, 10, 1), "1");
        EventRow later = new EventRow("A", "A", "10200", "1", "0399", "0036", "08",
                new BigDecimal("120"), LocalDate.of(2026, 10, 5), "2");

        ReflectionPlan plan = decide(List.of(later, earlier)); // 입력 순서를 섞어도 날짜로 정렬돼야 한다

        assertThat(plan.upserts()).hasSize(1);
        assertThat(plan.upserts().get(0).jimok()).isEqualTo("08");
        assertThat(plan.upserts().get(0).parea()).isEqualByComparingTo("120");
    }

    @Test
    void sameDateOrdersByLandMovNo() {
        EventRow first = new EventRow("A", "A", "10200", "1", "0399", "0036", "05",
                new BigDecimal("100"), LocalDate.of(2026, 10, 1), "1");
        EventRow second = new EventRow("A", "A", "10200", "1", "0399", "0036", "09",
                new BigDecimal("200"), LocalDate.of(2026, 10, 1), "2");

        ReflectionPlan plan = decide(List.of(second, first));

        assertThat(plan.upserts().get(0).jimok()).isEqualTo("09");
    }

    @Test
    void reflectingTheSamePlanTwiceIsIdempotent() {
        // apply()가 ON CONFLICT 업서트 + 단순 UPDATE만 쓰므로, 같은 이벤트를 두 번 반영해도 같은 SQL이 같은 횟수만큼 나간다.
        JdbcTemplate tx = mock(JdbcTemplate.class);
        when(tx.query(anyString(), ArgumentMatchers.<RowMapper<EventRow>>any(), eq(5L)))
                .thenReturn(List.of(event("A", "B", LocalDate.of(2026, 10, 1), "1")));

        reflect(tx, "12830", 5L);
        reflect(tx, "12830", 5L);

        verify(tx, times(2)).update(contains("INSERT INTO kras.parcel"),
                eq("B"), eq("12830"), eq("12830"), eq("10200"), eq("1"), eq("0399"), eq("0036"), eq(5L));
        verify(tx, times(2)).update(contains("INSERT INTO kras.land_basic"),
                eq("B"), eq("08"), eq(new BigDecimal("100")), eq(5L));
    }

    @Test
    void reflectQueriesEventsForTheGivenItemAndAppliesClose() {
        JdbcTemplate tx = mock(JdbcTemplate.class);
        when(tx.query(anyString(), ArgumentMatchers.<RowMapper<EventRow>>any(), eq(7L)))
                .thenReturn(List.of(event("A", null, LocalDate.of(2026, 10, 1), "1")));

        reflect(tx, "12830", 7L);

        verify(tx).update(contains("UPDATE kras.parcel"), eq(7L), eq("A"));
        verify(tx).update(contains("UPDATE kras.land_basic"), eq(7L), eq("A"));
        verify(tx, never()).update(contains("INSERT INTO kras.parcel"), any(Object[].class));
    }

    @Test
    void landChangeMapperDelegatesAfterPromoteToReflectionService() {
        // KrasDateRangeIngestService가 promote 직후 호출하는 지점 — 배선만 확인한다.
        JdbcTemplate tx = mock(JdbcTemplate.class);
        when(tx.query(anyString(), ArgumentMatchers.<RowMapper<EventRow>>any(), eq(9L))).thenReturn(List.of());

        new LandChangeMapper("", "", "", "yyyyMMdd").afterPromote(tx, "12830", 9L);

        verify(tx).query(contains("FROM kras.land_change_event"), ArgumentMatchers.<RowMapper<EventRow>>any(), eq(9L));
    }
}
