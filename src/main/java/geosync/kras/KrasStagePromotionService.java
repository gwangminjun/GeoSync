package geosync.kras;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * kras.stage_* → 업무 테이블 승격. 설계 §8.
 *
 * 패턴 A(자연키 UPSERT), B(범위 전체 교체), C(신원 매칭 후 UPSERT)를 구현한다 — land_info/land_bldg_check(A),
 * shr_ymb/own_rgt_hist(B, §8.3), collective_building(C, §8.4)로 전부 실제로 검증됨.
 * B의 변형으로 부모(surrogate PK)→자식(그 PK를 참조) 2단 교체(SCOPE_REPLACE_WITH_CHILDREN)도 지원한다
 * — land_mov_hist(LAND_MOV_HIST 반복 안에 RELJIBUN이 또 반복)가 이 모양이라 추가했다.
 * 드릴다운(부모를 먼저 조회해야 자식을 조회할 수 있는 집합건물 계열)은 PARENT_LOOKUP_IDENTITY_MATCH_UPSERT —
 * 이미 승격된 부모 테이블에서 서로게이트 ID를 찾아 자식 신원 매칭에 쓴다.
 * D(순수 append, §8.5) — land_change_event처럼 매 수집이 새 사건이라 그냥 INSERT만 하는 경우.
 * UNIQUE(source_item_id,record_no)가 중복을 막는다.
 */
@Service
public class KrasStagePromotionService {

    public enum PromotionPattern {
        NATURAL_KEY_UPSERT, IDENTITY_MATCH_UPSERT, SCOPE_REPLACE, SCOPE_REPLACE_WITH_CHILDREN,
        PARENT_LOOKUP_IDENTITY_MATCH_UPSERT, APPEND_ONLY
    }

    /**
     * 부모의 서로게이트 PK를 참조하는 자식 테이블 — SCOPE_REPLACE_WITH_CHILDREN 전용.
     * 자식 stage 행은 매퍼가 "이 행이 몇 번째 부모 행에 속하는지"를 parent_record_no 컬럼에
     * 미리 적어둬야 한다(부모의 row_no와 1:1 대응 — KrasPnuIngestService가 매퍼 반환 순서 그대로
     * row_no를 매기므로, 매퍼가 부모를 순회하는 순서와 같은 카운터를 쓰면 자동으로 맞는다).
     */
    public record ChildSpec(String stageTable, String businessTable, String parentIdColumn,
                             String sequenceColumn, List<String> copyColumns) {}

    /**
     * 드릴다운 전용 — 이미 승격된 부모 테이블에서 서로게이트 ID를 조회해 자식의 신원 매칭/삽입에 쓴다.
     * @param parentKeyColumns 부모 테이블에서 조회할 때 WHERE에 쓸 컬럼(예: pnu, cbldg_seqno).
     * @param lookupKeys       parentKeyColumns와 순서를 맞춘, stage 행 extra_attributes에서 값을 꺼낼 키.
     *                         "_"로 시작하면 최상위 키(예: "_pnu"), 아니면 _extra_params 안의 키로 취급한다
     *                         (KrasPnuIngestService.insertStageRow가 모든 stage 행에 이 둘을 자동으로 남긴다).
     * @param childFkColumn    자식(business) 테이블에서 이 부모 ID를 저장하는 컬럼명(예: collective_building_id).
     */
    public record ParentLookup(String parentTable, String parentIdColumn, List<String> parentKeyColumns,
                                List<String> lookupKeys, String childFkColumn) {}

