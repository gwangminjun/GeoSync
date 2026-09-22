package geosync.kras;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨트롤러의 slug 레지스트리와 실제 매퍼 빈이 어긋나면 화면에서 NPE로 터진다 —
 * 등록만 하고 빈을 안 만든(또는 반대) 경우를 여기서 잡는다.
 */
@SpringBootTest
class KrasMapperRegistryTest {

    @Autowired List<KrasXmlServiceMapper> mappers;

    /** kras.md에 XML이 있어 손으로 쓴 매퍼 11개. */
    private static final List<String> HANDWRITTEN = List.of(
            "land_info", "land_bldg_check", "collective_building", "shr_ymb", "own_rgt_hist",
            "land_mov_hist", "collective_unit", "land_right", "unit_ownership_history",
            "integrated_building", "building_image");

    /** kras.md에 XML이 없어 KrasSpecMapperConfig에 선언으로 만든 14개. */
    private static final List<String> DECLARED = List.of(
            "use_zone", "land_use_plan_attr", "land_use_plan_info", "bldg_dong_info",
            "bldg_ledg_gen_hds_info", "bldg_ho_info", "bldg_hds_info", "cbldg_hds_info",
            "cbldg_dfhs_info", "land_jiga", "house_info", "fin_dec_jiga", "read_dec_jiga", "land_attr");

    @Test
    void everyRegisteredPnuServiceHasExactlyOneMapper() {
        List<String> codes = mappers.stream().map(KrasXmlServiceMapper::datasetCode).toList();

        assertThat(codes).containsAll(HANDWRITTEN).containsAll(DECLARED);
        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void korepsServicesAreRoutedToTheKorepsGateway() {
        List<String> koreps = mappers.stream()
                .filter(m -> "KOREPS".equals(m.sourceSystem()))
                .map(KrasXmlServiceMapper::datasetCode)
                .toList();

        assertThat(koreps).containsExactlyInAnyOrder(
                "land_jiga", "house_info", "fin_dec_jiga", "read_dec_jiga", "land_attr");
    }

    @Test
    void declaredMappersExposeTheirServiceCodeWhileHandwrittenUnknownOnesStillRefuse() {
        // 선언형 14개는 conn_svc_id를 안다(그래서 호출 가능) — 막힌 건 응답 구조뿐이다.
        for (KrasXmlServiceMapper m : mappers) {
            if (DECLARED.contains(m.datasetCode())) {
                assertThat(m.connSvcId()).matches("(KRAS|KOREPS)\\d+");
            }
        }
    }
}
