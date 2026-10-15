package geosync.kras;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/**
 * 적재 DB 테이블 조회 팝업 API를 실제 kras 스키마에 대고 확인한다 — 기관 필터, pnu 앞부분 검색, 페이지, 테이블 없음.
 * 토지기본정보 TXT 기준선(실제 코드)으로 임의 기관의 필지를 만든다. 테스트 전용 DB에서만(GEOSYNC_IT_DB_* 없으면 건너뜀).
 */
class KrasTableBrowserDbIntegrationTest {

    private static final char VT = 11;
    private JdbcTemplate jdbc;
    private final String org = "9" + "%04d".formatted(ThreadLocalRandom.current().nextInt(10000));
    private final KrasApiClient api = mock(KrasApiClient.class);
    private final KrasStagePromotionService promotion = new KrasStagePromotionService();
    private final LandChangeMapper landChangeMapper = new LandChangeMapper("IT-LAND-CHANGE", "ST_YMD", "ED_YMD", "yyyyMMdd");
    private final KrasIncrementalSyncService sync = new KrasIncrementalSyncService(
            new KrasHistoryService(List.of(), List.of(landChangeMapper)), new KrasOperationLogService(),
            new KrasDateRangeIngestService(api, promotion), new KrasTxtIngestService(api, promotion), landChangeMapper);

