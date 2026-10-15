package geosync.kras;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 실제 kras 스키마(트리거 포함)에 대고 증분 동기화를 검증한다 — mock JdbcTemplate으로는
 * guard_business_row·CHECK·FK가 막는 경우를 못 잡는다. KRAS API만 mock이다.
 *
 * 테스트 전용 DB에서만 돈다(운영 DB 금지 — SUCCESS 수집 건은 트리거상 지울 수 없다).
 * GEOSYNC_IT_DB_URL/USER/PASSWORD가 없으면 건너뛴다. DB 준비:
 * docs/database/2026-09-18/kras-schema-create.sql을 빈 DB(PostGIS 설치)에 실행.
 * 수집 건을 지울 수 없어 실행마다 임의 기관코드를 써서 서로 섞이지 않게 한다.
 */
class KrasLandChangeDbIntegrationTest {

    private static final char VT = 11;
    private static final String SVC = "IT-LAND-CHANGE";

    private JdbcTemplate jdbc;
    private final String org = "9" + "%04d".formatted(ThreadLocalRandom.current().nextInt(10000));
    private final KrasApiClient api = mock(KrasApiClient.class);
    private final KrasStagePromotionService promotion = new KrasStagePromotionService();
    private final KrasTxtIngestService txt = new KrasTxtIngestService(api, promotion);
    private final KrasDateRangeIngestService range = new KrasDateRangeIngestService(api, promotion);
    private final LandChangeMapper mapper = new LandChangeMapper(SVC, "ST_YMD", "ED_YMD", "yyyyMMdd");
    private final KrasIncrementalSyncService sync = new KrasIncrementalSyncService(
            new KrasHistoryService(List.of(), List.of(mapper)), new KrasOperationLogService(), range, txt, mapper);
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void connect() {
        String url = System.getenv("GEOSYNC_IT_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "GEOSYNC_IT_DB_URL 미설정 — DB 통합 테스트 건너뜀");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("GEOSYNC_IT_DB_USER"), System.getenv("GEOSYNC_IT_DB_PASSWORD")));
        jdbc.update("UPDATE kras.sync_dataset SET contract_status='VERIFIED', enabled=true "
                + "WHERE dataset_code IN ('land_basic_file','land_change')");
        jdbc.update("UPDATE kras.sync_dataset SET service_code=? WHERE dataset_code='land_change'", SVC);
    }

    @Test
    void baselineThenDailyThenMonthlyCorrectionKeepsParcelStateInLineWithSource() throws Exception {
        List<String> parcels = new ArrayList<>();
        for (int i = 1; i <= 30; i++) parcels.add(pnu(i));
        String a = parcels.get(0), b = parcels.get(1), c = pnu(100);

        // 1) 기준선 = 첫 월 보정. 전체 행이 반영돼야 한다(예전엔 row_no=1 한 건만 들어갔다).
        txtReturns(parcels);
        assertThat(sync.runDaily(jdbc, org, today)).anyMatch(r -> r.contains("기준선"));   // 기준선 전에는 안 돈다
        List<String> baseline = sync.runMonthlyCorrection(jdbc, org);
        assertThat(baseline).last().asString().contains("반영 성공");
        assertThat(count("SELECT count(*) FROM kras.parcel WHERE org_cd=? AND record_status='ACTIVE'")).isEqualTo(30);
        assertThat(sync.baselineDate(jdbc, org)).contains(today.minusDays(1));

        // 2) 일일 증분: a 분할(→c 신규), b 지목 변경. 기간 [T0, 어제]가 날짜 파라미터로 나간다.
        when(api.query(anyString(), anyString(), any())).thenReturn(xml(
                event(1, a, c, "02", "50", today.minusDays(1)),
                event(2, b, b, "08", "200", today.minusDays(1))));
        List<String> daily = sync.runDaily(jdbc, org, today.plusDays(3));
        assertThat(daily).hasSize(2).last().asString().contains("반영 성공");
        assertThat(status(a)).isEqualTo("SOURCE_CLOSED");
        assertThat(status(c)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT jimok FROM kras.land_basic WHERE pnu=?", String.class, b)).isEqualTo("08");
        verify(api).query(eq(SVC), eq(""), eq(Map.of("ST_YMD", ymd(today.minusDays(1)), "ED_YMD", ymd(today.plusDays(2)))));
        assertThat(count("SELECT count(*) FROM kras.sync_run WHERE org_cd=? AND job_kind='DAILY'")).isEqualTo(1);

        // 같은 날 다시 돌면 새 구간이 없다(커서가 반영 완료 구간 다음으로 갔다).
        assertThat(sync.runDaily(jdbc, org, today.plusDays(3))).singleElement().asString().contains("새로 조회할 구간이 없습니다");

        // 3) 월 보정: 파일에 a가 다시 있고(재활성) parcels[29]는 없다(미관측, 1/31 < 5%).
        List<String> file = new ArrayList<>(parcels.subList(0, 29));
        file.add(c);
        txtReturns(file);
        List<String> monthly = sync.runMonthlyCorrection(jdbc, org);
        assertThat(monthly).last().asString().contains("재활성 1건", "미관측 1건");
        assertThat(status(a)).isEqualTo("ACTIVE");
        assertThat(status(parcels.get(29))).isEqualTo("NOT_OBSERVED");
        assertThat(count("SELECT count(*) FROM kras.sync_run WHERE org_cd=? AND job_kind='MONTHLY'")).isEqualTo(2);
        // land_change가 지나간 필지(b, c)도 월 보정 TXT 반영이 트리거에 막히지 않는다.
        assertThat(jdbc.queryForObject("SELECT jimok FROM kras.land_basic WHERE pnu=?", String.class, b)).isEqualTo("01");
    }

    @Test
    void monthlyCorrectionSkipsMarkingWhenTooManyParcelsVanish() throws Exception {
        List<String> parcels = List.of(pnu(1), pnu(2), pnu(3));
        txtReturns(parcels);
        sync.runMonthlyCorrection(jdbc, org);

        txtReturns(List.of(pnu(1)));   // 3건 중 2건이 사라짐 → 잘린 파일로 보고 표시 안 함
        assertThat(sync.runMonthlyCorrection(jdbc, org)).last().asString().contains("상태 보정을 건너뜀");
        assertThat(status(pnu(2))).isEqualTo("ACTIVE");
    }

    @Test
    void failedWindowDoesNotAdvanceCursorAndDayWithoutMovementDoes() throws Exception {
        txtReturns(List.of(pnu(1)));
        sync.runMonthlyCorrection(jdbc, org);

        when(api.query(anyString(), anyString(), any())).thenThrow(new RuntimeException("게이트웨이 연결 실패"));
        assertThat(sync.runDaily(jdbc, org, today.plusDays(1))).singleElement().asString().contains("게이트웨이 연결 실패");
        assertThat(range.nextWindowStart(jdbc, org, mapper)).isEmpty();

        reset(api);
        when(api.query(anyString(), anyString(), any())).thenReturn(xml());   // 이동 없는 날
        assertThat(sync.runDaily(jdbc, org, today.plusDays(1))).last().asString().contains("반영 성공");
        assertThat(range.nextWindowStart(jdbc, org, mapper)).contains(today.plusDays(1));
    }

    private void txtReturns(List<String> pnus) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String p : pnus) {
            sb.append(String.join(String.valueOf(VT), p.substring(0, 5), p.substring(5, 10), p.substring(10, 11),
                    p.substring(11, 15), p.substring(15), "01", "100", "1")).append('\n');
        }
        when(api.downloadLandTxt()).thenReturn(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, org);
    }

