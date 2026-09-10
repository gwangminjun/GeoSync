package geomex.sync.synchronization.model;

public class ColumnDef {
    public final String srcName;
    public final String tgtName;
    public final String type;
    public final boolean isKey;
    public final boolean isGeometry;

    public ColumnDef(String srcName, String tgtName, String type, boolean isKey) {
        this.srcName = srcName;
        this.tgtName = tgtName;
        this.type = type;
        this.isKey = isKey;
        this.isGeometry = type.contains("POLYGON") || type.contains("POINT") || type.contains("LINE");
    }
}
