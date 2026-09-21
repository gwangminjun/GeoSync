package geosync.kras;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * kras.stage_* → 업무 테이블 승격. 설계 §8.
 *
 * 패턴 A(자연키 UPSERT)와 C(신원 매칭 후 UPSERT)를 구현한다 — land_info/land_bldg_check(A)와
 * collective_building(C, §8.4)로 둘 다 실제로 검증됨. B(범위 전체 교체)/D(순수 append)는 그 패턴이
 * 실제로 필요한 첫 서비스를 붙일 때 §8.3/§8.5 SQL을 그대로 옮겨 추가한다.
 */
@Service
public class KrasStagePromotionService {

    public enum PromotionPattern { NATURAL_KEY_UPSERT, IDENTITY_MATCH_UPSERT }

    /**
     * @param copyColumns       stage에서 business 테이블로 그대로 옮길 컬럼(자연키 컬럼 포함). org_cd는 넣지
     *                          않는다 — guard_business_row가 source_item_id로 자동 채운다.
     * @param naturalKeyColumns A: ON CONFLICT 충돌 대상 컬럼(copyColumns의 부분집합).
     *                          C: 신원 매칭에 쓸 컬럼(역시 copyColumns의 부분집합).
     * @param surrogateIdColumn C 전용 — 서로게이트 PK 컬럼명(예: collective_building_id). A는 null.
     */
    public record StagePromotionSpec(String stageTable, String businessTable, PromotionPattern pattern,
                                      List<String> naturalKeyColumns, List<String> copyColumns,
                                      String surrogateIdColumn) {

        public static StagePromotionSpec naturalKeyUpsert(String stageTable, String businessTable,
                                                            List<String> naturalKeyColumns, List<String> copyColumns) {
            return new StagePromotionSpec(stageTable, businessTable, PromotionPattern.NATURAL_KEY_UPSERT,
                    naturalKeyColumns, copyColumns, null);
        }

        public static StagePromotionSpec identityMatchUpsert(String stageTable, String businessTable,
                                                               String surrogateIdColumn, List<String> naturalKeyColumns,
                                                               List<String> copyColumns) {
            return new StagePromotionSpec(stageTable, businessTable, PromotionPattern.IDENTITY_MATCH_UPSERT,
                    naturalKeyColumns, copyColumns, surrogateIdColumn);
        }
    }

    /** 호출자가 부모→자식 순서를 보장한 spec 목록을 그 순서 그대로 승격한다. */
    public void promote(JdbcTemplate tx, long itemId, List<StagePromotionSpec> specs) {
        for (StagePromotionSpec spec : specs) {
            switch (spec.pattern()) {
                case NATURAL_KEY_UPSERT -> promoteNaturalKeyUpsert(tx, itemId, spec);
                case IDENTITY_MATCH_UPSERT -> promoteIdentityMatchUpsert(tx, itemId, spec);
            }
        }
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
}
