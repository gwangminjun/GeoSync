package geomex.sync.repository;

import geomex.sync.model.ColumnDef;
import geomex.sync.model.SyncTableDef;
import geomex.sync.service.TargetTableNameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class OdsRepository {

    private static final Logger log = LoggerFactory.getLogger(OdsRepository.class);

    private final JdbcTemplate jdbc;
    private final TargetTableNameService tableNameService;

    public OdsRepository(JdbcTemplate jdbc, TargetTableNameService tableNameService) {
        this.jdbc = jdbc;
        this.tableNameService = tableNameService;
    }

    /**
     * 대상 테이블에서 기관코드 전체 삭제 후 배치 INSERT (Spring primary DataSource 사용)
     */
    @Transactional
    public int replaceAll(SyncTableDef def, String orgCode, int targetEpsg,
                          List<Map<String, Object>> rows) {
        return replaceAllTo(jdbc, def, orgCode, targetEpsg, rows);
    }

    /**
     * 지정된 JdbcTemplate에 기관코드 전체 삭제 후 배치 INSERT (다중 DB 대상용)
     */
    public int replaceAllTo(JdbcTemplate targetJdbc, SyncTableDef def, String orgCode,
                            int targetEpsg, List<Map<String, Object>> rows) {
        return replaceAllTo(targetJdbc, def, orgCode, targetEpsg, rows, null);
    }

    public int replaceAllTo(JdbcTemplate targetJdbc, SyncTableDef def, String orgCode,
                            int targetEpsg, List<Map<String, Object>> rows, String schemaOverride) {
        String targetTableName = tableNameService.resolve(def.tgtTableName, schemaOverride);

        if (!targetTableName.equals(def.tgtTableName)) {
            ensureTableExists(targetJdbc, targetTableName, def.tgtTableName);
        }

        String deleteSql = "DELETE FROM " + targetTableName + " WHERE org_cd = ?";
        targetJdbc.update(deleteSql, orgCode);

        if (rows.isEmpty()) return 0;

        String insertSql = buildInsertSql(def, targetTableName, targetEpsg);
        int count = 0;

        for (Map<String, Object> row : rows) {
            Object[] params = buildParams(def, row, orgCode);
            try {
                targetJdbc.update(insertSql, params);
                count++;
            } catch (Exception e) {
                log.warn("[{}] INSERT 실패 (row={}): {}", targetTableName, row, e.getMessage());
            }
        }
        log.info("[{}] org_cd={} → {}건 저장", targetTableName, orgCode, count);
        return count;
    }

    private void ensureTableExists(JdbcTemplate targetJdbc, String targetTableName, String sourceTableName) {
        String schema = targetTableName.contains(".")
                ? targetTableName.substring(0, targetTableName.indexOf('.'))
                : "public";
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        targetJdbc.execute("CREATE TABLE IF NOT EXISTS " + targetTableName +
                           " (LIKE " + sourceTableName + " INCLUDING ALL)");
    }

    private String buildInsertSql(SyncTableDef def, String targetTableName, int epsg) {
        List<ColumnDef> cols = def.columns;

        StringBuilder colList = new StringBuilder();
        StringBuilder valList = new StringBuilder();

        for (ColumnDef col : cols) {
            if (!colList.isEmpty()) { colList.append(", "); valList.append(", "); }
            colList.append(col.tgtName);
            valList.append(col.isGeometry ? "ST_GeomFromText(?, " + epsg + ")" : "?");
        }

        // org_cd가 매핑에 없으면 추가
        boolean hasOrgCd = cols.stream().anyMatch(c -> "org_cd".equals(c.tgtName));
        if (!hasOrgCd) {
            colList.append(", org_cd");
            valList.append(", ?");
        }

        return "INSERT INTO " + targetTableName + " (" + colList + ") VALUES (" + valList + ")";
    }

    private Object[] buildParams(SyncTableDef def, Map<String, Object> row, String orgCode) {
        List<ColumnDef> cols = def.columns;
        boolean hasOrgCd = cols.stream().anyMatch(c -> "org_cd".equals(c.tgtName));

        Object[] params = new Object[cols.size() + (hasOrgCd ? 0 : 1)];
        int i = 0;
        for (ColumnDef col : cols) {
            params[i++] = row.get(col.srcName);
        }
        if (!hasOrgCd) {
            params[i] = orgCode;
        }
        return params;
    }
}
