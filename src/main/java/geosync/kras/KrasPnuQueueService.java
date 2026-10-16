package geosync.kras;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PNU 단위 API 적재 대기열(kras.pnu_work) — 등록·꺼내기·결과 기록·현황 조회 SQL.
 * 처리(호출·수집·반영)는 {@link KrasPnuQueueWorker}가 한다.
 * 설계: docs/superpowers/specs/2026-10-15/2026-10-15-kras-pnu-api-full-incremental-design.md §4·§6·§7.1
 */
@Service
public class KrasPnuQueueService {

    /** 필지 하나로 바로 부르는 서비스 (전체 적재 FULL 대상). */
    static final List<String> PARCEL_DATASETS = List.of(
            "land_info", "shr_ymb", "land_mov_hist", "own_rgt_hist", "land_bldg_check",
            "land_use_plan_attr", "land_use_plan_info", "use_zone",
            "bldg_dong_info", "bldg_ledg_gen_hds_info",
            "land_jiga", "house_info", "fin_dec_jiga", "read_dec_jiga", "land_attr");
    /** 건물번호가 있어야 부르는 서비스 — 동 목록·호 목록 반영 뒤 CHILD로 들어간다. */
    static final List<String> CHILD_DATASETS = List.of("bldg_hds_info", "cbldg_hds_info", "bldg_ho_info",
            "cbldg_dfhs_info");
    static final List<String> ALL_DATASETS;
    /** 기본 목록 — 호 목록·집합 전유부는 실응답 확인(건물 종류 코드·요청 파라미터) 전이라 뺀다(설계 §5). */
    static final List<String> DEFAULT_DATASETS;
    /** 토지이동내역에 나온 필지를 다시 부를 서비스(토지·토지이용·건물존재). */
    static final Set<String> CHANGE_DATASETS = Set.of("land_info", "shr_ymb", "land_mov_hist", "own_rgt_hist",
            "land_bldg_check", "land_use_plan_attr", "land_use_plan_info", "use_zone");
    /** 연 1회 공시 후 갱신 — 순환 재조회에서는 뺀다. */
    static final Set<String> PRICE_DATASETS = Set.of("land_jiga", "house_info", "fin_dec_jiga", "read_dec_jiga",
            "land_attr");

    static {
        List<String> all = new ArrayList<>(PARCEL_DATASETS);
        all.addAll(CHILD_DATASETS);
        ALL_DATASETS = List.copyOf(all);
        List<String> def = new ArrayList<>(all);
        def.removeAll(List.of("bldg_ho_info", "cbldg_dfhs_info"));
        DEFAULT_DATASETS = List.copyOf(def);
    }

    /** 처리기가 꺼낸 한 행. */
    public record Work(String datasetCode, String pnu, String extraKey, String extraParamsJson, Long pendingItemId,
                       int attemptCount) {}

    /** 재시도 간격 — 1회 실패 1시간, 2회 4시간, 3회 1일. 그다음 실패는 FAILED. */
    static final String[] RETRY_DELAYS = {"1 hour", "4 hours", "1 day"};
    static final int LEASE_MINUTES = 10;

    // ── 등록 ──────────────────────────────────────────────────────────

    /**
     * 전체 적재: 현재 필지(ACTIVE) × 켜진 필지 단위 서비스. 대기·재시도 중인 행은 건드리지 않고,
     * refreshDays 안에 받은 행도 건너뛴다. 말소된 필지의 대기 행은 SKIPPED로 바꾼다.
     * @return 새로 넣거나 다시 대기로 돌린 행 수
     */
    public int enqueueFull(JdbcTemplate jdbc, String orgCd, List<String> datasets, int refreshDays) {
        return enqueueFull(jdbc, orgCd, datasets, refreshDays, Map.of());
    }

