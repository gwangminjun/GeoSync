package geosync.kras;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * /kras-db 적재 DB 테이블 조회 팝업. 화면에서 받은 이름으로 SQL을 만들므로
 * 테이블은 허용 목록, 칼럼은 information_schema 결과로만 받고 검색어는 바인딩한다. 읽기 전용·5초 제한.
 * 설계: docs/superpowers/specs/2026-10-15/2026-10-15-kras-db-table-popup-design.md
 */
final class KrasTableBrowser {

    static final int PAGE_SIZE = 50;
    static final int COUNT_CAP = 10_000;
    static final int MAX_PAGE = COUNT_CAP / PAGE_SIZE - 1;
    /**
     * 지도 미리보기에서 행 한 건을 고를 때 쓰는 칼럼(앞에 있는 것 우선). 미리보기 테이블엔 PK가 없어 실측으로 정했다:
     * lt_c_uzone·그 뷰는 mnum, lp_pa_cbnd는 pnu가 행마다 다르다(2026-10-15 dev DB). 지오서버 레이어에도 같은 칼럼이 있다.
     */
    static final List<String> ROW_KEY_COLUMNS = List.of("mnum", "pnu", "uid");

    /** 소유자 개인정보 칼럼 — 서버에서 가리고 검색 칼럼에서 뺀다. */
    static final Set<String> NAME_COLUMNS = Set.of("owner_nm");
    static final Set<String> ADDR_COLUMNS = Set.of("owner_addr", "detl_addr");
    static final Set<String> REGNO_COLUMNS = Set.of("dregno", "owner_regno");
    private static final Pattern PII_JSON_VALUE = Pattern.compile(
            "(?i)(\"(?:owner_nm|owner_addr|detl_addr|dregno|owner_regno)\"\\s*:\\s*)\"[^\"]*\"");

    private static final Set<String> TEXT_TYPES = Set.of("varchar", "text", "bpchar");
    private static final Set<String> NUMBER_TYPES = Set.of("int2", "int4", "int8", "numeric", "float4", "float8");
    private static final Set<String> TIME_TYPES = Set.of("date", "timestamp", "timestamptz");

    record Column(String name, String type, boolean searchable, boolean masked) {}

    /** 화면에 보여줄 오류(400) — 메시지를 그대로 내보낸다. */
    static class BrowseException extends RuntimeException {
        BrowseException(String message) { super(message); }
    }

    private KrasTableBrowser() {}

    /** 규격 표 "적재 DB 테이블" 문자열(`a, b → c`)을 테이블 이름들로 나눈다. */
    static List<String> splitTables(String targetTable) {
        if (targetTable == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : targetTable.split("[,→]")) {
            String t = part.trim();
            if (t.matches("[a-z_]+\\.[a-z0-9_]+")) out.add(t);
        }
        return out;
    }

    private static final Pattern TABLE_NAME = Pattern.compile("[a-z_]+\\.[a-z0-9_]+");
    /** 뷰 정의가 `WHERE ((칼럼)::text = '값'::text)` 하나뿐이면 원본을 칼럼별로 한 번에 세어 건수를 붙인다. */
    static final Pattern SIMPLE_VIEW_FILTER = Pattern.compile(
            "WHERE\\s*\\(\\((\\w+)\\)::text\\s*=\\s*'([^']*)'::text\\)\\s*;?\\s*$");

    static Map<String, Object> browse(JdbcTemplate jdbc, Set<String> allowed, String table, String orgCd,
                                      String col, String q, int page) {
        if (table == null || !TABLE_NAME.matcher(table).matches()) throw new BrowseException("조회할 수 없는 테이블입니다: " + table);
        if (page < 0 || page > MAX_PAGE) throw new BrowseException("페이지는 1–" + (MAX_PAGE + 1) + "까지만 볼 수 있습니다.");
        String[] parts = table.split("\\.");
        return readOnly(jdbc, tx -> {
            // 허용 테이블에서 파생된 뷰(예: lt_c_uzone → v_lt_c_uzone_ub201)도 같은 규칙으로 볼 수 있다.
            if (!allowed.contains(table) && baseOfView(tx, parts[0], parts[1], allowed) == null) {
                throw new BrowseException("조회할 수 없는 테이블입니다: " + table);
            }
            return query(tx, table, parts[0], parts[1], orgCd, col, q, page);
        });
    }