    /**
     * @param copyColumns       stage에서 business 테이블로 그대로 옮길 컬럼. org_cd는 넣지 않는다 —
     *                          guard_business_row가 source_item_id로 자동 채운다.
     * @param naturalKeyColumns A: ON CONFLICT 충돌 대상 컬럼(copyColumns의 부분집합).
     *                          C/드릴다운: 신원 매칭에 쓸 컬럼(역시 copyColumns의 부분집합). B/B+자식은 미사용.
     * @param surrogateIdColumn C, 드릴다운 전용 — 이 테이블 자신의 서로게이트 PK 컬럼명.
     *                          B+자식 전용 — 부모의 서로게이트 PK 컬럼명(예: history_id, RETURNING에 씀).
     * @param scopeColumn       B, B+자식 전용 — DELETE WHERE 기준 컬럼(예: pnu). A/C/드릴다운은 null.
     * @param children          B+자식 / 드릴다운+자식 전용. 자식이 없으면 빈 리스트.
     *                          한 부모에 자식 테이블이 여러 개인 경우(건축물대장 표제부의 층별/소유자/변동)를
     *                          위해 리스트다 — 선언 순서대로 처리한다.
     * @param parentLookup      드릴다운 전용. 그 외는 null.
     */
    public record StagePromotionSpec(String stageTable, String businessTable, PromotionPattern pattern,
                                      List<String> naturalKeyColumns, List<String> copyColumns,
                                      String surrogateIdColumn, String scopeColumn, List<ChildSpec> children,
                                      ParentLookup parentLookup) {

        public static StagePromotionSpec naturalKeyUpsert(String stageTable, String businessTable,
                                                            List<String> naturalKeyColumns, List<String> copyColumns) {
            return new StagePromotionSpec(stageTable, businessTable, PromotionPattern.NATURAL_KEY_UPSERT,
                    naturalKeyColumns, copyColumns, null, null, List.of(), null);
        }

        public static StagePromotionSpec identityMatchUpsert(String stageTable, String businessTable,
                                                               String surrogateIdColumn, List<String> naturalKeyColumns,
                                                               List<String> copyColumns) {
            return new StagePromotionSpec(stageTable, businessTable, PromotionPattern.IDENTITY_MATCH_UPSERT,
                    naturalKeyColumns, copyColumns, surrogateIdColumn, null, List.of(), null);
        }

        public static StagePromotionSpec scopeReplace(String stageTable, String businessTable,
                                                        String scopeColumn, List<String> copyColumns) {
            return new StagePromotionSpec(stageTable, businessTable, PromotionPattern.SCOPE_REPLACE,
                    List.of(), copyColumns, null, scopeColumn, List.of(), null);
        }

        public static StagePromotionSpec scopeReplaceWithChildren(String stageTable, String businessTable,
                                                                    String scopeColumn, String surrogateIdColumn,
                                                                    List<String> copyColumns, ChildSpec child) {
            return scopeReplaceWithChildren(stageTable, businessTable, scopeColumn, surrogateIdColumn,
                    copyColumns, List.of(child));
        }

        public static StagePromotionSpec scopeReplaceWithChildren(String stageTable, String businessTable,
                                                                    String scopeColumn, String surrogateIdColumn,
                                                                    List<String> copyColumns, List<ChildSpec> children) {
            return new StagePromotionSpec(stageTable, businessTable, PromotionPattern.SCOPE_REPLACE_WITH_CHILDREN,
                    List.of(), copyColumns, surrogateIdColumn, scopeColumn, children, null);
        }

        public static StagePromotionSpec parentLookupIdentityMatchUpsert(String stageTable, String businessTable,
                                                                          String surrogateIdColumn,
                                                                          List<String> naturalKeyColumns,
                                                                          List<String> copyColumns,
                                                                          ParentLookup parentLookup) {
            return parentLookupIdentityMatchUpsert(stageTable, businessTable, surrogateIdColumn,
                    naturalKeyColumns, copyColumns, parentLookup, List.of());
        }

        /** 드릴다운 + 자식 — 부모를 신원 매칭으로 보존하면서, 그 부모에 딸린 자식 목록은 매번 통째로 교체한다. */
        public static StagePromotionSpec parentLookupIdentityMatchUpsert(String stageTable, String businessTable,
                                                                          String surrogateIdColumn,
                                                                          List<String> naturalKeyColumns,
                                                                          List<String> copyColumns,
                                                                          ParentLookup parentLookup,
                                                                          List<ChildSpec> children) {
            return new StagePromotionSpec(stageTable, businessTable, PromotionPattern.PARENT_LOOKUP_IDENTITY_MATCH_UPSERT,
                    naturalKeyColumns, copyColumns, surrogateIdColumn, null, children, parentLookup);
        }

        public static StagePromotionSpec appendOnly(String stageTable, String businessTable,
                                                      List<String> copyColumns) {
            return new StagePromotionSpec(stageTable, businessTable, PromotionPattern.APPEND_ONLY,
                    List.of(), copyColumns, null, null, List.of(), null);
        }
    }