    /** @param daysByDataset 서비스별 재조회 주기(일). 없는 서비스는 refreshDays. 설계 2026-10-16 §8. */
    public int enqueueFull(JdbcTemplate jdbc, String orgCd, List<String> datasets, int refreshDays,
                           Map<String, Integer> daysByDataset) {
        List<String> targets = datasets.stream().filter(PARCEL_DATASETS::contains).toList();
        skipClosedParcels(jdbc, orgCd);
        if (targets.isEmpty()) return 0;
        return jdbc.update("""
            INSERT INTO kras.pnu_work(org_cd, dataset_code, pnu, reason)
            SELECT p.org_cd, d, p.pnu, 'FULL'
            FROM kras.parcel p CROSS JOIN unnest(?::text[]) AS d
            WHERE p.org_cd=? AND p.record_status='ACTIVE'
            ON CONFLICT (org_cd, dataset_code, pnu, extra_key) DO UPDATE
               SET reason='FULL', status='PENDING', attempt_count=0, next_attempt_at=now(),
                   queued_at=now(), lease_until=NULL, last_error=NULL
             WHERE kras.pnu_work.status IN ('DONE','FAILED','SKIPPED')
               AND (kras.pnu_work.last_success_at IS NULL
                    OR kras.pnu_work.last_success_at < now() - make_interval(days =>
                       coalesce((?::jsonb ->> kras.pnu_work.dataset_code)::int, ?)))
            """, targets.toArray(String[]::new), orgCd, daysJson(daysByDataset), refreshDays);
    }

    /** 토지이동내역 반영 후: since 이후 쌓인 이벤트의 필지(현재 ACTIVE) × 토지·토지이용 계열. */
    public int enqueueChanged(JdbcTemplate jdbc, String orgCd, List<String> datasets, java.time.OffsetDateTime since) {
        List<String> targets = datasets.stream().filter(CHANGE_DATASETS::contains).toList();
        skipClosedParcels(jdbc, orgCd);
        if (targets.isEmpty()) return 0;
        return jdbc.update("""
            INSERT INTO kras.pnu_work(org_cd, dataset_code, pnu, reason)
            SELECT p.org_cd, d, p.pnu, 'CHANGE'
            FROM (SELECT DISTINCT unnest(ARRAY[e.before_pnu, e.after_pnu]) AS pnu
                  FROM kras.land_change_event e WHERE e.org_cd=? AND e.created_at >= ?) c
            JOIN kras.parcel p ON p.pnu=c.pnu AND p.record_status='ACTIVE'
            CROSS JOIN unnest(?::text[]) AS d
            ON CONFLICT (org_cd, dataset_code, pnu, extra_key) DO UPDATE
               SET reason='CHANGE', status='PENDING', attempt_count=0, next_attempt_at=now(),
                   queued_at=now(), lease_until=NULL, last_error=NULL
             WHERE kras.pnu_work.lease_until IS NULL OR kras.pnu_work.lease_until < now()
            """, orgCd, since, targets.toArray(String[]::new));
    }

    /**
     * 순환 재조회: refreshDays보다 오래된 완료 행을 오래된 순으로 limit건 다시 대기로. 가격 계열은 빼고,
     * 전체 적재(FULL) 잔량이 남아 있으면 넣지 않는다(초기 적재가 먼저).
     */
    public int enqueueRotation(JdbcTemplate jdbc, String orgCd, List<String> datasets, int refreshDays, int limit) {
        return enqueueRotation(jdbc, orgCd, datasets, refreshDays, Map.of(), limit);
    }

    /**
     * 서비스별 주기. 하루 한도 안에서는 마감(last_success_at + 주기)이 가장 오래 지난 것부터 뽑는다 —
     * 가장 오래전에 받은 순이면 90일 서비스의 오래된 행이 14일 서비스보다 먼저 뽑히기 때문(설계 2026-10-16 §8.3).
     */
    public int enqueueRotation(JdbcTemplate jdbc, String orgCd, List<String> datasets, int refreshDays,
                               Map<String, Integer> daysByDataset, int limit) {
        List<String> targets = datasets.stream().filter(d -> !PRICE_DATASETS.contains(d)).toList();
        if (targets.isEmpty() || limit <= 0 || fullBacklog(jdbc, orgCd) > 0) return 0;
        return jdbc.update("""
            UPDATE kras.pnu_work w SET reason='ROTATE', status='PENDING', attempt_count=0, next_attempt_at=now(),
                   queued_at=now(), lease_until=NULL, last_error=NULL
            FROM (SELECT org_cd, dataset_code, pnu, extra_key FROM kras.pnu_work
                  WHERE org_cd=? AND status='DONE' AND dataset_code = ANY(?::text[])
                    AND last_success_at + """ + DAYS + """
             < now()
                  ORDER BY last_success_at + """ + DAYS + """
             LIMIT ?) o
            WHERE (w.org_cd, w.dataset_code, w.pnu, w.extra_key) = (o.org_cd, o.dataset_code, o.pnu, o.extra_key)
            """, orgCd, targets.toArray(String[]::new), daysJson(daysByDataset), refreshDays,
                daysJson(daysByDataset), refreshDays, limit);
    }

