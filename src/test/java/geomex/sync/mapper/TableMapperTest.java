package geomex.sync.mapper;

import geomex.sync.model.SyncTableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableMapperTest {

    private final TableMapper mapper = new TableMapper();

    @Test
    void loadsKrasBaseTables() {
        List<SyncTableDef> tables = mapper.load("../GEOMEX-SYNC-HOME/conf/kras/base-tables.xml");

        assertThat(tables).isNotEmpty();
        SyncTableDef cbnd = tables.stream()
                .filter(t -> t.tgtTableName.equals("ods.lp_pa_cbnd"))
                .findFirst().orElseThrow();

        assertThat(cbnd.hasGeometry()).isTrue();
        assertThat(cbnd.keyColumns()).isNotEmpty();
    }

    @Test
    void loadsKaisBaseTables() {
        List<SyncTableDef> tables = mapper.load("../GEOMEX-SYNC-HOME/conf/kais/base-tables.xml");

        assertThat(tables).isNotEmpty();
        SyncTableDef buld = tables.stream()
                .filter(t -> t.tgtTableName.equals("ods.tl_spbd_buld"))
                .findFirst().orElseThrow();

        assertThat(buld.hasGeometry()).isTrue();
    }

    @Test
    void buildUpsertSqlContainsOnConflict() {
        List<SyncTableDef> tables = mapper.load("../GEOMEX-SYNC-HOME/conf/kras/base-tables.xml");
        SyncTableDef cbnd = tables.stream()
                .filter(t -> t.tgtTableName.equals("ods.lp_pa_cbnd"))
                .findFirst().orElseThrow();

        String sql = mapper.buildUpsertSql(cbnd, 5176);
        assertThat(sql).containsIgnoringCase("INSERT INTO ods.lp_pa_cbnd");
        assertThat(sql).containsIgnoringCase("ST_GeomFromText");
    }
}
