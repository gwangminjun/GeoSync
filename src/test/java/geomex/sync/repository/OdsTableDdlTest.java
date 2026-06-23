package geomex.sync.repository;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OdsTableDdlTest {

    @Test
    void rewritesTargetSchemaWithoutChangingTableBody() throws Exception {
        String ddlScript = Files.readString(Path.of("conf/sql/sync_tables.sql"));

        String ddl = OdsTableDdl.rewriteCreateTable(ddlScript, "custom_schema.tl_spbd_buld")
                .orElseThrow();

        assertThat(ddl).startsWith("CREATE TABLE IF NOT EXISTS custom_schema.tl_spbd_buld");
        assertThat(ddl).contains("buld_sttus character varying(40)");
        assertThat(ddl).contains("zip character varying(7)");
        assertThat(ddl).contains("_geometry geometry(MultiPolygon,5176)");
        assertThat(ddl).contains("CONSTRAINT tl_spbd_buld_pkey PRIMARY KEY (_gid)");
    }

    @Test
    void matchesByBaseTableName() throws Exception {
        String ddlScript = Files.readString(Path.of("conf/sql/sync_tables.sql"));

        String ddl = OdsTableDdl.rewriteCreateTable(ddlScript, "ods.lt_c_uzone")
                .orElseThrow();

        assertThat(ddl).contains("remark character varying(100)");
        assertThat(ddl).contains("alias character varying(100)");
        assertThat(ddl).contains("uname character varying(100)");
    }
}