    private String status(String pnu) {
        return jdbc.queryForObject("SELECT record_status FROM kras.parcel WHERE pnu=?", String.class, pnu);
    }

    private String pnu(int bobn) {
        return org + "25021" + "1" + "%04d".formatted(bobn) + "0000";
    }

    private static String ymd(LocalDate d) {
        return d.toString().replace("-", "");
    }

    private String event(int no, String bf, String af, String afJimok, String afParea, LocalDate day) {
        return "<LAND_MOV_CHG><LAND_MOV_NO>IT" + org + "-" + no + "</LAND_MOV_NO><LAND_MOV_NM>분할</LAND_MOV_NM>"
                + "<LAND_MOV_ITEM>1</LAND_MOV_ITEM>"
                + "<BF_LAND_LOC_CD>" + bf.substring(5, 10) + "</BF_LAND_LOC_CD><BF_LEDG_GBN>1</BF_LEDG_GBN>"
                + "<BF_BOBN>" + bf.substring(11, 15) + "</BF_BOBN><BF_BUBN>0000</BF_BUBN><BF_JIMOK>01</BF_JIMOK>"
                + "<BF_PAREA>100</BF_PAREA>"
                + "<AF_LAND_LOC_CD>" + af.substring(5, 10) + "</AF_LAND_LOC_CD><AF_LEDG_GBN>1</AF_LEDG_GBN>"
                + "<AF_BOBN>" + af.substring(11, 15) + "</AF_BOBN><AF_BUBN>0000</AF_BUBN><AF_JIMOK>" + afJimok
                + "</AF_JIMOK><AF_PAREA>" + afParea + "</AF_PAREA>"
                + "<LAND_MOV_RSN_CD>20</LAND_MOV_RSN_CD><LAND_MOV_RSN_NM>분할</LAND_MOV_RSN_NM>"
                + "<ADJ_YMD>" + ymd(day) + "</ADJ_YMD><HNDL_YMD>" + ymd(day) + "</HNDL_YMD></LAND_MOV_CHG>";
    }

    private static byte[] xml(String... events) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><LAND_MOV_CHG_SET>" + String.join("", events)
                + "</LAND_MOV_CHG_SET>").getBytes(StandardCharsets.UTF_8);
    }
}
