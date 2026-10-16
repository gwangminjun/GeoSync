package geosync.kras;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PNU 대기열(kras.pnu_work)을 실제 kras 스키마에 대고 끝까지 돌린다 — 등록·꺼내기·수집·반영·재시도·중단 복구.
 * KRAS API만 mock이고 매퍼·수집·반영은 실제 코드다. 테스트 전용 DB에서만(GEOSYNC_IT_DB_* 없으면 건너뜀).
 * DB 준비: kras-schema-create.sql (+ 2026-10-15 pnu_work 마이그레이션).
 */
class KrasPnuQueueDbIntegrationTest {

    private static final char VT = 11;
    private static final String SVC = "IT-LAND-CHANGE";
    private static final List<String> DATASETS = List.of("land_bldg_check", "bldg_dong_info");

    private JdbcTemplate jdbc;
    private final String org = "9" + "%04d".formatted(ThreadLocalRandom.current().nextInt(10000));
    private final KrasApiClient api = mock(KrasApiClient.class);
    private final KrasStagePromotionService promotion = new KrasStagePromotionService();
    private final KrasPnuIngestService ingest = new KrasPnuIngestService(api, mock(KorepsApiClient.class), promotion, null);
    private final KrasPnuQueueService queue = new KrasPnuQueueService();
    private final List<KrasXmlServiceMapper> mappers = List.of(new LandBldgCheckMapper(),
            new KrasSpecMapperConfig().bldgDongInfoMapper(), new LandInfoMapper());
    private final KrasPnuQueueWorker worker = new KrasPnuQueueWorker(ingest, queue,
            new KrasHistoryService(mappers, List.of()), mappers);
    private final LandChangeMapper landChangeMapper = new LandChangeMapper(SVC, "ST_YMD", "ED_YMD", "yyyyMMdd");
    private final KrasIncrementalSyncService sync = new KrasIncrementalSyncService(
            new KrasHistoryService(List.of(), List.of(landChangeMapper)), new KrasOperationLogService(),
            new KrasDateRangeIngestService(api, promotion), new KrasTxtIngestService(api, promotion), landChangeMapper);