    /** 호출자가 부모→자식 순서를 보장한 spec 목록을 그 순서 그대로 승격한다. */
    public void promote(JdbcTemplate tx, long itemId, List<StagePromotionSpec> specs) {
        for (StagePromotionSpec spec : specs) {
            switch (spec.pattern()) {
                case NATURAL_KEY_UPSERT -> promoteNaturalKeyUpsert(tx, itemId, spec);
                case IDENTITY_MATCH_UPSERT -> promoteIdentityMatchUpsert(tx, itemId, spec);
                case SCOPE_REPLACE -> promoteScopeReplace(tx, itemId, spec);
                case SCOPE_REPLACE_WITH_CHILDREN -> promoteScopeReplaceWithChildren(tx, itemId, spec);
                case PARENT_LOOKUP_IDENTITY_MATCH_UPSERT -> promoteParentLookupIdentityMatchUpsert(tx, itemId, spec);
                case APPEND_ONLY -> promoteAppendOnly(tx, itemId, spec);
            }
        }
    }

    /** 패턴 D(§8.5) — stage 전체를 그대로 INSERT. UNIQUE(source_item_id,record_no)가 재수집 중복을 막는다. */
    private void promoteAppendOnly(JdbcTemplate tx, long itemId, StagePromotionSpec spec) {
        String colList = String.join(",", spec.copyColumns());
        String sql = "INSERT INTO " + spec.businessTable() + "(" + colList + ", source_item_id) "
                + "SELECT " + colList + ", ? FROM " + spec.stageTable() + " WHERE item_id=?";
        tx.update(sql, itemId, itemId);
    }

    /**
     * 패턴 B(§8.3) — 이 item의 scope(예: pnu) 전체를 DELETE 후 stage 전체를 INSERT.
     * land_share처럼 "매번 전체 목록을 반환하는" 서비스에 맞다 — 부분 갱신이 아니라 전체 교체.
     * land_share는 guard_business_row의 DELETE 차단 목록에 없어 허용된다(설계에서 확인됨).
     */
    private void promoteScopeReplace(JdbcTemplate tx, long itemId, StagePromotionSpec spec) {
        Object scopeValue = tx.queryForObject(
                "SELECT " + spec.scopeColumn() + " FROM " + spec.stageTable() + " WHERE item_id=? AND row_no=1",
                Object.class, itemId);
        tx.update("DELETE FROM " + spec.businessTable() + " WHERE " + spec.scopeColumn() + "=?", scopeValue);

        String colList = String.join(",", spec.copyColumns());
        String sql = "INSERT INTO " + spec.businessTable() + "(" + colList + ", source_item_id) "
                + "SELECT " + colList + ", ? FROM " + spec.stageTable() + " WHERE item_id=?";
        tx.update(sql, itemId, itemId);
    }

    /**
     * 패턴 B + 자식 — scope(예: pnu) 전체를 자식→부모 순서로 DELETE한 뒤, 부모 stage 행마다
     * INSERT ... RETURNING으로 새 서로게이트 ID를 받고, parent_record_no로 이어붙는 자식 stage
     * 행들을 그 ID로 다시 INSERT한다. 부모/자식이 같은 트랜잭션(tx) 안에서 순차 실행되므로
     * RETURNING된 ID를 바로 다음 INSERT에 쓸 수 있다.
     */
    private void promoteScopeReplaceWithChildren(JdbcTemplate tx, long itemId, StagePromotionSpec spec) {
        Object scopeValue = tx.queryForObject(
                "SELECT " + spec.scopeColumn() + " FROM " + spec.stageTable() + " WHERE item_id=? AND row_no=1",
                Object.class, itemId);

        for (ChildSpec child : spec.children()) {
            tx.update("DELETE FROM " + child.businessTable() + " WHERE " + child.parentIdColumn() + " IN "
                    + "(SELECT " + spec.surrogateIdColumn() + " FROM " + spec.businessTable() + " WHERE "
                    + spec.scopeColumn() + "=?)", scopeValue);
        }
        tx.update("DELETE FROM " + spec.businessTable() + " WHERE " + spec.scopeColumn() + "=?", scopeValue);

        List<Integer> parentRowNos = tx.query(
                "SELECT row_no FROM " + spec.stageTable() + " WHERE item_id=? ORDER BY row_no",
                (rs, i) -> rs.getInt(1), itemId);
        if (parentRowNos.isEmpty()) {
            throw new IllegalStateException(
                    spec.businessTable() + " 승격 실패 — item_id=" + itemId + "의 " + spec.stageTable() + " 행이 없습니다.");
        }

        String parentColList = String.join(",", spec.copyColumns());

        for (int rowNo : parentRowNos) {
            Map<String, Object> parentRow = tx.queryForMap(
                    "SELECT " + parentColList + " FROM " + spec.stageTable() + " WHERE item_id=? AND row_no=?",
                    itemId, rowNo);
            List<Object> parentValues = new ArrayList<>();
            for (String c : spec.copyColumns()) parentValues.add(parentRow.get(c));
            parentValues.add(itemId);

            Long parentId = tx.queryForObject(
                    "INSERT INTO " + spec.businessTable() + "(" + parentColList + ",source_item_id) VALUES ("
                            + placeholders(spec.copyColumns().size() + 1) + ") RETURNING " + spec.surrogateIdColumn(),
                    Long.class, parentValues.toArray());

            insertChildren(tx, itemId, rowNo, parentId, spec.children());
        }
    }

