package geomex.sync.repository;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OdsTableDdlTest {

    @Test
    void rewritesTargetSchemaWithoutChangingTableBody() throws Exception {
        String ddlScript = Files.readString(Path.of("conf/sql/sync_public_tables.sql"));

        String ddl = OdsTableDdl.rewriteCreateTable(ddlScript, "custom_schema.lt_c_uzone")
                .orElseThrow();

        assertThat(ddl).startsWith("CREATE TABLE IF NOT EXISTS custom_schema.lt_c_uzone");
        assertThat(ddl).contains("layer_code");
        assertThat(ddl).contains("theme_code");
        assertThat(ddl).contains("theme_name");
        assertThat(ddl).contains("geom");
    }

    @Test
    void matchesByBaseTableName() throws Exception {
        String ddlScript = Files.readString(Path.of("conf/sql/sync_public_tables.sql"));

        String ddl = OdsTableDdl.rewriteCreateTable(ddlScript, "ods.lp_pa_cbnd")
                .orElseThrow();

        assertThat(ddl).contains("pnu");
        assertThat(ddl).contains("jibun");
        assertThat(ddl).contains("geom");
    }
}