    @BeforeEach
    void connect() {
        String url = System.getenv("GEOSYNC_IT_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "GEOSYNC_IT_DB_URL 미설정 — DB 통합 테스트 건너뜀");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("GEOSYNC_IT_DB_USER"), System.getenv("GEOSYNC_IT_DB_PASSWORD")));
        jdbc.update("UPDATE kras.sync_dataset SET contract_status='VERIFIED', enabled=true WHERE dataset_code IN "
                + "('land_basic_file','land_change','land_bldg_check','bldg_dong_info','land_info')");
        jdbc.update("UPDATE kras.sync_dataset SET service_code=? WHERE dataset_code='land_change'", SVC);
    }

    private KrasNextgenSettingsService.Pnu cfg(int parallelism, int circuitBreak, List<String> noDataCodes) {
        return new KrasNextgenSettingsService.Pnu(DATASETS, parallelism, 0, 0, "", circuitBreak, 90, 1000, "06-01",
                noDataCodes);
    }

    @Test
    void fullLoadQueueCollectsPromotesRetriesAndEnqueuesBuildingChildren() throws Exception {
        baseline(pnu(1), pnu(2), pnu(3));
        assertThat(queue.enqueueFull(jdbc, org, DATASETS, 90)).isEqualTo(6);
        assertThat(queue.enqueueFull(jdbc, org, DATASETS, 90)).isZero();   // 다시 눌러도 중복되지 않는다

        when(api.query(eq("KRAS000101"), anyString(), any())).thenAnswer(inv -> landBldgCheck(inv.getArgument(1)));
        when(api.query(eq("KRAS000102"), eq(pnu(1)), any())).thenReturn(dongList(pnu(1)));
        when(api.query(eq("KRAS000102"), eq(pnu(2)), any())).thenReturn(errorCode("0001"));   // 자료 없음
        when(api.query(eq("KRAS000102"), eq(pnu(3)), any())).thenThrow(new RuntimeException("Connect timed out"));

        KrasPnuQueueWorker.Result r = worker.drain(jdbc, org, cfg(2, 20, List.of("0001")), "NOW", "MANUAL");

        assertThat(r.processed()).isEqualTo(6);
        assertThat(r.done()).isEqualTo(5);
        assertThat(r.retried()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM kras.land_presence p JOIN kras.parcel c USING (pnu) WHERE c.org_cd=?"))
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM kras.building_register WHERE org_cd=?")).isEqualTo(3);
        Map<String, Object> retry = work("bldg_dong_info", pnu(3));
        assertThat(retry).containsEntry("status", "RETRY").containsEntry("attempt_count", 1);
        assertThat((String) retry.get("last_error")).contains("Connect timed out");
        assertThat((Boolean) jdbc.queryForObject("""
            SELECT next_attempt_at BETWEEN now() + interval '55 minutes' AND now() + interval '65 minutes'
            FROM kras.pnu_work WHERE org_cd=? AND dataset_code='bldg_dong_info' AND pnu=?""", Boolean.class, org, pnu(3)))
                .isTrue();
        assertThat(work("bldg_dong_info", pnu(2))).containsEntry("status", "DONE");   // 자료 없음 = 완료(0건)

        // 건물 종류 1=총괄(없음) 2=일반(014) 3=집합(015 + 103)
        assertThat(queue.enqueueChildren(jdbc, org, "bldg_dong_info", pnu(1), KrasPnuQueueService.ALL_DATASETS))
                .isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT dataset_code||':'||extra_key FROM kras.pnu_work WHERE org_cd=? "
                + "AND reason='CHILD'", String.class, org))
                .containsExactlyInAnyOrder("bldg_ho_info:B3", "bldg_hds_info:B2", "cbldg_hds_info:B3");

        // 재시도 시각 전에는 꺼내지 않는다 → 시각이 지나면 같은 행을 다시 처리해 완료한다.
        assertThat(worker.drain(jdbc, org, cfg(2, 20, List.of()), "NOW", "MANUAL").processed()).isZero();
        jdbc.update("UPDATE kras.pnu_work SET next_attempt_at=now() WHERE org_cd=? AND status='RETRY'", org);
        reset(api);
        when(api.query(eq("KRAS000102"), eq(pnu(3)), any())).thenReturn(dongList(pnu(3)));
        assertThat(worker.drain(jdbc, org, cfg(1, 20, List.of()), "NOW", "MANUAL").done()).isEqualTo(1);
        assertThat(work("bldg_dong_info", pnu(3))).containsEntry("status", "DONE").containsEntry("attempt_count", 0);
        assertThat(queue.fullBacklog(jdbc, org)).isZero();
    }

    @Test
    void interruptedRowsAreResumedAndCollectedRowsArePromotedWithoutCallingAgain() throws Exception {
        baseline(pnu(1), pnu(2));
        queue.enqueueFull(jdbc, org, List.of("land_bldg_check"), 90);

        // 1) 꺼낸 뒤 서버가 죽음 → lease가 남아 있는 동안은 아무도 안 가져가고, 지나면 다시 처리한다.
        KrasPnuQueueService.Work leased = queue.claim(jdbc, org, List.of("land_bldg_check"));
        assertThat(leased).isNotNull();
        // 2) 다른 행은 수집까지만 커밋되고 반영 전에 끊김.
        String other = leased.pnu().equals(pnu(1)) ? pnu(2) : pnu(1);
        when(api.query(eq("KRAS000101"), anyString(), any())).thenAnswer(inv -> landBldgCheck(inv.getArgument(1)));
        KrasPnuIngestService.IngestResult collected = ingest.ingest(jdbc, org, mappers.get(0), other, Map.of(), "IT");
        KrasPnuQueueService.Work otherWork = queue.claim(jdbc, org, List.of("land_bldg_check"));
        queue.markCollected(jdbc, org, otherWork, collected.itemId());
        jdbc.update("UPDATE kras.pnu_work SET lease_until=now() - interval '1 minute' WHERE org_cd=? AND pnu=?",
                org, other);
        assertThat(queue.summary(jdbc, org)).containsEntry("interrupted", 1L).containsEntry("running", 1L);

        clearInvocations(api);
        jdbc.update("UPDATE kras.pnu_work SET lease_until=now() - interval '1 minute' WHERE org_cd=?", org);
        KrasPnuQueueWorker.Result r = worker.drain(jdbc, org,
                new KrasNextgenSettingsService.Pnu(List.of("land_bldg_check"), 1, 0, 0, "", 20, 90, 0, "06-01", List.of()),
                "NOW", "MANUAL");

        assertThat(r.done()).isEqualTo(2);
        verify(api, times(1)).query(eq("KRAS000101"), eq(leased.pnu()), any());   // 반영만 한 행은 다시 부르지 않음
        verify(api, never()).query(eq("KRAS000101"), eq(other), any());
        assertThat(work("land_bldg_check", other)).containsEntry("status", "DONE")
                .containsEntry("last_item_id", collected.itemId());
        assertThat(count("SELECT count(*) FROM kras.land_presence p JOIN kras.parcel c USING (pnu) WHERE c.org_cd=?"))
                .isEqualTo(2);
    }