    /**
     * 부모 stage 행(parentRowNo)에 parent_record_no로 이어붙는 자식 stage 행들을 새 parentId로 INSERT한다.
     * 자식 테이블이 여러 개면(건축물대장 표제부의 층별/소유자/변동) 선언 순서대로 각각 처리한다.
     */
    private void insertChildren(JdbcTemplate tx, long itemId, int parentRowNo, Long parentId,
                                 List<ChildSpec> children) {
        for (ChildSpec child : children) {
            if (child.copyColumns().isEmpty()) continue;
            String childColList = String.join(",", child.copyColumns());
            List<Map<String, Object>> childRows = tx.queryForList(
                    "SELECT " + childColList + " FROM " + child.stageTable()
                            + " WHERE item_id=? AND parent_record_no=? ORDER BY row_no",
                    itemId, parentRowNo);
            int seq = 1;
            for (Map<String, Object> childRow : childRows) {
                List<Object> childValues = new ArrayList<>();
                childValues.add(parentId);
                childValues.add(seq++);
                for (String c : child.copyColumns()) childValues.add(childRow.get(c));
                childValues.add(itemId);
                tx.update("INSERT INTO " + child.businessTable() + "(" + child.parentIdColumn() + ","
                                + child.sequenceColumn() + "," + childColList + ",source_item_id) VALUES ("
                                + placeholders(childValues.size()) + ")",
                        childValues.toArray());
            }
        }
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    /** 패턴 A(§8.2/§8.3) — stage 전체를 한 문장으로 INSERT ... ON CONFLICT DO UPDATE. */
    private void promoteNaturalKeyUpsert(JdbcTemplate tx, long itemId, StagePromotionSpec spec) {
        List<String> cols = spec.copyColumns();
        String colList = String.join(",", cols);
        String updateSet = cols.stream()
                .filter(c -> !spec.naturalKeyColumns().contains(c))
                .map(c -> c + "=EXCLUDED." + c)
                .reduce((a, b) -> a + "," + b)
                .orElseThrow(() -> new IllegalStateException(
                        spec.businessTable() + ": 자연키 외 컬럼이 하나도 없습니다."));

        String sql = """
            INSERT INTO %s(%s, source_item_id)
            SELECT %s, ? FROM %s WHERE item_id=? AND row_no=1
            ON CONFLICT (%s) DO UPDATE SET %s, source_item_id=EXCLUDED.source_item_id
            """.formatted(spec.businessTable(), colList, colList, spec.stageTable(),
                String.join(",", spec.naturalKeyColumns()), updateSet);

        int n = tx.update(sql, itemId, itemId);
        if (n != 1) {
            throw new IllegalStateException(
                    spec.businessTable() + " 승격 실패 — item_id=" + itemId + "의 " + spec.stageTable()
                            + " 행을 찾을 수 없습니다.");
        }
    }

    /**
     * 패턴 C(§8.4) — stage의 row_no마다: 후보 자연키로 기존 행 탐색 → 있으면 UPDATE(서로게이트 ID 보존),
     * 없으면 INSERT. 호출자(KrasPnuIngestService)가 이미 org_cd+pnu 단위로 advisory lock을 잡은
     * 트랜잭션 안에서만 호출한다(§8.7) — 탐색과 INSERT 사이 경쟁 상태를 막기 위함.
     */
    private void promoteIdentityMatchUpsert(JdbcTemplate tx, long itemId, StagePromotionSpec spec) {
        List<Integer> rowNos = tx.query(
                "SELECT row_no FROM " + spec.stageTable() + " WHERE item_id=? ORDER BY row_no",
                (rs, i) -> rs.getInt(1), itemId);
        if (rowNos.isEmpty()) {
            throw new IllegalStateException(
                    spec.businessTable() + " 승격 실패 — item_id=" + itemId + "의 " + spec.stageTable() + " 행이 없습니다.");
        }
        for (int rowNo : rowNos) {
            promoteIdentityMatchRow(tx, itemId, rowNo, spec);
        }
    }

    private void promoteIdentityMatchRow(JdbcTemplate tx, long itemId, int rowNo, StagePromotionSpec spec) {
        List<String> cols = spec.copyColumns();
        Map<String, Object> stageRow = tx.queryForMap(
                "SELECT " + String.join(",", cols) + " FROM " + spec.stageTable() + " WHERE item_id=? AND row_no=?",
                itemId, rowNo);

        String whereMatch = spec.naturalKeyColumns().stream().map(c -> c + "=?")
                .reduce((a, b) -> a + " AND " + b).orElseThrow();
        Object[] matchValues = spec.naturalKeyColumns().stream().map(stageRow::get).toArray();
        List<Long> existing = tx.query(
                "SELECT " + spec.surrogateIdColumn() + " FROM " + spec.businessTable() + " WHERE " + whereMatch,
                (rs, i) -> rs.getLong(1), matchValues);

        List<String> updateCols = cols.stream().filter(c -> !spec.naturalKeyColumns().contains(c)).toList();

        if (!existing.isEmpty()) {
            long id = existing.get(0);
            List<Object> params = new ArrayList<>();
            StringBuilder setClause = new StringBuilder();
            for (String c : updateCols) {
                if (!setClause.isEmpty()) setClause.append(",");
                setClause.append(c).append("=?");
                params.add(stageRow.get(c));
            }
            if (!setClause.isEmpty()) setClause.append(",");
            setClause.append("source_item_id=?,last_seen_item_id=?");
            params.add(itemId);
            params.add(itemId);
            params.add(id);
            tx.update("UPDATE " + spec.businessTable() + " SET " + setClause
                    + " WHERE " + spec.surrogateIdColumn() + "=?", params.toArray());
        } else {
            List<String> insertCols = new ArrayList<>(cols);
            insertCols.add("source_item_id");
            insertCols.add("last_seen_item_id");
            List<Object> params = new ArrayList<>();
            for (String c : cols) params.add(stageRow.get(c));
            params.add(itemId);
            params.add(itemId);
            String placeholders = String.join(",", insertCols.stream().map(c -> "?").toList());
            tx.update("INSERT INTO " + spec.businessTable() + "(" + String.join(",", insertCols) + ") VALUES ("
                    + placeholders + ")", params.toArray());
        }
    }

    /**
     * 드릴다운 승격 — stage row_no마다: (1) extra_attributes에 남겨진 _pnu/_extra_params로 이미
     * 승격된 부모 테이블에서 서로게이트 ID를 조회하고, (2) 그 ID + naturalKeyColumns로 자식 신원 매칭 →
     * 있으면 UPDATE, 없으면 INSERT. 호출자가 advisory lock을 잡은 트랜잭션 안에서만 호출한다(패턴 C와 동일 이유).
     */
    private void promoteParentLookupIdentityMatchUpsert(JdbcTemplate tx, long itemId, StagePromotionSpec spec) {
        List<Integer> rowNos = tx.query(
                "SELECT row_no FROM " + spec.stageTable() + " WHERE item_id=? ORDER BY row_no",
                (rs, i) -> rs.getInt(1), itemId);
        if (rowNos.isEmpty()) {
            throw new IllegalStateException(
                    spec.businessTable() + " 승격 실패 — item_id=" + itemId + "의 " + spec.stageTable() + " 행이 없습니다.");
        }
        for (int rowNo : rowNos) {
            long promotedId = promoteParentLookupRow(tx, itemId, rowNo, spec);
            // 자식은 매번 통째로 교체한다 — 층별/소유자/변동 같은 목록은 서비스가 매 호출 전체를 돌려주므로
            // 부분 갱신이 아니라 전체 교체가 맞다(부모의 서로게이트 ID는 위에서 신원 매칭으로 보존됨).
            for (ChildSpec child : spec.children()) {
                tx.update("DELETE FROM " + child.businessTable() + " WHERE " + child.parentIdColumn() + "=?",
                        promotedId);
            }
            insertChildren(tx, itemId, rowNo, promotedId, spec.children());
        }
    }

    /** @return 이번 행이 매칭/삽입된 업무 테이블의 서로게이트 ID(자식 연결에 쓴다). */
    private long promoteParentLookupRow(JdbcTemplate tx, long itemId, int rowNo, StagePromotionSpec spec) {
        ParentLookup pl = spec.parentLookup();
        List<String> lookupKeys = pl.lookupKeys();
        StringBuilder lookupSelect = new StringBuilder();
        for (int i = 0; i < lookupKeys.size(); i++) {
            if (i > 0) lookupSelect.append(",");
            lookupSelect.append(jsonbLookupExpr(lookupKeys.get(i), i));
        }
        Map<String, Object> lookupRow = tx.queryForMap(
                "SELECT " + lookupSelect + " FROM " + spec.stageTable() + " WHERE item_id=? AND row_no=?",
                itemId, rowNo);
        Object[] parentKeyValues = new Object[lookupKeys.size()];
        for (int i = 0; i < lookupKeys.size(); i++) parentKeyValues[i] = lookupRow.get("lookup_" + i);

        String parentWhere = pl.parentKeyColumns().stream().map(c -> c + "=?")
                .reduce((a, b) -> a + " AND " + b).orElseThrow();
        Long parentId = tx.queryForObject(
                "SELECT " + pl.parentIdColumn() + " FROM " + pl.parentTable() + " WHERE " + parentWhere,
                Long.class, parentKeyValues);

        List<String> cols = spec.copyColumns();
        Map<String, Object> stageRow = tx.queryForMap(
                "SELECT " + String.join(",", cols) + " FROM " + spec.stageTable() + " WHERE item_id=? AND row_no=?",
                itemId, rowNo);

        String whereMatch = spec.naturalKeyColumns().stream().map(c -> c + "=?")
                .reduce((a, b) -> a + " AND " + b).orElseThrow();
        List<Object> matchValues = new ArrayList<>();
        matchValues.add(parentId);
        for (String c : spec.naturalKeyColumns()) matchValues.add(stageRow.get(c));
        List<Long> existing = tx.query(
                "SELECT " + spec.surrogateIdColumn() + " FROM " + spec.businessTable()
                        + " WHERE " + pl.childFkColumn() + "=? AND " + whereMatch,
                (rs, i) -> rs.getLong(1), matchValues.toArray());

        List<String> updateCols = cols.stream().filter(c -> !spec.naturalKeyColumns().contains(c)).toList();

        if (!existing.isEmpty()) {
            long id = existing.get(0);
            List<Object> params = new ArrayList<>();
            StringBuilder setClause = new StringBuilder();
            for (String c : updateCols) {
                if (!setClause.isEmpty()) setClause.append(",");
                setClause.append(c).append("=?");
                params.add(stageRow.get(c));
            }
            if (!setClause.isEmpty()) setClause.append(",");
            setClause.append("source_item_id=?,last_seen_item_id=?");
            params.add(itemId);
            params.add(itemId);
            params.add(id);
            tx.update("UPDATE " + spec.businessTable() + " SET " + setClause
                    + " WHERE " + spec.surrogateIdColumn() + "=?", params.toArray());
            return id;
        }
        List<String> insertCols = new ArrayList<>();
        insertCols.add(pl.childFkColumn());
        insertCols.addAll(cols);
        insertCols.add("source_item_id");
        insertCols.add("last_seen_item_id");
        List<Object> params = new ArrayList<>();
        params.add(parentId);
        for (String c : cols) params.add(stageRow.get(c));
        params.add(itemId);
        params.add(itemId);
        Long newId = tx.queryForObject(
                "INSERT INTO " + spec.businessTable() + "(" + String.join(",", insertCols) + ") VALUES ("
                        + placeholders(insertCols.size()) + ") RETURNING " + spec.surrogateIdColumn(),
                Long.class, params.toArray());
        return newId;
    }

    private static String jsonbLookupExpr(String key, int idx) {
        String expr = key.startsWith("_")
                ? "extra_attributes->>'" + key + "'"
                : "extra_attributes->'_extra_params'->>'" + key + "'";
        return expr + " AS lookup_" + idx;
    }
}
