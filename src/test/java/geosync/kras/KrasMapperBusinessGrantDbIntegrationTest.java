package geosync.kras;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 매퍼가 반영하겠다고 선언한 업무 테이블마다 kras.business_dataset에 (테이블, 데이터셋) 짝이 있는지 대조한다.
 * 짝이 없으면 guard_business_row 트리거가 반영을 막는다(2026-10-15 대기열 실서버 확인에서 shr_ymb·own_rgt_hist → parcel이 막힘).
 * 테스트 전용 DB에서만(GEOSYNC_IT_DB_* 없으면 건너뜀).
 */
class KrasMapperBusinessGrantDbIntegrationTest {

    private static List<KrasXmlServiceMapper> allMappers() {
        KrasSpecMapperConfig c = new KrasSpecMapperConfig();
        return List.of(new LandInfoMapper(), new ShrYmbMapper(), new LandMovHistMapper(), new OwnRgtHistMapper(),
                new LandBldgCheckMapper(), new CollectiveBuildingMapper(), new CollectiveUnitMapper(),
                new LandRightMapper(), new UnitOwnershipHistoryMapper(), new IntegratedBuildingMapper(),
                new BuildingImageMapper(),
                c.useZoneMapper(), c.landUsePlanAttrMapper(), c.landUsePlanInfoMapper(), c.bldgDongInfoMapper(),
                c.bldgLedgGenHdsInfoMapper(), c.bldgHoInfoMapper(), c.bldgHdsInfoMapper(), c.cbldgHdsInfoMapper(),
                c.cbldgDfhsInfoMapper(), c.landJigaMapper(), c.houseInfoMapper(), c.finDecJigaMapper(),
                c.readDecJigaMapper(), c.landAttrMapper());
    }

    @Test
    void everyMapperIsAllowedToPopulateTheTablesItDeclares() {
        String url = System.getenv("GEOSYNC_IT_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "GEOSYNC_IT_DB_URL 미설정 — DB 통합 테스트 건너뜀");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("GEOSYNC_IT_DB_USER"), System.getenv("GEOSYNC_IT_DB_PASSWORD")));
        Set<String> granted = new TreeSet<>(jdbc.queryForList(
                "SELECT table_name || ':' || dataset_code FROM kras.business_dataset", String.class));

        Set<String> missing = new TreeSet<>();
        for (KrasXmlServiceMapper m : allMappers()) {
            for (KrasStagePromotionService.StagePromotionSpec spec : m.promotionSpecs()) {
                List<String> tables = new ArrayList<>(List.of(spec.businessTable()));
                if (spec.children() != null) spec.children().forEach(ch -> tables.add(ch.businessTable()));
                for (String t : tables) {
                    String key = t.replace("kras.", "") + ":" + m.datasetCode();
                    if (!granted.contains(key)) missing.add(key);
                }
            }
        }
        assertThat(missing).as("kras.business_dataset에 없는 (테이블:데이터셋)").isEmpty();
    }
}
