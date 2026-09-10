package geomex.sync.ods;

import geomex.sync.synchronization.TableMapper;
import geomex.sync.synchronization.model.SyncTableDef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

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

    @Test
    void uzoneApiGeometryIsTransformedFrom5174To5186() throws Exception {
        // USEZONE(lt_c_uzone): API가 srsName:5174로 반환 → 5174→5186 변환이 적용돼야 한다
        SyncTableDef uzone = new TableMapper().load("conf/kras/base-tables.xml").stream()
                .filter(t -> t.srcTableName.startsWith("USEZONE:"))
                .findFirst()
                .orElseThrow();

        OdsRepository repository = new OdsRepository(null, null, null);
        Method buildInsertSql = OdsRepository.class.getDeclaredMethod(
                "buildInsertSql", SyncTableDef.class, String.class, int.class, int.class);
        buildInsertSql.setAccessible(true);

        // sourceEpsg=5174 (KRAS_EPSG), storageEpsg=5186
        String sql = (String) buildInsertSql.invoke(repository, uzone, "\"ods\".\"lt_c_uzone\"", 5174, 5186);

        assertThat(sql).contains("ST_Transform(ST_GeomFromText(?, 5174), 5186)");
    }

    @Test
    void mapsPublicGeometryColumnAndSkipsMissingOrgCode() throws Exception {
        SyncTableDef cbnd = new TableMapper().load("conf/kras/base-tables.xml").stream()
                .filter(t -> t.tgtTableName.equals("lp_pa_cbnd") || t.tgtTableName.equals("ods.lp_pa_cbnd"))
                .findFirst()
                .orElseThrow();

        OdsRepository repository = new OdsRepository(null, null, null);
        Method buildInsertPlan = OdsRepository.class.getDeclaredMethod(
                "buildInsertPlan", SyncTableDef.class, String.class, int.class, int.class, Set.class);
        buildInsertPlan.setAccessible(true);

        Object plan = buildInsertPlan.invoke(repository, cbnd, "\"public\".\"lp_pa_cbnd\"", 5174, 5186,
                Set.of("uid", "geom", "jibun", "bchk", "pnu"));
        Method sql = plan.getClass().getDeclaredMethod("sql");
        sql.setAccessible(true);

        assertThat((String) sql.invoke(plan))
                .contains("\"geom\"")
                .contains("ST_Transform(ST_GeomFromText(?, 5174), 5186)")
                .doesNotContain("\"org_cd\"");
    }
}
