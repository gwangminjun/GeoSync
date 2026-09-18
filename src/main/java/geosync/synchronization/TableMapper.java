package geosync.synchronization;

import geosync.synchronization.model.ColumnDef;
import geosync.synchronization.model.SyncTableDef;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Component
public class TableMapper {

    private static final Logger log = LoggerFactory.getLogger(TableMapper.class);

    public List<SyncTableDef> load(String configPath) {
        List<SyncTableDef> result = new ArrayList<>();
        try {
            Document doc = new SAXBuilder().build(new File(configPath));
            for (Element tableEl : doc.getRootElement().getChildren("table")) {
                if ("true".equalsIgnoreCase(tableEl.getAttributeValue("disabled"))) continue;
                result.add(parseTable(tableEl));
            }
        } catch (Exception e) {
            throw new IllegalStateException("base-tables.xml 로드 실패: " + configPath, e);
        }
        log.info("TableMapper 로드: {} → {}개 테이블", configPath, result.size());
        return result;
    }

    private SyncTableDef parseTable(Element tableEl) {
        String geomType  = tableEl.getAttributeValue("type", "ATTRIBUTE");
        String srcTable  = tableEl.getChildTextTrim("src-table-name");
        String tgtTable  = tableEl.getChildTextTrim("tgt-table-name");
        String srcClause = tableEl.getChildTextTrim("src-clause");

        List<ColumnDef> columns = new ArrayList<>();
        Element columnsEl = tableEl.getChild("columns");
        if (columnsEl != null) {
            for (Element colEl : columnsEl.getChildren("column")) {
                columns.add(parseColumn(colEl));
            }
        }
        return new SyncTableDef(srcTable, tgtTable, geomType, srcClause, columns);
    }

    private ColumnDef parseColumn(Element colEl) {
        boolean isKey = "true".equalsIgnoreCase(colEl.getAttributeValue("key"));
        Element srcEl = colEl.getChild("src");
        Element tgtEl = colEl.getChild("tgt");

        String srcName = srcEl != null ? srcEl.getAttributeValue("name") : "";
        String type    = srcEl != null ? srcEl.getAttributeValue("type", "STRING") : "STRING";
        String tgtName = tgtEl != null ? tgtEl.getAttributeValue("name") : srcName;

        return new ColumnDef(srcName, tgtName, type, isKey);
    }

    // INSERT SQL 생성 (ON CONFLICT DO UPDATE 방식)
    public String buildUpsertSql(SyncTableDef def, int epsg) {
        List<ColumnDef> allCols = def.columns;

        String colList = allCols.stream()
                .map(c -> c.tgtName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        String valList = allCols.stream()
                .map(c -> c.isGeometry ? "ST_GeomFromText(?, " + epsg + ")" : "?")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        // org_cd 컬럼은 항상 포함 (매핑에 없으면 추가)
        boolean hasOrgCd = allCols.stream().anyMatch(c -> "org_cd".equals(c.tgtName));
        if (!hasOrgCd) {
            colList += ", org_cd";
            valList += ", ?";
        }

        List<ColumnDef> keyCols = def.keyColumns();
        if (keyCols.isEmpty()) {
            return String.format("INSERT INTO %s (%s) VALUES (%s)", def.tgtTableName, colList, valList);
        }

        String conflictCols = keyCols.stream()
                .map(c -> c.tgtName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        String updateSet = def.dataColumns().stream()
                .filter(c -> !c.isKey)
                .map(c -> c.tgtName + " = EXCLUDED." + c.tgtName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        if (updateSet.isEmpty()) {
            return String.format(
                    "INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO NOTHING",
                    def.tgtTableName, colList, valList, conflictCols);
        }
        return String.format(
                "INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO UPDATE SET %s",
                def.tgtTableName, colList, valList, conflictCols, updateSet);
    }

    // 대상 테이블 전체 삭제 (전체 교체 방식 sync 전 호출)
    public String buildDeleteSql(SyncTableDef def) {
        return "DELETE FROM " + def.tgtTableName + " WHERE org_cd = ?";
    }
}