    @Test
    void repeatedGatewayFailuresStopTheRunThenRowsFailAfterRetriesAndCanBeRetried() throws Exception {
        baseline(pnu(1), pnu(2), pnu(3));
        queue.enqueueFull(jdbc, org, List.of("land_bldg_check"), 90);
        when(api.query(anyString(), anyString(), any())).thenThrow(new RuntimeException("Connect timed out"));
        var cfg = new KrasNextgenSettingsService.Pnu(List.of("land_bldg_check"), 1, 0, 0, "", 2, 90, 0, "06-01", List.of());

        KrasPnuQueueWorker.Result r = worker.drain(jdbc, org, cfg, "NOW", "MANUAL");
        assertThat(r.processed()).isEqualTo(2);
        assertThat(r.stopReason()).contains("연속 2회");
        assertThat(count("SELECT count(*) FROM kras.pnu_work WHERE org_cd=? AND status='PENDING'")).isEqualTo(1);

        // 재시도 3번을 다 쓴 행은 FAILED → 재시도 버튼으로 다시 대기.
        jdbc.update("UPDATE kras.pnu_work SET attempt_count=3, next_attempt_at=now() WHERE org_cd=?", org);
        worker.drain(jdbc, org, new KrasNextgenSettingsService.Pnu(List.of("land_bldg_check"), 1, 0, 0, "", 20, 90, 0,
                "06-01", List.of()), "NOW", "MANUAL");
        assertThat(count("SELECT count(*) FROM kras.pnu_work WHERE org_cd=? AND status='FAILED'")).isEqualTo(3);
        assertThat(queue.rows(jdbc, org, "FAILED", pnu(1).substring(10))).singleElement()
                .satisfies(row -> assertThat((String) row.get("last_error")).contains("Connect timed out"));
        // 상세 팝업: 대기열 칸 + 그 필지 수집 이력(같은 scope_key 식으로 찾는다).
        assertThat(queue.detail(jdbc, org, "land_bldg_check", pnu(1), ""))
                .containsEntry("status", "FAILED").containsEntry("attempt_count", 4).containsEntry("running", false)
                .containsEntry("reason", "FULL").containsKey("items").doesNotContainKey("scope_key")
                .satisfies(d -> assertThat((String) d.get("last_error")).contains("Connect timed out"));
        assertThat(queue.detail(jdbc, org, "land_bldg_check", pnu(1), "no-such")).isNull();
        assertThat(queue.retryFailed(jdbc, org, List.of("land_bldg_check|" + pnu(1) + "|"))).isEqualTo(1);
        assertThat(work("land_bldg_check", pnu(1))).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
        assertThat(queue.retryFailed(jdbc, org, List.of())).isEqualTo(2);
    }