    @BeforeEach
    void connect() {
        String url = System.getenv("GEOSYNC_IT_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "GEOSYNC_IT_DB_URL 미설정 — DB 통합 테스트 건너뜀");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("GEOSYNC_IT_DB_USER"), System.getenv("GEOSYNC_IT_DB_PASSWORD")));
        jdbc.update("UPDATE kras.sync_dataset SET contract_status='VERIFIED', enabled=true WHERE dataset_code='land_basic_file'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void browsesOnlyTheCurrentOrgAndSearchesByPnuPrefix() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 55; i++) {
            String p = pnu(i < 50 ? "25021" : "25022", i);
            sb.append(String.join(String.valueOf(VT), p.substring(0, 5), p.substring(5, 10), p.substring(10, 11),
                    p.substring(11, 15), p.substring(15), "01", "100", "1")).append('\n');
        }
        when(api.downloadLandTxt()).thenReturn(sb.toString().getBytes(StandardCharsets.US_ASCII));
        assertThat(sync.runMonthlyCorrection(jdbc, org)).last().asString().contains("반영 성공");

        Set<String> allowed = KrasSchemaController.BROWSABLE_TABLES;
        Map<String, Object> all = KrasTableBrowser.browse(jdbc, allowed, "kras.land_basic", org, null, null, 0);
        assertThat(all).containsEntry("exists", true).containsEntry("orgFiltered", true)
                .containsEntry("total", 55).containsEntry("defaultColumn", "pnu");
        assertThat((List<?>) all.get("rows")).hasSize(KrasTableBrowser.PAGE_SIZE);
        assertThat((List<?>) KrasTableBrowser.browse(jdbc, allowed, "kras.land_basic", org, null, null, 1).get("rows"))
                .hasSize(5);

        Map<String, Object> found = KrasTableBrowser.browse(jdbc, allowed, "kras.land_basic", org, "pnu",
                org + "25022", 0);
        assertThat(found).containsEntry("total", 6);
        List<KrasTableBrowser.Column> cols = (List<KrasTableBrowser.Column>) found.get("columns");
        int pnuIdx = cols.stream().map(KrasTableBrowser.Column::name).toList().indexOf("pnu");
        assertThat((List<List<String>>) found.get("rows")).allSatisfy(r -> assertThat(r.get(pnuIdx)).startsWith(org + "25022"));

        // 다른 기관 행은 보이지 않는다.
        assertThat(KrasTableBrowser.browse(jdbc, allowed, "kras.land_basic", "00000", "pnu", org, 0))
                .containsEntry("total", 0);
        // 이 기관 행이 없으면 다른 기관에 행이 있는지 알려준다(검색하지 않았을 때만).
        assertThat((List<Map<String, Object>>) KrasTableBrowser.browse(jdbc, allowed, "kras.land_basic", "00000",
                null, null, 0).get("otherOrgs")).isNotEmpty();
        assertThatThrownBy(() -> KrasTableBrowser.browse(jdbc, allowed, "kras.land_basic", org, "no_such_col", "1", 0))
                .isInstanceOf(KrasTableBrowser.BrowseException.class);
        assertThat(KrasTableBrowser.browse(jdbc, Set.of("kras.no_such_table"), "kras.no_such_table", org, null, null, 0))
                .containsEntry("exists", false);

        // 허용 테이블에서 파생된 뷰는 목록에 나오고 같은 규칙으로 열린다. 허용 테이블과 무관한 뷰는 막는다.
        assertThat(KrasTableBrowser.views(jdbc, allowed, "kras.land_basic", org))
                .extracting(v -> v.get("name")).contains("kras.land_frst_ledg");
        assertThat(KrasTableBrowser.browse(jdbc, allowed, "kras.land_frst_ledg", org, null, null, 0))
                .containsEntry("exists", true);
        assertThatThrownBy(() -> KrasTableBrowser.browse(jdbc, allowed, "kras.api_current_response", org, null, null, 0))
                .isInstanceOf(KrasTableBrowser.BrowseException.class);

        // 지도 미리보기: SHAPE 다운로드 테이블이 아니면 거절, 맞으면 범위·레이어(배경 → 대상)를 준다.
        Set<String> preview = KrasSchemaController.PREVIEW_TABLES;
        assertThatThrownBy(() -> KrasTableBrowser.extent(jdbc, allowed, preview, "kras.land_basic", org, null, null, null,
                List.of("lp_adm_emd"))).hasMessageContaining("SHAPE 다운로드");
        assertThatThrownBy(() -> KrasTableBrowser.extent(jdbc, allowed, preview, "kras.sync_dataset", org, null, null, null,
                List.of("lp_adm_emd"))).hasMessageContaining("조회할 수 없는");
        Map<String, Object> ext = KrasTableBrowser.extent(jdbc, allowed, preview, "public.lt_c_uzone", "00000", null, null, null,
                List.of("lp_adm_emd", "lp_pa_cbnd"));
        assertThat(ext).containsEntry("previewable", true).containsEntry("total", 0L).containsEntry("bbox", null);
        assertThat((List<Map<String, Object>>) ext.get("layers")).extracting(l -> l.get("name") + ":" + l.get("role"))
                .containsExactly("lp_adm_emd:background", "lp_pa_cbnd:background", "lt_c_uzone:target");
        // 행 한 건 미리보기: 행 식별 칼럼(mnum) 조건이 범위와 지오서버 CQL_FILTER에 같이 들어간다(따옴표는 두 번).
        assertThat(KrasTableBrowser.extent(jdbc, allowed, preview, "public.lt_c_uzone", "00000", null, null, "a'b",
                List.of())).containsEntry("total", 0L).containsEntry("cqlFilter", "mnum = 'a''b'");
        // 한 건을 고르면 대상과 같은 배경도 남기고(주변 맥락), 대상은 강조 스타일을 붙인다.
        Map<String, Object> pickedExt = KrasTableBrowser.extent(jdbc, allowed, preview, "public.lt_c_uzone", "00000",
                null, null, "x", List.of("lt_c_uzone"));
        assertThat((List<Map<String, Object>>) pickedExt.get("layers")).extracting(l -> l.get("name") + ":" + l.get("role"))
                .containsExactly("lt_c_uzone:background", "lt_c_uzone:target");
        assertThat((String) ((List<Map<String, Object>>) pickedExt.get("layers")).get(1).get("sld"))
                .contains(KrasLayerStyleSld.HIGHLIGHT_RULE);
        assertThat(KrasTableBrowser.browse(jdbc, allowed, "public.lt_c_uzone", "00000", null, null, 0))
                .containsEntry("rowKey", "mnum");
    }

    private String pnu(String landLoc, int bobn) {
        return org + landLoc + "1" + "%04d".formatted(bobn) + "0000";
    }
}
