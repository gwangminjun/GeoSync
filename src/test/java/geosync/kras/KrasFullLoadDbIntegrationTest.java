package geosync.kras;

import com.fasterxml.jackson.databind.ObjectMapper;
import geosync.ods.UsezoneCodeService;
import geosync.settings.RuntimeSettingsService;
import geosync.synchronization.TableMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 차세대 전체 적재(연속지적·용도지역)를 실제 kras 스키마(트리거·발행 함수·public 승격 함수)에 대고 끝까지 돌린다.
 * KRAS API·SHP 파싱만 mock이다. 테스트 전용 DB에서만(GEOSYNC_IT_DB_* 없으면 건너뜀).
 * DB 준비: kras-schema-create.sql + kras-schema-public-sync.sql + public.lp_pa_cbnd/lt_c_uzone 테이블.
 */
class KrasFullLoadDbIntegrationTest {

    private static final String WKT = "MULTIPOLYGON(((155000 315000,155500 315000,155500 315500,155000 315500,155000 315000)))";

    @TempDir Path workDir;
    private JdbcTemplate jdbc;
    private final String org = "9" + "%04d".formatted(ThreadLocalRandom.current().nextInt(10000));
    private final KrasApiClient api = mock(KrasApiClient.class);
    private final KrasWorkspaceScanner scanner = mock(KrasWorkspaceScanner.class);
    private final RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
    private final UsezoneCodeService codes = mock(UsezoneCodeService.class);
    private KrasFullLoadService fullLoad;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("GEOSYNC_IT_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "GEOSYNC_IT_DB_URL 미설정 — DB 통합 테스트 건너뜀");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("GEOSYNC_IT_DB_USER"), System.getenv("GEOSYNC_IT_DB_PASSWORD")));
        jdbc.update("UPDATE kras.sync_dataset SET contract_status='VERIFIED', enabled=true WHERE dataset_code IN "
                + "('cadastral_file','layer_list','usezone_file')");

        when(settings.krasConfig()).thenReturn("conf/kras/base-tables.xml");
        when(settings.krasWorkDir()).thenReturn(workDir.toString());
        when(settings.orgCode()).thenReturn(org);
        when(codes.getName(anyString())).thenReturn("제2종일반주거지역");
        when(api.downloadLayer(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        KrasStagePromotionService promotion = new KrasStagePromotionService();
        TableMapper tableMapper = new TableMapper();
        fullLoad = new KrasFullLoadService(api, scanner, tableMapper, settings,
                new KrasCadastralIngestService(),
                new KrasUsezoneIngestService(api, scanner, tableMapper, settings, codes),
                new KrasTxtIngestService(api, promotion),
                new KrasHistoryService(List.of(), List.of()), new KrasOperationLogService());
    }

    @Test
    void cadastralFullLoadIngestsAndPromotesToPublicAutomatically() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> r = new HashMap<>();
            r.put("pnu", org + "250211" + "%04d".formatted(i) + "0000");
            r.put("jibun", i + "대");
            r.put("bchk", "1");
            r.put("xgeometry", WKT);
            rows.add(r);
        }
        when(scanner.loadShpFile(any(), any())).thenReturn(rows);

        List<String> out = fullLoad.runCadastral(jdbc, org, "NOW", "MANUAL");

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).contains("연속지적 수집 성공: 3건");
        assertThat(out.get(1)).contains("public.lp_pa_cbnd 승격 성공");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.lp_pa_cbnd WHERE pnu LIKE ?", Long.class, org + "%"))
                .isEqualTo(3);
        assertThat(opStatuses("cadastral_file")).containsExactly("INGEST:SUCCESS", "PUBLIC:SUCCESS");
    }

    @Test
    void usezoneFullLoadRunsCatalogSweepPublishAndPublic() throws Exception {
        when(api.layerList()).thenReturn(new ObjectMapper().readTree("""
            {"layers":[{"layerName":"LSMD_CONT_UQ112"},{"layerName":"LSMD_CONT_UQ113"},{"layerName":"LSMD_CONT_LDREG"}]}
            """));
        when(scanner.loadShpFile(any(), any())).thenAnswer(inv -> List.of(usezoneRow("UQ0112")));

        List<String> out = fullLoad.runUsezone(jdbc, org, "SCHEDULE", "DAILY");

        assertThat(out).anyMatch(m -> m.contains("카탈로그 수집 성공: 2개 레이어"))
                .anyMatch(m -> m.contains("2/2건 성공"))
                .anyMatch(m -> m.contains("release 발행 성공"))
                .anyMatch(m -> m.contains("public.lt_c_uzone 승격 성공"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.lt_c_uzone WHERE org_cd=?", Long.class, org))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kras.sync_run WHERE org_cd=? AND job_kind='DAILY'",
                Long.class, org)).isPositive();
    }

    @Test
    void usezoneLayerFailingTwiceStopsBeforePublish() throws Exception {
        when(api.layerList()).thenReturn(new ObjectMapper().readTree("""
            {"layers":[{"layerName":"LSMD_CONT_UQ112"},{"layerName":"LSMD_CONT_UQ113"}]}
            """));
        when(scanner.loadShpFile(any(), any())).thenAnswer(inv ->
                inv.getArgument(0).toString().contains("UQ113") ? List.of() : List.of(usezoneRow("UQ0112")));

        List<String> out = fullLoad.runUsezone(jdbc, org, "SCHEDULE", "DAILY");

        assertThat(out).anyMatch(m -> m.contains("1회차") && m.contains("1/2건"))
                .anyMatch(m -> m.contains("2회차"))
                .last().asString().contains("발행·승격하지 않았습니다");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.lt_c_uzone WHERE org_cd=?", Long.class, org))
                .isZero();
        assertThat(opStatuses("usezone_file")).doesNotContain("PUBLISH:SUCCESS", "PUBLIC:SUCCESS");
    }

    private Map<String, Object> usezoneRow(String theme) {
        Map<String, Object> r = new HashMap<>();
        r.put("mnum", org + "256250000000000" + theme + "0001");
        r.put("remark", "");
        r.put("alias", "2종일주");
        r.put("geometry", WKT);
        return r;
    }

    private List<String> opStatuses(String dataset) {
        return jdbc.query("SELECT action||':'||status FROM kras.ui_operation_log WHERE org_cd=? AND dataset_code=? "
                + "ORDER BY operation_id", (rs, i) -> rs.getString(1), org, dataset);
    }
}
