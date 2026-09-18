package geosync.ods;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OdsTableDdl {

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)?)\\s*\\((.*?)\\)\\s*;");

    private OdsTableDdl() {
    }

    static Optional<String> rewriteCreateTable(String ddlScript, String targetTableName) {
        return rewriteCreateTable(ddlScript, targetTableName, targetTableName);
    }

    static Optional<String> rewriteCreateTable(String ddlScript, String lookupTableName, String sqlTableName) {
        String targetBaseName = baseName(lookupTableName);
        Matcher matcher = CREATE_TABLE_PATTERN.matcher(ddlScript);
        while (matcher.find()) {
            String sourceTableName = matcher.group(1);
            if (baseName(sourceTableName).equalsIgnoreCase(targetBaseName)) {
                String body = matcher.group(2);
                return Optional.of("CREATE TABLE IF NOT EXISTS " + sqlTableName + " ("
                        + body
                        + ");");
            }
        }
        return Optional.empty();
    }

    static List<CreateTable> createTables(String ddlScript) {
        List<CreateTable> tables = new ArrayList<>();
        Matcher matcher = CREATE_TABLE_PATTERN.matcher(ddlScript);
        while (matcher.find()) {
            tables.add(new CreateTable(matcher.group(1), matcher.group(2)));
        }
        return tables;
    }

    private static String baseName(String tableName) {
        int dot = tableName.lastIndexOf('.');
        return dot >= 0 ? tableName.substring(dot + 1) : tableName;
    }

    record CreateTable(String tableName, String body) {
        String baseName() {
            return OdsTableDdl.baseName(tableName);
        }

        String createSql(String sqlTableName) {
            return "CREATE TABLE " + sqlTableName + " (" + body + ");";
        }
    }
}