    /** 재조회 마감이 지난 완료 행 수(가격 제외) — 하루 한도보다 많으면 주기가 밀린다는 안내용. */
    public long overdue(JdbcTemplate jdbc, String orgCd, List<String> datasets, int refreshDays,
                        Map<String, Integer> daysByDataset) {
        List<String> targets = datasets.stream().filter(d -> !PRICE_DATASETS.contains(d)).toList();
        if (targets.isEmpty()) return 0;
        Long n = jdbc.queryForObject("""
            SELECT count(*) FROM kras.pnu_work
            WHERE org_cd=? AND status='DONE' AND dataset_code = ANY(?::text[])
              AND last_success_at + """ + DAYS + """
             < now()
            """, Long.class, orgCd, targets.toArray(String[]::new), daysJson(daysByDataset), refreshDays);
        return n == null ? 0 : n;
    }

    /** 행의 서비스별 주기 — 바인딩 2개: 서비스별 일수 jsonb, 기본 일수. */
    private static final String DAYS = "make_interval(days => coalesce((?::jsonb ->> dataset_code)::int, ?))";

    /** {서비스: 일} → jsonb 문자열. 키는 서비스 코드 형식만 통과시킨다(설정 파일을 직접 고친 경우 대비). */
    static String daysJson(Map<String, Integer> daysByDataset) {
        return daysByDataset.entrySet().stream()
                .filter(e -> e.getKey().matches("[a-z0-9_]+") && e.getValue() != null)
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    /** 연 1회 가격: 올해 기준일이 지났으면, 그 뒤로 아직 받지 않은 가격 계열 완료 행을 다시 대기로. */
    public int enqueueAnnual(JdbcTemplate jdbc, String orgCd, List<String> datasets, String priceRefreshAfter,
                             LocalDate today) {
        LocalDate since = MonthDay.parse("--" + priceRefreshAfter).atYear(today.getYear());
        List<String> targets = datasets.stream().filter(PRICE_DATASETS::contains).toList();
        if (targets.isEmpty() || today.isBefore(since)) return 0;
        return jdbc.update("""
            UPDATE kras.pnu_work SET reason='ANNUAL', status='PENDING', attempt_count=0, next_attempt_at=now(),
                   queued_at=now(), lease_until=NULL, last_error=NULL
            WHERE org_cd=? AND status='DONE' AND dataset_code = ANY(?::text[]) AND last_success_at < ?::date
            """, orgCd, targets.toArray(String[]::new), since);
    }

    /**
     * 건물 하위 조회: 동 목록(bldg_dong_info) 반영 뒤 그 필지의 건물마다, 호 목록(bldg_ho_info) 반영 뒤 호마다 넣는다.
     * 건물 종류 코드 1=총괄(추가 없음) 2=일반(표제부 014) 그 외=집합(표제부 015 + 호 목록 103). 샘플 BLDG_DONG_LIST.XML 기준.
     */
    public int enqueueChildren(JdbcTemplate jdbc, String orgCd, String parentDataset, String pnu,
                               List<String> datasets) {
        String sql = switch (parentDataset) {
            case "bldg_dong_info" -> """
                SELECT d, b.bldg_gbn_no FROM kras.building_register b
                CROSS JOIN LATERAL unnest(CASE WHEN b.bldg_kind_cd='2' THEN ARRAY['bldg_hds_info']
                                               WHEN b.bldg_kind_cd='1' THEN ARRAY[]::text[]
                                               ELSE ARRAY['cbldg_hds_info','bldg_ho_info'] END) AS d
                WHERE b.pnu=? AND b.record_status='ACTIVE' AND coalesce(b.bldg_gbn_no,'')<>''
                """;
            case "bldg_ho_info" -> """
                SELECT 'cbldg_dfhs_info', u.bldg_gbn_no FROM kras.building_unit u
                WHERE u.pnu=? AND u.record_status='ACTIVE' AND coalesce(u.bldg_gbn_no,'')<>''
                """;
            default -> null;
        };
        if (sql == null) return 0;
        int n = 0;
        for (String[] r : jdbc.query(sql, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, pnu)) {
            String ds = r[0];
            String bno = r[1];
            if (!datasets.contains(ds)) continue;
            n += jdbc.update("""
                INSERT INTO kras.pnu_work(org_cd, dataset_code, pnu, extra_key, extra_params, reason)
                VALUES (?, ?, ?, ?, jsonb_build_object('bldg_gbn_no', ?::text), 'CHILD')
                ON CONFLICT (org_cd, dataset_code, pnu, extra_key) DO UPDATE
                   SET reason='CHILD', status='PENDING', attempt_count=0, next_attempt_at=now(),
                       queued_at=now(), lease_until=NULL, last_error=NULL
                 WHERE kras.pnu_work.status IN ('DONE','FAILED','SKIPPED')
                """, orgCd, ds, pnu, bno, bno);
        }
        return n;
    }

    private void skipClosedParcels(JdbcTemplate jdbc, String orgCd) {
        jdbc.update("""
            UPDATE kras.pnu_work w SET status='SKIPPED', lease_until=NULL, last_error='말소·미관측 필지'
            FROM kras.parcel p
            WHERE w.org_cd=? AND p.pnu=w.pnu AND p.record_status<>'ACTIVE' AND w.status IN ('PENDING','RETRY')
            """, orgCd);
    }

    // ── 꺼내기·결과 ────────────────────────────────────────────────────

    /** 다음 처리할 행을 꺼내 lease를 건다(바로 커밋). 없으면 null. 순서: CHANGE → CHILD → FULL → ANNUAL → ROTATE. */
    public Work claim(JdbcTemplate jdbc, String orgCd, List<String> runnable) {
        List<Work> rows = jdbc.query("""
            UPDATE kras.pnu_work w SET attempt_count=w.attempt_count+1,
                   lease_until=now() + make_interval(mins => ?)
            FROM (SELECT org_cd, dataset_code, pnu, extra_key FROM kras.pnu_work
                  WHERE org_cd=? AND status IN ('PENDING','RETRY') AND next_attempt_at <= now()
                    AND (lease_until IS NULL OR lease_until < now()) AND dataset_code = ANY(?::text[])
                  ORDER BY priority, queued_at LIMIT 1 FOR UPDATE SKIP LOCKED) n
            WHERE (w.org_cd, w.dataset_code, w.pnu, w.extra_key) = (n.org_cd, n.dataset_code, n.pnu, n.extra_key)
            RETURNING w.dataset_code, w.pnu, w.extra_key, w.extra_params::text, w.pending_item_id, w.attempt_count
            """, (rs, i) -> new Work(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                (Long) rs.getObject(5), rs.getInt(6)), LEASE_MINUTES, orgCd, runnable.toArray(String[]::new));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 수집이 커밋됨 — 반영 전에 끊겨도 다음에는 다시 부르지 않고 반영만 한다. */
    public void markCollected(JdbcTemplate jdbc, String orgCd, Work w, long itemId) {
        jdbc.update("UPDATE kras.pnu_work SET pending_item_id=? WHERE " + KEY, itemId, orgCd, w.datasetCode(),
                w.pnu(), w.extraKey());
    }

    public void markDone(JdbcTemplate jdbc, String orgCd, Work w, Long itemId) {
        jdbc.update("""
            UPDATE kras.pnu_work SET status='DONE', attempt_count=0, lease_until=NULL, pending_item_id=NULL,
                   last_success_at=now(), last_item_id=coalesce(?, last_item_id), last_error=NULL
            WHERE\s""" + KEY, itemId, orgCd, w.datasetCode(), w.pnu(), w.extraKey());
    }

    /** 실패 — 재시도 간격이 남았으면 RETRY, 다 썼으면(또는 retry=false) FAILED. */
    public void markFailed(JdbcTemplate jdbc, String orgCd, Work w, String error, boolean retry) {
        boolean giveUp = !retry || w.attemptCount() > RETRY_DELAYS.length;
        String delay = RETRY_DELAYS[Math.min(w.attemptCount(), RETRY_DELAYS.length) - 1];
        String msg = error == null ? "알 수 없는 오류" : error.length() > 500 ? error.substring(0, 500) : error;
        jdbc.update("""
            UPDATE kras.pnu_work SET status=?, lease_until=NULL, last_error=?,
                   pending_item_id=CASE WHEN ? THEN NULL ELSE pending_item_id END,
                   next_attempt_at=now() + ?::interval
            WHERE\s""" + KEY, giveUp ? "FAILED" : "RETRY", msg, !retry, delay, orgCd, w.datasetCode(), w.pnu(),
                w.extraKey());
    }

    /** 처리하지 않고 돌려놓음(중단 시) — 시도 횟수도 되돌린다. */
    public void release(JdbcTemplate jdbc, String orgCd, Work w) {
        jdbc.update("UPDATE kras.pnu_work SET lease_until=NULL, attempt_count=greatest(attempt_count-1,0) WHERE "
                + KEY, orgCd, w.datasetCode(), w.pnu(), w.extraKey());
    }

    private static final String KEY = "org_cd=? AND dataset_code=? AND pnu=? AND extra_key=?";

    // ── 화면 ──────────────────────────────────────────────────────────

    public boolean tableExists(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT to_regclass('kras.pnu_work')::text", String.class) != null;
    }

    public long fullBacklog(JdbcTemplate jdbc, String orgCd) {
        Long n = jdbc.queryForObject("""
            SELECT count(*) FROM kras.pnu_work WHERE org_cd=? AND reason='FULL' AND status IN ('PENDING','RETRY')
            """, Long.class, orgCd);
        return n == null ? 0 : n;
    }

    /** 지금 처리할 수 없는 서비스(실행 조건 미충족·꺼짐)에 남은 대기·재시도 행 수. */
    public long waitingOutside(JdbcTemplate jdbc, String orgCd, List<String> runnable) {
        Long n = jdbc.queryForObject("""
            SELECT count(*) FROM kras.pnu_work WHERE org_cd=? AND status IN ('PENDING','RETRY')
               AND NOT (dataset_code = ANY(?::text[]))
            """, Long.class, orgCd, runnable.toArray(String[]::new));
        return n == null ? 0 : n;
    }

    /** 숫자 칸·서비스별 표·진행률. 설계 §7.1. */
    public Map<String, Object> summary(JdbcTemplate jdbc, String orgCd) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(jdbc.queryForMap("""
            SELECT count(*) FILTER (WHERE status IN ('PENDING','RETRY') AND lease_until >= now()) AS running,
                   count(*) FILTER (WHERE status='PENDING' AND (lease_until IS NULL OR lease_until < now())) AS pending,
                   count(*) FILTER (WHERE status='RETRY' AND (lease_until IS NULL OR lease_until < now())) AS retry,
                   count(*) FILTER (WHERE status='FAILED') AS failed,
                   count(*) FILTER (WHERE status='SKIPPED') AS skipped,
                   count(*) FILTER (WHERE status='DONE') AS done,
                   count(*) FILTER (WHERE status IN ('PENDING','RETRY') AND lease_until < now()) AS interrupted,
                   count(*) FILTER (WHERE reason='FULL') AS full_total,
                   count(*) FILTER (WHERE reason='FULL' AND status IN ('DONE','SKIPPED','FAILED')) AS full_finished,
                   count(*) FILTER (WHERE last_success_at >= now() - interval '1 hour') AS last_hour,
                   count(*) FILTER (WHERE last_success_at >= current_date) AS today
            FROM kras.pnu_work WHERE org_cd=?
            """, orgCd));
        out.put("datasets", jdbc.queryForList("""
            SELECT dataset_code,
                   count(*) FILTER (WHERE status='PENDING') AS pending,
                   count(*) FILTER (WHERE status='RETRY') AS retry,
                   count(*) FILTER (WHERE status='FAILED') AS failed,
                   count(*) FILTER (WHERE status='DONE') AS done,
                   to_char(max(last_success_at), 'YYYY-MM-DD HH24:MI') AS last_success
            FROM kras.pnu_work WHERE org_cd=? GROUP BY dataset_code ORDER BY dataset_code
            """, orgCd));
        return out;
    }

    /** 목록(최근 50건). kind: RETRY(재시도 대기) / FAILED / RUNNING(처리 중). q는 PNU 또는 서비스 일부. */
    public List<Map<String, Object>> rows(JdbcTemplate jdbc, String orgCd, String kind, String q) {
        String where = switch (kind) {
            case "FAILED" -> "status='FAILED'";
            case "RUNNING" -> "status IN ('PENDING','RETRY') AND lease_until IS NOT NULL";
            default -> "status='RETRY'";
        };
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim() + "%";
        return jdbc.queryForList("""
            SELECT pnu, dataset_code, extra_key, reason, attempt_count,
                   to_char(next_attempt_at, 'YYYY-MM-DD HH24:MI') AS next_attempt,
                   greatest(0, ceil(extract(epoch FROM next_attempt_at - now()) / 60))::int AS next_in_min,
                   to_char(lease_until - make_interval(mins => ?), 'YYYY-MM-DD HH24:MI') AS started,
                   lease_until < now() AS stalled, last_error
            FROM kras.pnu_work WHERE org_cd=? AND\s""" + where + """
             AND (pnu LIKE ? OR dataset_code LIKE ?)
            ORDER BY next_attempt_at DESC LIMIT 50
            """, LEASE_MINUTES, orgCd, like, like);
    }

    /**
     * 행 하나의 상세(대기열 칸 전부 + 그 필지의 최근 수집 이력 10건). 없으면 null.
     * 수집 이력은 수집 때와 같은 식(kras.entity_key)으로 scope_key를 만들어 인덱스(dataset_code, scope_key)로 찾는다.
     */
    public Map<String, Object> detail(JdbcTemplate jdbc, String orgCd, String datasetCode, String pnu, String extraKey) {
        List<Map<String, Object>> found = jdbc.queryForList("""
            SELECT dataset_code, pnu, extra_key, extra_params::text AS extra_params, reason, status, attempt_count,
                   to_char(queued_at, 'YYYY-MM-DD HH24:MI') AS queued,
                   to_char(next_attempt_at, 'YYYY-MM-DD HH24:MI') AS next_attempt,
                   to_char(lease_until - make_interval(mins => ?), 'YYYY-MM-DD HH24:MI') AS started,
                   lease_until IS NOT NULL AND status IN ('PENDING','RETRY') AS running,
                   lease_until < now() AS stalled,
                   to_char(last_success_at, 'YYYY-MM-DD HH24:MI') AS last_success,
                   pending_item_id, last_item_id, last_error,
                   kras.entity_key(jsonb_build_object('pnu', pnu::text) || extra_params) AS scope_key
            FROM kras.pnu_work WHERE\s""" + KEY, LEASE_MINUTES, orgCd, datasetCode, pnu, extraKey);
        if (found.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>(found.get(0));
        out.put("items", jdbc.queryForList("""
            SELECT item_id, attempt_no, status, to_char(requested_at, 'YYYY-MM-DD HH24:MI') AS requested,
                   rows_received, rows_applied, error_message
            FROM kras.sync_item WHERE org_cd=? AND dataset_code=? AND scope_key=?
            ORDER BY item_id DESC LIMIT 10
            """, orgCd, datasetCode, out.remove("scope_key")));
        return out;
    }

    /** 실패 행 재시도 — keys가 비면 전부. key = dataset_code|pnu|extra_key. */
    public int retryFailed(JdbcTemplate jdbc, String orgCd, List<String> keys) {
        String reset = """
            UPDATE kras.pnu_work SET status='PENDING', attempt_count=0, next_attempt_at=now(), lease_until=NULL,
                   pending_item_id=NULL
            WHERE org_cd=? AND status='FAILED'""";
        if (keys.isEmpty()) return jdbc.update(reset, orgCd);
        int n = 0;
        for (String key : keys) {
            String[] k = key.split("\\|", -1);
            if (k.length != 3) continue;
            n += jdbc.update(reset + " AND dataset_code=? AND pnu=? AND extra_key=?", orgCd, k[0], k[1], k[2]);
        }
        return n;
    }
}
