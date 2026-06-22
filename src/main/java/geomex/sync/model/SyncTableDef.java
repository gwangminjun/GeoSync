package geomex.sync.model;

import java.util.List;

public class SyncTableDef {
    public final String srcTableName;
    public final String tgtTableName;
    public final String geomType;
    public final String srcClause;
    public final List<ColumnDef> columns;

    public SyncTableDef(String srcTableName, String tgtTableName,
                        String geomType, String srcClause, List<ColumnDef> columns) {
        this.srcTableName = srcTableName;
        this.tgtTableName = tgtTableName;
        this.geomType = geomType;
        this.srcClause = srcClause;
        this.columns = columns;
    }

    public List<ColumnDef> keyColumns() {
        return columns.stream().filter(c -> c.isKey).toList();
    }

    public List<ColumnDef> dataColumns() {
        return columns.stream().filter(c -> !c.isKey).toList();
    }

    public boolean hasGeometry() {
        return columns.stream().anyMatch(c -> c.isGeometry);
    }
}
