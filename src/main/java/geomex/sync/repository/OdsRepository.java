package geomex.sync.repository;

import geomex.sync.model.ColumnDef;
import geomex.sync.model.SyncTableDef;
import geomex.sync.service.SyncStatusService;
import geomex.sync.service.TargetTableNameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Repository
public class OdsRepository {

    private static final Logger log = LoggerFactory.getLogger(OdsRepository.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final TargetTableNameService tableNameService;
    private final SyncStatusService statusService;

    @Value("${ods.ddl:conf/sql/sync_tables.sql}")
    private String ddlPath;

    public OdsRepository(JdbcTemplate jdbc, TargetTableNameService tableNameService,
                         SyncStatusService statusService) {
        this.jdbc = jdbc;
        this.tableNameService = tableNameService;
        this.statusService = statusService;
    }

    @Transactional
    public int replaceAll(SyncTableDef def, String orgCode, int targetEpsg,
                          List<Map<String, Object>> rows) {
        return replaceAllTo(jdbc, def, orgCode, targetEpsg, rows);
    }

    public int replaceAllTo(JdbcTemplate targetJdbc, SyncTableDef def, String orgCode,
                            int targetEpsg, List<Map<String, Object>> rows) {
        return replaceAllTo(targetJdbc, def, orgCode, targetEpsg, rows, null, null);
    }

    public void dropTable(JdbcTemplate targetJdbc, String tgtTableName, String schemaOverride) {
        String targetTableName = tableNameService.resolve(tgtTableName, schemaOverride);
        String sqlTableName = qualifiedTableName(targetTableName);
        try {
            targetJdbc.execute("DROP TABLE IF EXISTS " + sqlTableName + " CASCADE");
            log.info("[{}] dropped", targetTableName);
        } catch (Exception e) {
            log.warn("[{}] drop failed: {}", targetTableName, e.getMessage());
        }
    }

    public void recreateSchemaTables(JdbcTemplate targetJdbc, String schemaOverride) {
        String schema = resolveSchema(schemaOverride);
        String sqlSchema = quoteIdent(schema);
        String databaseName = currentDatabase(targetJdbc);

        try {
            String ddlScript = Files.readString(Path.of(ddlPath), StandardCharsets.UTF_8);
            List<OdsTableDdl.CreateTable> tables = OdsTableDdl.createTables(ddlScript);
            if (tables.isEmpty()) {
                throw new IllegalStateException("No CREATE TABLE statements found in " + ddlPath);
            }

            targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS " + sqlSchema);

            List<OdsTableDdl.CreateTable> dropOrder = new ArrayList<>(tables);
            Collections.reverse(dropOrder);
            for (OdsTableDdl.CreateTable table : dropOrder) {
                String tableName = sqlSchema + "." + quoteIdent(table.baseName());
                targetJdbc.execute("DROP TABLE IF EXISTS " + tableName + " CASCADE");
            }

            for (OdsTableDdl.CreateTable table : tables) {
                String tableName = sqlSchema + "." + quoteIdent(table.baseName());
                targetJdbc.execute(table.createSql(tableName));
                verifyTableExists(targetJdbc, schema, table.baseName());
            }

            log.info("[{}] database={} recreated {} tables from {}",
                    schema, databaseName, tables.size(), ddlPath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to recreate schema tables for "
                    + databaseName + "." + schema + ": " + e.getMessage(), e);
        }
    }

    public int replaceAllTo(JdbcTemplate targetJdbc, SyncTableDef def, String orgCode,
                            int targetEpsg, List<Map<String, Object>> rows, String schemaOverride) {
        return replaceAllTo(targetJdbc, def, orgCode, targetEpsg, targetEpsg, rows, schemaOverride, null);
    }

    public int replaceAllTo(JdbcTemplate targetJdbc, SyncTableDef def, String orgCode,
                            int targetEpsg, List<Map<String, Object>> rows, String schemaOverride,
                            String progressType) {
        return replaceAllTo(targetJdbc, def, orgCode, targetEpsg, targetEpsg, rows, schemaOverride, progressType);
    }

    public int replaceAllTo(JdbcTemplate targetJdbc, SyncTableDef def, String orgCode,
                            int sourceEpsg, int storageEpsg, List<Map<String, Object>> rows,
                            String schemaOverride, String progressType) {
        String targetTableName = tableNameService.resolve(def.tgtTableName, schemaOverride);
        String sqlTableName = qualifiedTableName(targetTableName);
        String databaseName = currentDatabase(targetJdbc);
        log.info("[{}] database={} org_cd={} save start (rows={})",
                targetTableName, databaseName, orgCode, rows.size());

        ensureTableExists(targetJdbc, targetTableName, sqlTableName, def, storageEpsg);
        log.info("[{}] database={} table verified", targetTableName, databaseName);

        String deleteSql = "DELETE FROM " + sqlTableName + " WHERE " + quoteIdent("org_cd") + " = ?";
        try {
            targetJdbc.update(deleteSql, orgCode);
            log.info("[{}] org_cd={} old rows deleted", targetTableName, orgCode);
        } catch (Exception e) {
            log.warn("[{}] DELETE skipped: {}", targetTableName, e.getMessage());
        }

        if (rows.isEmpty()) return 0;

        String insertSql = buildInsertSql(def, sqlTableName, sourceEpsg, storageEpsg);
        boolean hasOrgCd = def.columns.stream().anyMatch(c -> "org_cd".equals(c.tgtName));

        List<Object[]> allParams = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            allParams.add(buildParams(def, row, orgCode, hasOrgCd));
        }

        int count = 0;
        int failCount = 0;
        Exception firstFailure = null;
        updateInsertProgress(progressType, targetTableName, databaseName, 0, rows.size());

        for (int start = 0; start < allParams.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, allParams.size());
            List<Object[]> batch = allParams.subList(start, end);
            try {
                targetJdbc.batchUpdate(insertSql, batch);
                count += batch.size();
                log.info("[{}] org_cd={} INSERT progress {}/{}", targetTableName, orgCode, count, rows.size());
            } catch (Exception e) {
                failCount += batch.size();
                if (firstFailure == null) firstFailure = e;
                log.warn("[{}] batch INSERT failed (rows {}-{}): {}", targetTableName, start, end, e.getMessage());
            }
            updateInsertProgress(progressType, targetTableName, databaseName, count + failCount, rows.size());
        }