    /** land_info·land_bldg_check는 둘 다 kras.parcel을 갱신한다 — 동시 8개여도 순서 역전으로 막히면 안 된다. */
    @Test
    void parcelWritingServicesDoNotOverwriteEachOtherOutOfOrderUnderParallelism() throws Exception {
        String[] pnus = new String[20];
        for (int i = 0; i < pnus.length; i++) pnus[i] = pnu(i + 1);
        baseline(pnus);
        queue.enqueueFull(jdbc, org, List.of("land_bldg_check", "land_info"), 90);
        String template = new String(getClass().getResourceAsStream("/mock-gateway/KRAS000002.xml").readAllBytes(),
                StandardCharsets.UTF_8);
        when(api.query(eq("KRAS000101"), anyString(), any())).thenAnswer(inv -> landBldgCheck(inv.getArgument(1)));
        when(api.query(eq("KRAS000002"), anyString(), any())).thenAnswer(inv -> {
            String p = inv.getArgument(1);
            return template.replace("{{PNU}}", p).replace("{{ADM}}", p.substring(0, 5)).replace("{{LOC}}", p.substring(5, 10))
                    .replace("{{GBN}}", p.substring(10, 11)).replace("{{BOBN}}", p.substring(11, 15))
                    .replace("{{BUBN}}", p.substring(15)).getBytes(StandardCharsets.UTF_8);
        });

        KrasPnuQueueWorker.Result r = worker.drain(jdbc, org, new KrasNextgenSettingsService.Pnu(
                List.of("land_bldg_check", "land_info"), 8, 0, 0, "", 20, 90, 0, "06-01", List.of()), "NOW", "MANUAL");

        assertThat(r.failed()).as(r.message()).isZero();
        assertThat(r.done()).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM kras.pnu_work WHERE org_cd=? AND status='DONE'")).isEqualTo(40);
    }

    @Test
    void sameParcelCanBeCollectedAgainOnTheSameDay() throws Exception {
        baseline(pnu(1));
        when(api.query(eq("KRAS000101"), anyString(), any())).thenAnswer(inv -> landBldgCheck(inv.getArgument(1)));
        var first = ingest.ingest(jdbc, org, mappers.get(0), pnu(1), Map.of(), "IT", "DAILY");
        ingest.promote(jdbc, org, mappers.get(0), first.itemId());
        var second = ingest.ingest(jdbc, org, mappers.get(0), pnu(1), Map.of(), "IT", "DAILY");   // 재시도·재조회
        ingest.promote(jdbc, org, mappers.get(0), second.itemId());

        assertThat(jdbc.queryForList("SELECT attempt_no FROM kras.sync_item WHERE org_cd=? AND dataset_code="
                + "'land_bldg_check' ORDER BY item_id", Integer.class, org)).containsExactly(1, 2);
    }

    @Test
    void dataErrorsFailAtOnceWithoutRetryAndDoNotTripTheGatewayCircuitBreaker() throws Exception {
        baseline(pnu(1), pnu(2), pnu(3));
        queue.enqueueFull(jdbc, org, List.of("land_bldg_check"), 90);
        // 응답 필지가 요청과 다름 → 매퍼가 거부(다시 불러도 같은 결과)
        when(api.query(anyString(), anyString(), any())).thenReturn(landBldgCheck(pnu(9)));
        var cfg = new KrasNextgenSettingsService.Pnu(List.of("land_bldg_check"), 1, 0, 0, "", 2, 90, 0, "06-01", List.of());

        KrasPnuQueueWorker.Result r = worker.drain(jdbc, org, cfg, "NOW", "MANUAL");

        assertThat(r.processed()).isEqualTo(3);   // 연속 실패 차단(2회)에 걸리지 않고 끝까지 간다
        assertThat(r.failed()).isEqualTo(3);
        assertThat(r.stopReason()).doesNotContain("연속");
        assertThat(work("land_bldg_check", pnu(1))).containsEntry("status", "FAILED");
        assertThat((String) work("land_bldg_check", pnu(1)).get("last_error")).startsWith("처리 오류:");
    }

    @Test
    void gatewayErrorCodeIsNotStoredAsAnEmptyResponse() throws Exception {
        baseline(pnu(1));
        queue.enqueueFull(jdbc, org, List.of("land_bldg_check"), 90);
        when(api.query(anyString(), anyString(), any())).thenReturn(errorCode("9999"));

        worker.drain(jdbc, org, cfg(1, 20, List.of("0001")), "NOW", "MANUAL");

        assertThat(work("land_bldg_check", pnu(1))).containsEntry("status", "RETRY");
        assertThat((String) work("land_bldg_check", pnu(1)).get("last_error")).contains("응답 코드 9999");
        assertThat(count("SELECT count(*) FROM kras.sync_item WHERE org_cd=? AND dataset_code='land_bldg_check'"))
                .isZero();   // 수집 트랜잭션이 롤백돼 아무것도 남지 않는다
    }

