package geomex.sync.repository;

import geomex.sync.mapper.TableMapper;
import geomex.sync.model.SyncTableDef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class OdsRepositorySqlTest {

    @Test
    void transformsKrasGeometryDirectlyToStorageSrid() throws Exception {
        SyncTableDef cbnd = new TableMapper().load("conf/kras/base-tables.xml").stream()
                .filter(t -> t.tgtTableName.equals("lp_pa_cbnd") || t.tgtTableName.equals("ods.lp_pa_cbnd"))
                .findFirst()
                .orElseThrow();

        OdsRepository repository = new OdsRepository(null, null, null);
        Method buildInsertSql = OdsRepository.class.getDeclaredMethod(
                "buildInsertSql", SyncTableDef.class, String.class, int.class, int.class);
        buildInsertSql.setAccessible(true);

        String sql = (String) buildInsertSql.invoke(repository, cbnd, "ods.lp_pa_cbnd", 5174, 5186);

        assertThat(sql).contains("ST_Transform(ST_GeomFromText(?, 5174), 5186)");
    }
}