    /**
     * 허용 테이블을 원본으로 쓰는 뷰 목록. 단순 필터 뷰는 현재 기관 건수와 필터 값(레이어 코드 등)을 붙인다.
     * 건수 많은 순 → 이름순.
     */
    static List<Map<String, Object>> views(JdbcTemplate jdbc, Set<String> allowed, String table, String orgCd) {
        if (table == null || !allowed.contains(table)) throw new BrowseException("조회할 수 없는 테이블입니다: " + table);
        String[] parts = table.split("\\.");
        return readOnly(jdbc, tx -> {
            List<Map<String, Object>> views = tx.queryForList("""
                    SELECT u.view_schema || '.' || u.view_name AS name,
                           pg_get_viewdef(format('%I.%I', u.view_schema, u.view_name)::regclass) AS def
                    FROM information_schema.view_table_usage u
                    WHERE u.table_schema=? AND u.table_name=? ORDER BY 1
                    """, parts[0], parts[1]);
            boolean orgFiltered = tx.queryForObject("""
                    SELECT count(*) > 0 FROM information_schema.columns
                    WHERE table_schema=? AND table_name=? AND column_name='org_cd'
                    """, Boolean.class, parts[0], parts[1]);
            Map<String, Map<String, Long>> countsByColumn = new LinkedHashMap<>();
            Map<String, String> titles = layerTitles(tx);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> v : views) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", v.get("name"));
                row.put("title", titles.get((String) v.get("name")));
                var m = SIMPLE_VIEW_FILTER.matcher(String.valueOf(v.get("def")).trim());
                if (m.find()) {
                    String filterCol = m.group(1);
                    row.put("filter", filterCol + " = " + m.group(2));
                    Map<String, Long> counts = countsByColumn.computeIfAbsent(filterCol,
                            c -> groupCounts(tx, parts[0], parts[1], c, orgFiltered ? orgCd : null));
                    row.put("total", counts.getOrDefault(m.group(2), 0L));
                } else {
                    row.put("filter", null);
                    row.put("total", null);
                }
                out.add(row);
            }
            out.sort((a, b) -> Long.compare(b.get("total") == null ? -1 : (Long) b.get("total"),
                    a.get("total") == null ? -1 : (Long) a.get("total")));
            return out;
        });
    }

    /**
     * 지도 레이어 목록(public.dhc_layer)의 표시 이름 — "스키마.뷰" → title. 사용 중(use_yn='Y')인 것을 앞에 둔다.
     * dhc_layer가 없는 DB에서는 빈 맵(이름 없이 코드만 보인다).
     */
    private static Map<String, String> layerTitles(JdbcTemplate tx) {
        Map<String, String> titles = new LinkedHashMap<>();
        if (tx.queryForObject("SELECT to_regclass('public.dhc_layer')::text", String.class) == null) return titles;
        tx.query("""
                SELECT DISTINCT ON (1) coalesce(nullif(tb_schema, ''), 'public') || '.' || tb_name, title
                FROM public.dhc_layer WHERE tb_name IS NOT NULL AND title IS NOT NULL
                ORDER BY 1, (use_yn = 'Y') DESC, reg_date DESC NULLS LAST
                """, rs -> { titles.put(rs.getString(1), rs.getString(2)); });
        return titles;
    }

    private static Map<String, Long> groupCounts(JdbcTemplate tx, String schema, String name, String col, String orgCd) {
        boolean known = tx.queryForObject("SELECT count(*) > 0 FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name=? AND column_name=?", Boolean.class, schema, name, col);
        if (!known) return Map.of();
        Map<String, Long> counts = new LinkedHashMap<>();
        tx.query("SELECT " + quote(col) + "::text, count(*) FROM " + quote(schema) + "." + quote(name)
                        + (orgCd == null ? "" : " WHERE \"org_cd\" = ?") + " GROUP BY 1",
                rs -> { counts.put(rs.getString(1), rs.getLong(2)); },
                orgCd == null ? new Object[0] : new Object[]{orgCd});
        return counts;
    }

    /** schema.name이 허용 테이블 중 하나를 원본으로 쓰는 뷰면 그 원본, 아니면 null. */
    private static String baseOfView(JdbcTemplate tx, String schema, String name, Set<String> allowed) {
        List<String> bases = tx.queryForList("""
                SELECT table_schema || '.' || table_name FROM information_schema.view_table_usage
                WHERE view_schema=? AND view_name=?
                """, String.class, schema, name);
        return bases.stream().filter(allowed::contains).findFirst().orElse(null);
    }

    private static <T> T readOnly(JdbcTemplate jdbc, TxWork<T> work) {
        try {
            return jdbc.execute((ConnectionCallback<T>) con -> inReadOnly(con, work));
        } catch (DataAccessException e) {
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            if (root instanceof SQLException se && "57014".equals(se.getSQLState())) {
                throw new BrowseException("검색이 오래 걸려 멈췄습니다(5초). 검색어를 더 좁혀 주세요.");
            }
            throw e;
        }
    }

    private interface TxWork<T> { T run(JdbcTemplate tx); }

    private static <T> T inReadOnly(Connection con, TxWork<T> work) throws SQLException {
        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(con, true));
            tx.execute("SET TRANSACTION READ ONLY");
            tx.execute("SET LOCAL statement_timeout = '5s'");
            return work.run(tx);
        } finally {
            con.rollback();
            con.setAutoCommit(autoCommit);
        }
    }

    private static Map<String, Object> query(JdbcTemplate tx, String table, String schema, String name, String orgCd,
                                             String col, String q, int page) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        out.put("orgCd", orgCd);
        if (tx.queryForObject("SELECT to_regclass(?)::text", String.class, table) == null) {
            out.put("exists", false);
            return out;
        }
        List<Column> columns = columns(tx, schema, name);
        List<String> pk = tx.queryForList("""
                SELECT a.attname FROM pg_index i
                JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=ANY(i.indkey)
                WHERE i.indrelid=?::regclass AND i.indisprimary ORDER BY array_position(i.indkey, a.attnum)
                """, String.class, table);
        boolean orgFiltered = columns.stream().anyMatch(c -> c.name().equals("org_cd"));
        boolean searched = q != null && !q.isBlank();
        List<Object> args = new ArrayList<>();
        String from = fromWhere(schema, name, columns, orgCd, col, q, args);

        Integer counted = tx.queryForObject("SELECT count(*) FROM (SELECT 1" + from + " LIMIT " + (COUNT_CAP + 1) + ") x",
                Integer.class, args.toArray());
        int total = counted == null ? 0 : counted;

        List<String> select = columns.stream().map(KrasTableBrowser::selectExpr).toList();
        String order = pk.isEmpty() ? "" : " ORDER BY " + String.join(", ", pk.stream().map(c -> quote(c) + " DESC").toList());
        List<List<String>> rows = tx.query("SELECT " + String.join(", ", select) + from + order
                        + " LIMIT " + PAGE_SIZE + " OFFSET " + (page * PAGE_SIZE),
                (rs, i) -> {
                    List<String> row = new ArrayList<>(columns.size());
                    for (int c = 0; c < columns.size(); c++) row.add(mask(columns.get(c), rs.getString(c + 1)));
                    return row;
                }, args.toArray());

        if (orgFiltered && total == 0 && !searched) {
            // 이 기관 행이 하나도 없을 때만: 다른 기관 데이터가 들어 있는지 알려 "고장"과 구분한다(행 내용은 보이지 않음).
            out.put("otherOrgs", tx.queryForList("SELECT \"org_cd\" AS org_cd, count(*) AS total FROM "
                    + quote(schema) + "." + quote(name) + " GROUP BY 1 ORDER BY 2 DESC LIMIT 5"));
        }
        out.put("exists", true);
        out.put("orgFiltered", orgFiltered);
        out.put("columns", columns);
        out.put("defaultColumn", columns.stream().anyMatch(c -> c.name().equals("pnu")) ? "pnu"
                : (pk.isEmpty() ? columns.get(0).name() : pk.get(0)));
        out.put("rowKey", rowKey(columns));
        out.put("rows", rows);
        out.put("total", Math.min(total, COUNT_CAP));
        out.put("totalCapped", total > COUNT_CAP);
        out.put("page", page);
        out.put("pageSize", PAGE_SIZE);
        return out;
    }

    static String rowKey(List<Column> columns) {
        return ROW_KEY_COLUMNS.stream().filter(k -> columns.stream().anyMatch(c -> c.name().equals(k)))
                .findFirst().orElse(null);
    }

    private static List<Column> columns(JdbcTemplate tx, String schema, String name) {
        return tx.query("""
                SELECT column_name, udt_name FROM information_schema.columns
                WHERE table_schema=? AND table_name=? ORDER BY ordinal_position
                """, (rs, i) -> column(rs.getString(1), rs.getString(2)), schema, name);
    }

    /** " FROM 테이블 WHERE 기관 AND 검색" — 표 조회와 지도 범위가 같은 조건을 쓴다. args에 바인딩 값을 채운다. */
    private static String fromWhere(String schema, String name, List<Column> columns, String orgCd, String col, String q,
                                    List<Object> args) {
        List<String> where = new ArrayList<>();
        if (columns.stream().anyMatch(c -> c.name().equals("org_cd"))) {
            where.add("\"org_cd\" = ?");
            args.add(orgCd);
        }
        if (q != null && !q.isBlank()) {
            Column searchCol = columns.stream().filter(c -> c.name().equals(col)).findFirst()
                    .orElseThrow(() -> new BrowseException("없는 칼럼입니다: " + col));
            if (!searchCol.searchable()) throw new BrowseException(col + " 칼럼으로는 검색할 수 없습니다.");
            addCondition(searchCol, q.trim(), where, args);
        }
        return " FROM " + quote(schema) + "." + quote(name) + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where));
    }

    /**
     * 지도 미리보기: 표와 같은 조건(기관·검색)의 도형 범위 + 그릴 레이어(배경 → 대상)와 포털 스타일(SLD).
     * SHAPE 다운로드 테이블(previewBases)과 그 파생 뷰만 된다.
     * 설계: docs/superpowers/specs/2026-10-15/2026-10-15-kras-db-layer-map-preview-design.md §5.2
     */
    static Map<String, Object> extent(JdbcTemplate jdbc, Set<String> allowed, Set<String> previewBases, String table,
                                      String orgCd, String col, String q, String key, List<String> backgroundLayers) {
        if (table == null || !TABLE_NAME.matcher(table).matches()) throw new BrowseException("조회할 수 없는 테이블입니다: " + table);
        String[] parts = table.split("\\.");
        return readOnly(jdbc, tx -> {
            if (!previewBases.contains(table) && baseOfView(tx, parts[0], parts[1], previewBases) == null) {
                boolean browsable = allowed.contains(table) || baseOfView(tx, parts[0], parts[1], allowed) != null;
                throw new BrowseException(browsable ? "지도 미리보기는 SHAPE 다운로드 레이어만 됩니다."
                        : "조회할 수 없는 테이블입니다: " + table);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("table", table);
            if (tx.queryForObject("SELECT to_regclass(?)::text", String.class, table) == null) {
                out.put("previewable", false);
                out.put("message", "테이블이 아직 없습니다.");
                return out;
            }
            List<Column> columns = columns(tx, parts[0], parts[1]);
            String geom = columns.stream().filter(c -> c.type().equals("geometry")).map(Column::name).findFirst().orElse(null);
            if (geom == null) {
                out.put("previewable", false);
                out.put("message", "도형(geometry) 칼럼이 없는 테이블입니다.");
                return out;
            }
            List<Object> args = new ArrayList<>();
            String from = fromWhere(parts[0], parts[1], columns, orgCd, col, q, args);
            if (key != null && !key.isBlank()) {
                // 표에서 고른 행 한 건만: 범위도 그 행, 지도 이미지도 같은 칼럼 조건(CQL_FILTER)으로 그 행만 그린다.
                String keyCol = rowKey(columns);
                if (keyCol == null) throw new BrowseException("이 테이블은 행 한 건 미리보기를 할 수 없습니다.");
                from += (from.contains(" WHERE ") ? " AND " : " WHERE ") + quote(keyCol) + "::text = ?";
                args.add(key);
                out.put("cqlFilter", keyCol + " = '" + key.replace("'", "''") + "'");
            }
            String g = quote(geom);
            Map<String, Object> box = tx.queryForMap("SELECT n AS total, ST_XMin(e) AS minx, ST_YMin(e) AS miny,"
                    + " ST_XMax(e) AS maxx, ST_YMax(e) AS maxy FROM (SELECT count(" + g + ") AS n, ST_Extent(" + g + ") AS e"
                    + from + ") x", args.toArray());
            List<Integer> srid = tx.queryForList("SELECT ST_SRID(" + g + ")" + from
                    + (from.contains(" WHERE ") ? " AND " : " WHERE ") + g + " IS NOT NULL LIMIT 1", Integer.class, args.toArray());
            out.put("previewable", true);
            out.put("srid", srid.isEmpty() ? null : srid.get(0));
            out.put("total", box.get("total"));
            out.put("bbox", box.get("minx") == null ? null
                    : List.of(box.get("minx"), box.get("miny"), box.get("maxx"), box.get("maxy")));

            List<String> names = new ArrayList<>();
            // 대상과 같은 배경(lp_pa_cbnd)은 평소엔 두 번 그리지 않게 빼지만, 행 한 건만 그릴 때는 주변 맥락으로 남긴다(설계 §10).
            boolean picked = key != null && !key.isBlank();
            for (String b : backgroundLayers) {
                if (picked || !b.equals(parts[1])) names.add("public." + b);
            }
            names.add(table);
            Map<String, KrasLayerStyleSld.Style> styles = KrasLayerStyleSld.load(tx, names);
            List<Map<String, Object>> layers = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                String qualified = names.get(i);
                String[] lp = qualified.split("\\.");
                boolean target = i == names.size() - 1;   // 대상은 늘 마지막(같은 이름 배경이 앞에 있을 수 있다)
                Set<String> layerColumns = new java.util.HashSet<>();
                columns(tx, lp[0], lp[1]).forEach(c -> layerColumns.add(c.name()));
                KrasLayerStyleSld.Style st = styles.get(qualified);
                Map<String, Object> layer = new LinkedHashMap<>();
                layer.put("name", lp[1]);
                layer.put("role", target ? "target" : "background");
                layer.put("title", st == null ? null : st.title());
                // 배경은 포털 축척 규칙 그대로, 대상은 기본으로 축척 규칙 없이(어느 축척에서나 보이게) + 규칙 적용판 따로.
                String sld = st == null ? null : KrasLayerStyleSld.sld(lp[1], st, !target, layerColumns);
                String sldWithScale = st == null ? null : KrasLayerStyleSld.sld(lp[1], st, true, layerColumns);
                if (target && picked) {
                    // 고른 행은 포털 스타일 위에 강조선을 덧그린다(이미지는 CQL_FILTER로 그 행만).
                    sld = KrasLayerStyleSld.withHighlight(lp[1], sld);
                    sldWithScale = KrasLayerStyleSld.withHighlight(lp[1], sldWithScale);
                }
                layer.put("sld", sld);
                if (target) layer.put("sldWithScale", sldWithScale);
                layer.put("minScale", st == null ? null : st.minScale());
                layer.put("maxScale", st == null ? null : st.maxScale());
                layers.add(layer);
            }
            out.put("layers", layers);
            return out;
        });
    }

    static Column column(String name, String type) {
        boolean masked = NAME_COLUMNS.contains(name) || ADDR_COLUMNS.contains(name) || REGNO_COLUMNS.contains(name);
        boolean searchable = !masked && (TEXT_TYPES.contains(type) || NUMBER_TYPES.contains(type) || TIME_TYPES.contains(type));
        return new Column(name, type, searchable, masked);
    }

    /** 칼럼 형식별 검색 조건. 값은 항상 바인딩한다. */
    static void addCondition(Column c, String q, List<String> where, List<Object> args) {
        String id = quote(c.name());
        if (TEXT_TYPES.contains(c.type())) {
            String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            if (c.name().equals("pnu")) {
                where.add(id + " LIKE ? ESCAPE '\\'");
                args.add(escaped + "%");
            } else {
                where.add(id + " ILIKE ? ESCAPE '\\'");
                args.add("%" + escaped + "%");
            }
        } else if (NUMBER_TYPES.contains(c.type())) {
            try {
                args.add(new BigDecimal(q));
            } catch (NumberFormatException e) {
                throw new BrowseException(c.name() + " 칼럼은 숫자를 입력하세요.");
            }
            where.add(id + " = ?");
        } else if (TIME_TYPES.contains(c.type())) {
            LocalDate d;
            try {
                d = LocalDate.parse(q);
            } catch (DateTimeParseException e) {
                throw new BrowseException(c.name() + " 칼럼은 날짜를 yyyy-MM-dd 형식으로 입력하세요.");
            }
            where.add(id + " >= ? AND " + id + " < ?");
            args.add(java.sql.Date.valueOf(d));
            args.add(java.sql.Date.valueOf(d.plusDays(1)));
        } else {
            throw new BrowseException(c.name() + " 칼럼으로는 검색할 수 없습니다.");
        }
    }

    private static String selectExpr(Column c) {
        String id = quote(c.name());
        return switch (c.type()) {
            case "geometry" -> "ST_GeometryType(" + id + ") || ' / SRID ' || ST_SRID(" + id + ")";
            case "bytea" -> "'(바이너리 ' || octet_length(" + id + ") || ' bytes)'";
            default -> id + "::text";
        };
    }

    static String mask(Column c, String v) {
        if (v == null || v.isBlank()) return v;
        if (REGNO_COLUMNS.contains(c.name())) return "***";
        if (NAME_COLUMNS.contains(c.name())) return v.charAt(0) + "*".repeat(v.length() - 1);
        if (ADDR_COLUMNS.contains(c.name())) {
            String[] words = v.trim().split("\\s+");
            return words.length <= 2 ? words[0] + " ***" : words[0] + " " + words[1] + " ***";
        }
        if (c.type().equals("jsonb") || c.type().equals("json")) v = PII_JSON_VALUE.matcher(v).replaceAll("$1\"***\"");
        return KrasVerificationEvidenceService.RRN_PATTERN.matcher(v).replaceAll("[MASKED]");
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