    @Test
    void changedParcelsRotationAndAnnualPricesAreQueued() throws Exception {
        String a = pnu(1), b = pnu(2), c = pnu(50);
        baseline(a, b);
        OffsetDateTime since = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
        when(api.query(eq(SVC), anyString(), any())).thenReturn(landChange(a, c));
        assertThat(sync.runDaily(jdbc, org, LocalDate.now().plusDays(1))).last().asString().contains("반영 성공");

        // a는 분할로 말소, c가 새로 생김 → c만 토지 계열로 들어간다.
        assertThat(queue.enqueueChanged(jdbc, org, KrasPnuQueueService.DEFAULT_DATASETS, since))
                .isEqualTo(KrasPnuQueueService.CHANGE_DATASETS.size());
        assertThat(jdbc.queryForList("SELECT DISTINCT pnu FROM kras.pnu_work WHERE org_cd=? AND reason='CHANGE'",
                String.class, org)).containsExactly(c);

        // 순환 재조회: FULL 잔량이 있으면 넣지 않고, 다 끝난 뒤 오래된 행만.
        queue.enqueueFull(jdbc, org, List.of("bldg_dong_info"), 90);
        jdbc.update("UPDATE kras.pnu_work SET status='DONE', last_success_at=now() - interval '100 days' "
                + "WHERE org_cd=? AND pnu=? AND dataset_code='bldg_dong_info'", org, b);
        assertThat(queue.enqueueRotation(jdbc, org, List.of("bldg_dong_info"), 90, 10)).isZero();
        jdbc.update("UPDATE kras.pnu_work SET status='DONE', last_success_at=now() WHERE org_cd=? AND reason='FULL' "
                + "AND pnu<>?", org, b);
        assertThat(queue.enqueueRotation(jdbc, org, List.of("bldg_dong_info"), 90, 10)).isEqualTo(1);
        assertThat(work("bldg_dong_info", b)).containsEntry("reason", "ROTATE").containsEntry("status", "PENDING");

        // 연 1회 가격: 기준일 전에는 0, 지나면 그 뒤로 안 받은 가격 행만.
        jdbc.update("INSERT INTO kras.pnu_work(org_cd, dataset_code, pnu, reason, status, last_success_at) "
                + "VALUES (?, 'land_jiga', ?, 'FULL', 'DONE', '2020-01-01')", org, b);
        int year = LocalDate.now().getYear();
        assertThat(queue.enqueueAnnual(jdbc, org, List.of("land_jiga"), "06-01", LocalDate.of(year, 5, 31))).isZero();
        assertThat(queue.enqueueAnnual(jdbc, org, List.of("land_jiga"), "06-01", LocalDate.of(year, 6, 2))).isEqualTo(1);
        assertThat(work("land_jiga", b)).containsEntry("reason", "ANNUAL");
    }

    // ── helpers ───────────────────────────────────────────────────────

