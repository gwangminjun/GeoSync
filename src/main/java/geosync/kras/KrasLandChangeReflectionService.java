package geosync.kras;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * land_change_event(토지이동내역, §10)를 kras.parcel/kras.land_basic 현재 상태에 반영한다.
 * 설계: docs/superpowers/specs/2026-10-07/2026-10-07-kras-incremental-sync-design.md §4.3
 *
 * 이벤트 원본(land_change_event)은 바꾸지 않는다 — 여기서 만드는 현재 상태는 원본에서 언제든
 * 다시 계산할 수 있는 파생값이다. 삭제도 하지 않는다: 소멸한 필지는 SOURCE_CLOSED로만 표시한다.
 *
 * 확인 필요(V-1, V-4): land_mov_no의 전역 유일성, 분할·합병이 실제로 이런 모양(여러 행, 같은
 * bf_pnu 또는 같은 af_pnu)으로 오는지는 운영 응답으로 검증해야 한다. 그 전까지는 kras.md §10
 * 필드 설명에 근거한 가정이다.
 */
final class KrasLandChangeReflectionService {

    private KrasLandChangeReflectionService() {
    }

    /** land_change_event 한 행 — 반영 판단에 필요한 열만 담는다. */
    record EventRow(String beforePnu, String afterPnu, String afLandLocCd, String afLedgGbn, String afBobn,
                     String afBubn, String afJimok, BigDecimal afParea, LocalDate hndlYmd, String landMovNo) {
    }

    record Upsert(String pnu, String landLocCd, String ledgGbn, String bobn, String bubn,
                  String jimok, BigDecimal parea) {
    }

    record Close(String pnu) {
    }

    record ReflectionPlan(List<Upsert> upserts, List<Close> closes) {
    }

    private static final Comparator<EventRow> EVENT_ORDER =
            Comparator.comparing(EventRow::hndlYmd, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(EventRow::landMovNo, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * 반영 규칙 (설계 §4.3):
     * - after_pnu가 있으면 그 필지를 upsert한다 (같은 pnu가 여러 번 나오면 처리일자·순번이 늦은 값으로 덮어쓴다).
     * - before_pnu가 이 배치의 after_pnu 집합에 없으면 그 필지를 close(SOURCE_CLOSED)한다.
     *   (속성만 바뀌는 경우 before==after라 자기 자신은 닫히지 않는다. after가 없는 말소도 같은 규칙으로 닫힌다.)
     */
    static ReflectionPlan decide(List<EventRow> events) {
        List<EventRow> ordered = events.stream().sorted(EVENT_ORDER).toList();

        LinkedHashMap<String, Upsert> upserts = new LinkedHashMap<>();
        LinkedHashSet<String> beforeSeen = new LinkedHashSet<>();
        for (EventRow e : ordered) {
            if (e.afterPnu() != null) {
                upserts.put(e.afterPnu(), new Upsert(e.afterPnu(), e.afLandLocCd(), e.afLedgGbn(), e.afBobn(),
                        e.afBubn(), e.afJimok(), e.afParea()));
            }
            if (e.beforePnu() != null) {
                beforeSeen.add(e.beforePnu());
            }
        }

        Set<String> stillActive = upserts.keySet();
        List<Close> closes = new ArrayList<>();
        for (String bf : beforeSeen) {
            if (!stillActive.contains(bf)) {
                closes.add(new Close(bf));
            }
        }
        return new ReflectionPlan(List.copyOf(upserts.values()), closes);
    }

    /** source_item_id로 쌓인 land_change_event를 읽어 반영한다. promote와 같은 트랜잭션에서 호출한다. */
    static void reflect(JdbcTemplate tx, String orgCd, long itemId) {
        List<EventRow> events = tx.query("""
            SELECT before_pnu, after_pnu, af_land_loc_cd, af_ledg_gbn, af_bobn, af_bubn, af_jimok, af_parea,
                   hndl_ymd, land_mov_no
            FROM kras.land_change_event WHERE source_item_id = ?
            """, (rs, i) -> new EventRow(
                rs.getString("before_pnu"), rs.getString("after_pnu"),
                rs.getString("af_land_loc_cd"), rs.getString("af_ledg_gbn"), rs.getString("af_bobn"),
                rs.getString("af_bubn"), rs.getString("af_jimok"), rs.getBigDecimal("af_parea"),
                rs.getObject("hndl_ymd", LocalDate.class), rs.getString("land_mov_no")),
                itemId);
        apply(tx, orgCd, itemId, decide(events));
    }

    /**
     * source_item_id만 이번 수집 건으로 바꾸고 last_seen_item_id는 건드리지 않는다 — guard_business_row가
     * 두 값이 같은 데이터셋·scope에서 왔는지 검사하므로, land_change 건을 last_seen에 남기면 기존 행 갱신과
     * 이후 토지기본정보(land_basic_file) 재반영이 둘 다 막힌다.
     */
    private static void apply(JdbcTemplate tx, String orgCd, long itemId, ReflectionPlan plan) {
        for (Upsert u : plan.upserts()) {
            tx.update("""
                INSERT INTO kras.parcel(pnu, org_cd, adm_sect_cd, land_loc_cd, ledg_gbn, bobn, bubn,
                                         source_item_id, record_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT (pnu) DO UPDATE SET
                    source_item_id = EXCLUDED.source_item_id, record_status = 'ACTIVE', updated_at = now()
                """, u.pnu(), orgCd, orgCd, u.landLocCd(), u.ledgGbn(), u.bobn(), u.bubn(), itemId);
            tx.update("""
                INSERT INTO kras.land_basic(pnu, jimok, parea, source_item_id, record_status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT (pnu) DO UPDATE SET
                    jimok = EXCLUDED.jimok, parea = EXCLUDED.parea,
                    source_item_id = EXCLUDED.source_item_id, record_status = 'ACTIVE', updated_at = now()
                """, u.pnu(), u.jimok(), u.parea(), itemId);
        }
        for (Close c : plan.closes()) {
            tx.update("""
                UPDATE kras.parcel SET record_status = 'SOURCE_CLOSED', source_item_id = ?, updated_at = now()
                WHERE pnu = ?
                """, itemId, c.pnu());
            tx.update("""
                UPDATE kras.land_basic SET record_status = 'SOURCE_CLOSED', source_item_id = ?, updated_at = now()
                WHERE pnu = ?
                """, itemId, c.pnu());
        }
    }
}