        updateInsertProgress(progressType, targetTableName, databaseName, count, rows.size());
        if (failCount > 0) {
            throw new IllegalStateException("INSERT failed for " + targetTableName
                    + " on database " + databaseName + " (success=" + count
                    + ", failed=" + failCount + "): " + firstFailure.getMessage(), firstFailure);
        }
        log.info("[{}] org_cd={} saved {} rows", targetTableName, orgCode, count);
        return count;
    }

    private void updateInsertProgress(String progressType, String targetTableName,
                                      String databaseName, int completed, int total) {
        if (progressType == null || progressType.isBlank()) return;
        statusService.updateRowProgress(progressType, completed, total,
                databaseName + "." + targetTableName);
    }

    private static final Map<String, String> GEOM_TYPE_MAP = Map.of(
            "MULTIPOLYGON", "MULTIPOLYGON",
            "POLYGON", "POLYGON",
            "POINT", "POINT",
            "MULTIPOINT", "MULTIPOINT",
            "LINESTRING", "LINESTRING",
            "MULTILINESTRING", "MULTILINESTRING"
    );

    private void ensureTableExists(JdbcTemplate targetJdbc, String targetTableName, String sqlTableName,
                                   SyncTableDef def, int epsg) {
        String schema = targetTableName.contains(".")
                ? targetTableName.substring(0, targetTableName.indexOf('.'))
                : "public";
        String baseName = targetTableName.contains(".")
                ? targetTableName.substring(targetTableName.indexOf('.') + 1)
                : targetTableName;
        String sqlSchema = quoteIdent(schema);

        try {
            targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS " + sqlSchema);
            String ddlScript = Files.readString(Path.of(ddlPath), StandardCharsets.UTF_8);
            String ddl = OdsTableDdl.rewriteCreateTable(ddlScript, targetTableName, sqlTableName)
                    .orElseThrow(() -> new IllegalStateException("DDL not found: " + baseName));
            targetJdbc.execute(ddl);
            verifyTableExists(targetJdbc, schema, baseName);
            return;
        } catch (Exception e) {
            log.warn("[{}] SQL DDL table create failed; falling back to XML mapping: {}",
                    targetTableName, e.getMessage());
        }

        try {
            targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS " + sqlSchema);

            StringBuilder colDdl = new StringBuilder();
            ColumnDef geomCol = null;
            for (ColumnDef col : def.columns) {
                if (col.isGeometry) {
                    geomCol = col;
                    continue;
                }
                colDdl.append(quoteIdent(col.tgtName)).append(" ").append(toSqlType(col)).append(", ");
            }

            boolean hasOrgCd = def.columns.stream().anyMatch(c -> "org_cd".equals(c.tgtName));
            if (!hasOrgCd) colDdl.append(quoteIdent("org_cd")).append(" VARCHAR(10), ");

            if (geomCol != null) {
                String pgGeomType = GEOM_TYPE_MAP.getOrDefault(geomCol.type.toUpperCase(), "GEOMETRY");
                String ddl = "CREATE TABLE IF NOT EXISTS " + sqlTableName + " ("
                        + colDdl
                        + quoteIdent("_gid") + " SERIAL, "
                        + quoteIdent("_annox") + " NUMERIC(20,4), "
                        + quoteIdent("_annoy") + " NUMERIC(20,4), "
                        + quoteIdent(geomCol.tgtName) + " geometry(" + pgGeomType + "," + epsg + "), "
                        + "PRIMARY KEY (" + quoteIdent("_gid") + ")"
                        + ")";
                targetJdbc.execute(ddl);
            } else {
                if (colDdl.length() > 0) colDdl.setLength(colDdl.length() - 2);
                targetJdbc.execute("CREATE TABLE IF NOT EXISTS " + sqlTableName + " (" + colDdl + ")");
            }

            verifyTableExists(targetJdbc, schema, baseName);
        } catch (Exception e) {
            throw new IllegalStateException("Table create failed for " + targetTableName + ": " + e.getMessage(), e);
        }
    }

    private void verifyTableExists(JdbcTemplate targetJdbc, String schema, String tableName) {
        Boolean exists = targetJdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = ? AND table_type = 'BASE TABLE')",
                Boolean.class,
                schema,
                tableName);
        if (!Boolean.TRUE.equals(exists)) {
            throw new IllegalStateException("table was not created: " + schema + "." + tableName);
        }
    }

    private String currentDatabase(JdbcTemplate targetJdbc) {
        try {
            return targetJdbc.queryForObject("SELECT current_database()", String.class);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String resolveSchema(String schemaOverride) {
        if (schemaOverride != null && !schemaOverride.isBlank()) {
            return schemaOverride.trim();
        }
        String odsSchema = tableNameService.getOdsSchema();
        return odsSchema != null && !odsSchema.isBlank() ? odsSchema.trim() : "ods";
    }

    private String toSqlType(ColumnDef col) {
        return switch (col.type.toUpperCase()) {
            case "LONG", "INT", "INTEGER" -> "NUMERIC(20,0)";
            case "DOUBLE", "FLOAT" -> "NUMERIC(20,4)";
            default -> "TEXT";
        };
    }

    private String buildInsertSql(SyncTableDef def, String sqlTableName, int sourceEpsg, int storageEpsg) {
        List<ColumnDef> cols = def.columns;

        StringBuilder colList = new StringBuilder();
        StringBuilder valList = new StringBuilder();

        for (ColumnDef col : cols) {
            if (!colList.isEmpty()) {
                colList.append(", ");
                valList.append(", ");
            }
            colList.append(quoteIdent(col.tgtName));
            if (col.isGeometry) {
                String geomExpr = sourceEpsg != storageEpsg
                        ? "ST_Transform(ST_GeomFromText(?, " + sourceEpsg + "), " + storageEpsg + ")"
                        : "ST_GeomFromText(?, " + storageEpsg + ")";
                valList.append(geomExpr);
            } else {
                valList.append("?");
            }
        }

        boolean hasOrgCd = cols.stream().anyMatch(c -> "org_cd".equals(c.tgtName));
        if (!hasOrgCd) {
            colList.append(", ").append(quoteIdent("org_cd"));
            valList.append(", ?");
        }

        return "INSERT INTO " + sqlTableName + " (" + colList + ") VALUES (" + valList + ")";
    }

    private Object[] buildParams(SyncTableDef def, Map<String, Object> row, String orgCode, boolean hasOrgCd) {
        List<ColumnDef> cols = def.columns;
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

    private static String qualifiedTableName(String tableName) {
        String trimmed = tableName.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot < 0) return quoteIdent(trimmed);
        return quoteIdent(trimmed.substring(0, dot)) + "." + quoteIdent(trimmed.substring(dot + 1));
    }

    private static String quoteIdent(String identifier) {
        String trimmed = identifier.trim();
        if (!SAFE_IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return "\"" + trimmed + "\"";
    }
}