    private void baseline(String... pnus) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String p : pnus) {
            sb.append(String.join(String.valueOf(VT), p.substring(0, 5), p.substring(5, 10), p.substring(10, 11),
                    p.substring(11, 15), p.substring(15), "01", "100", "1")).append('\n');
        }
        when(api.downloadLandTxt()).thenReturn(sb.toString().getBytes(StandardCharsets.US_ASCII));
        assertThat(sync.runMonthlyCorrection(jdbc, org)).last().asString().contains("반영 성공");
    }

    private Map<String, Object> work(String dataset, String pnu) {
        return jdbc.queryForMap("SELECT * FROM kras.pnu_work WHERE org_cd=? AND dataset_code=? AND pnu=? "
                + "AND extra_key=''", org, dataset, pnu);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, org);
    }

    private String pnu(int bobn) {
        return org + "25021" + "1" + "%04d".formatted(bobn) + "0000";
    }

    private static byte[] landBldgCheck(String pnu) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><RESPONSE><HEADER><CODE>0000</CODE><MESSAGE>SUCCESS</MESSAGE>"
                + "</HEADER><BODY><LAND_BLDG_CHECK><REAL_GBN>토지(건물)</REAL_GBN>"
                + "<ADM_SECT_CD>" + pnu.substring(0, 5) + "</ADM_SECT_CD><LAND_LOC_CD>" + pnu.substring(5, 10)
                + "</LAND_LOC_CD><LEDG_GBN>" + pnu.substring(10, 11) + "</LEDG_GBN><BOBN>" + pnu.substring(11, 15)
                + "</BOBN><BUBN>" + pnu.substring(15) + "</BUBN><ADM_SECT_NM>시험군</ADM_SECT_NM>"
                + "<SECT_LOC_NM>시험리</SECT_LOC_NM><MAP_GBN>1</MAP_GBN></LAND_BLDG_CHECK></BODY></RESPONSE>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 건물 3동: 1 총괄, 2 일반, 3 집합 — 건물번호 B1~B3. */
    private static byte[] dongList(String pnu) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><RESPONSE><HEADER>"
                + "<CODE>0000</CODE><MESSAGE>SUCCESS</MESSAGE></HEADER><BODY><BLDG_DONG_LIST>");
        String[] kinds = {"총괄", "일반", "집합"};
        for (int k = 1; k <= 3; k++) {
            sb.append("<BLDG_DONG_INFO><ADM_SECT_CD>").append(pnu, 0, 5).append("</ADM_SECT_CD><LAND_LOC_CD>")
                    .append(pnu, 5, 10).append("</LAND_LOC_CD><LEDG_GBN>1</LEDG_GBN><BOBN>").append(pnu, 11, 15)
                    .append("</BOBN><BUBN>0000</BUBN><BLDG_KIND_NM>").append(kinds[k - 1])
                    .append("</BLDG_KIND_NM><BLDG_KIND_CD>").append(k).append("</BLDG_KIND_CD><BLDG_GBN_NO>B")
                    .append(k).append("</BLDG_GBN_NO><BLDG_NM>시험동</BLDG_NM><DONG_NM>").append(k)
                    .append("동</DONG_NM><BMAP_YN>N</BMAP_YN><GAREA>100.5</GAREA></BLDG_DONG_INFO>");
        }
        return sb.append("</BLDG_DONG_LIST></BODY></RESPONSE>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] errorCode(String code) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><RESPONSE><HEADER><CODE>" + code
                + "</CODE><MESSAGE>ERROR</MESSAGE></HEADER><BODY/></RESPONSE>").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] landChange(String bf, String af) {
        String day = LocalDate.now().minusDays(1).toString().replace("-", "");
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><LAND_MOV_CHG_SET><LAND_MOV_CHG>"
                + "<LAND_MOV_NO>IT" + org + "-Q1</LAND_MOV_NO><LAND_MOV_NM>분할</LAND_MOV_NM><LAND_MOV_ITEM>1</LAND_MOV_ITEM>"
                + "<BF_LAND_LOC_CD>" + bf.substring(5, 10) + "</BF_LAND_LOC_CD><BF_LEDG_GBN>1</BF_LEDG_GBN>"
                + "<BF_BOBN>" + bf.substring(11, 15) + "</BF_BOBN><BF_BUBN>0000</BF_BUBN><BF_JIMOK>01</BF_JIMOK>"
                + "<BF_PAREA>100</BF_PAREA><AF_LAND_LOC_CD>" + af.substring(5, 10) + "</AF_LAND_LOC_CD>"
                + "<AF_LEDG_GBN>1</AF_LEDG_GBN><AF_BOBN>" + af.substring(11, 15) + "</AF_BOBN><AF_BUBN>0000</AF_BUBN>"
                + "<AF_JIMOK>02</AF_JIMOK><AF_PAREA>50</AF_PAREA><LAND_MOV_RSN_CD>20</LAND_MOV_RSN_CD>"
                + "<LAND_MOV_RSN_NM>분할</LAND_MOV_RSN_NM><ADJ_YMD>" + day + "</ADJ_YMD><HNDL_YMD>" + day
                + "</HNDL_YMD></LAND_MOV_CHG></LAND_MOV_CHG_SET>").getBytes(StandardCharsets.UTF_8);
    }
}
