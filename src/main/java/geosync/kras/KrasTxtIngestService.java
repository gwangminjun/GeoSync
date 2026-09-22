package geosync.kras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * kras 스키마 전체 TXT 파일(FULL 수집모드) 적재 — land_basic_file(KRAS000040),
 * land_price_file(KRAS000039).
 *
 * 기존 KrasTxtLoaderService(ods.anvm_jiga / ods.land_frst_ledg 적재)는 건드리지 않는다 —
 * 같은 원본 파일을 별도로 한 번 더 읽어 kras 스키마에만 독립적으로 적재한다(설계 §3-5).
 *
 * 두 데이터셋의 모양이 다르다:
 * - land_basic_file → kras.stage_parcel + kras.stage_land_basic → 승격(패턴 A×2). 수집/승격 2단계.
 * - land_price_file → kras.land_price_file_row 직행. 이 테이블은 업무 테이블이 아니라
 *   spatial_feature와 같은 원본 보존 테이블(guard_item_content가 SUCCESS 후 불변을 강제)이고
 *   대응하는 업무 테이블이 business_dataset에 없다 — 그래서 승격 단계 자체가 없다.
 *
 * 구분자: kras.md §16과 DDL 주석(sync_file.delimiter_code=11) 모두 ASCII 11을 정본으로 본다.
 * 기존 KrasTxtLoaderService의 detectDelimiter는 파이프/탭/콤마만 보고 ASCII 11을 모르므로
 * 그대로 재사용하지 않고, ASCII 11을 먼저 보고 없으면 같은 순서로 내려가게 새로 썼다.
 */
@Service
public class KrasTxtIngestService {

    private static final Logger log = LoggerFactory.getLogger(KrasTxtIngestService.class);

    private static final String LAND_BASIC_DATASET = "land_basic_file";
    private static final String LAND_PRICE_DATASET = "land_price_file";
    private static final int BATCH_SIZE = 2000;
    /** 경고를 전부 모으면 수십만 줄짜리 파일에서 메시지가 감당이 안 된다 — 앞부분만 남기고 건수만 센다. */
    private static final int MAX_WARNINGS = 20;

    private static final Charset EUC_KR;
    static {
        Charset cs;
        try { cs = Charset.forName("EUC-KR"); } catch (Exception e) { cs = StandardCharsets.UTF_8; }
        EUC_KR = cs;
    }

    private final KrasApiClient krasApiClient;
    private final KrasStagePromotionService promotionService;

    public record IngestResult(long runId, long itemId, int rowCount, boolean promotable, List<String> warnings) {}
    public record PromoteResult(long itemId) {}

    public KrasTxtIngestService(KrasApiClient krasApiClient, KrasStagePromotionService promotionService) {
        this.krasApiClient = krasApiClient;
        this.promotionService = promotionService;
    }

    // ── land_basic_file (KRAS000040) ──────────────────────────────────

    /**
     * kras.md §16 결과 파일 구조:
     * ADM_SECT_CD♂LAND_LOC_CD♂LEDG_GBN♂BOBN♂BUBN♂JIMOK♂PAREA♂OWN_GBN (♂ = ASCII 11)
     * 앞 5개를 이어붙이면 19자리 PNU다 — 기존 KrasTxtLoaderService의 8컬럼 순서와도 일치한다.
     */
    public IngestResult ingestLandBasic(JdbcTemplate targetJdbc, String orgCd, String triggeredBy) throws Exception {
        byte[] data = krasApiClient.downloadLandTxt();
        List<String[]> rows = parseTxt(data, 8);
        if (rows.isEmpty()) {
            throw new IllegalStateException("토지기본정보 TXT에서 읽은 행이 0건입니다 — 적재를 중단합니다.");
        }
        return targetJdbc.execute((ConnectionCallback<IngestResult>) conn ->
                inTransaction(conn, LAND_BASIC_DATASET,
                        tx -> ingestLandBasicInTransaction(tx, orgCd, rows, triggeredBy)));
    }

    private IngestResult ingestLandBasicInTransaction(JdbcTemplate tx, String orgCd, List<String[]> rows,
                                                       String triggeredBy) {
        long[] ids = createRunAndItem(tx, orgCd, LAND_BASIC_DATASET, triggeredBy);
        long runId = ids[0];
        long itemId = ids[1];

        List<String> warnings = new ArrayList<>();
        List<Object[]> parcelBatch = new ArrayList<>();
        List<Object[]> basicBatch = new ArrayList<>();
        int rowNo = 0;
        for (String[] c : rows) {
            rowNo++;
            String admSectCd = trimToNull(c[0]);
            String landLocCd = trimToNull(c[1]);
            String ledgGbn   = trimToNull(c[2]);
            String bobn      = trimToNull(c[3]);
            String bubn      = trimToNull(c[4]);
            String pnu = admSectCd + landLocCd + ledgGbn + bobn + bubn;
            if (!pnu.matches("\\d{19}")) {
                addWarning(warnings, rowNo + "행: 필지 식별 5항목을 이어붙인 값이 19자리 숫자가 아닙니다 — " + pnu);
                continue;
            }
            if (!orgCd.equals(admSectCd)) {
                addWarning(warnings, rowNo + "행: 행정구역코드가 요청 기관(" + orgCd + ")과 다릅니다 — " + admSectCd);
                continue;
            }
            parcelBatch.add(new Object[]{ itemId, (long) rowNo, pnu, admSectCd, landLocCd, ledgGbn, bobn, bubn });
            basicBatch.add(new Object[]{ itemId, (long) rowNo, pnu, trimToNull(c[5]),
                    toDecimal(c[6], rowNo, "PAREA", warnings), trimToNull(c[7]) });
        }
        if (parcelBatch.isEmpty()) {
            throw new IllegalStateException("유효한 행이 0건입니다(전체 " + rows.size() + "행). 앞부분 경고: " + warnings);
        }

        batchInsert(tx, """
            INSERT INTO kras.stage_parcel(item_id, row_no, pnu, adm_sect_cd, land_loc_cd, ledg_gbn, bobn, bubn)
            VALUES (?,?,?,?,?,?,?,?)
            """, parcelBatch);
        batchInsert(tx, """
            INSERT INTO kras.stage_land_basic(item_id, row_no, pnu, jimok, parea, own_gbn)
            VALUES (?,?,?,?,?,?)
            """, basicBatch);

        int n = parcelBatch.size();
        boolean promotable = warnings.isEmpty();
        if (promotable) {
            markSuccess(tx, itemId, n);
        } else {
            log.warn("[KrasTxtIngest] land_basic_file 경고 {}건으로 SUCCESS 보류 (item_id={})", warnings.size(), itemId);
        }
        return new IngestResult(runId, itemId, n, promotable, warnings);
    }

    /** 승격: 부모(parcel) → 자식(land_basic) 순서. 둘 다 자연키(pnu) UPSERT — 패턴 A. */
    public PromoteResult promoteLandBasic(JdbcTemplate targetJdbc, String orgCd, long itemId) {
        return targetJdbc.execute((ConnectionCallback<PromoteResult>) conn ->
                inTransaction(conn, LAND_BASIC_DATASET, tx -> {
                    requireSuccess(tx, orgCd, itemId);
                    promotionService.promote(tx, itemId, List.of(
                        KrasStagePromotionService.StagePromotionSpec.naturalKeyUpsert(
                            "kras.stage_parcel", "kras.parcel", List.of("pnu"),
                            List.of("pnu", "adm_sect_cd", "land_loc_cd", "ledg_gbn", "bobn", "bubn")),
                        KrasStagePromotionService.StagePromotionSpec.naturalKeyUpsert(
                            "kras.stage_land_basic", "kras.land_basic", List.of("pnu"),
                            List.of("pnu", "jimok", "parea", "own_gbn"))
                    ));
                    return new PromoteResult(itemId);
                }));
    }

    // ── land_price_file (KRAS000039) ──────────────────────────────────

    /**
     * 파일 컬럼 순서는 kras.md에 없다 — 기존 KrasTxtLoaderService가 쓰고 있는 순서를 그대로 따른다:
     * land_cd, base_year, jiga, base_mon, pyo_yn (jiga가 3번째, base_mon이 4번째로 DB 컬럼 순서와 다름).
     *
     * 승격 단계가 없다 — kras.land_price_file_row는 업무 테이블이 아니라 원본 보존 테이블이고,
     * business_dataset에 대응 업무 테이블이 등록돼 있지 않다(대장 공시지가 kras.land_price는
     * land_info 데이터셋 소유라 이 파일에서 채우지 않는다).
     */
    public IngestResult ingestLandPrice(JdbcTemplate targetJdbc, String orgCd, String triggeredBy) throws Exception {
        byte[] data = krasApiClient.downloadJigaTxt();
        List<String[]> rows = parseTxt(data, 5);
        if (rows.isEmpty()) {
            throw new IllegalStateException("공시지가 TXT에서 읽은 행이 0건입니다 — 적재를 중단합니다.");
        }
        return targetJdbc.execute((ConnectionCallback<IngestResult>) conn ->
                inTransaction(conn, LAND_PRICE_DATASET,
                        tx -> ingestLandPriceInTransaction(tx, orgCd, rows, triggeredBy)));
    }

    private IngestResult ingestLandPriceInTransaction(JdbcTemplate tx, String orgCd, List<String[]> rows,
                                                       String triggeredBy) {
        long[] ids = createRunAndItem(tx, orgCd, LAND_PRICE_DATASET, triggeredBy);
        long runId = ids[0];
        long itemId = ids[1];

        List<String> warnings = new ArrayList<>();
        List<Object[]> batch = new ArrayList<>();
        int rowNo = 0;
        for (String[] c : rows) {
            rowNo++;
            String landCd = trimToNull(c[0]);
            if (landCd == null || !landCd.matches("\\d{19}")) {
                addWarning(warnings, rowNo + "행: 토지코드가 19자리 숫자가 아닙니다 — " + landCd);
                continue;
            }
            if (!landCd.startsWith(orgCd)) {
                addWarning(warnings, rowNo + "행: 토지코드 앞 5자리가 요청 기관(" + orgCd + ")과 다릅니다 — " + landCd);
                continue;
            }
            batch.add(new Object[]{ itemId, orgCd, (long) rowNo, landCd, trimToNull(c[1]), trimToNull(c[3]),
                    toDecimal(c[2], rowNo, "JIGA", warnings), trimToNull(c[4]) });
        }
        if (batch.isEmpty()) {
            throw new IllegalStateException("유효한 행이 0건입니다(전체 " + rows.size() + "행). 앞부분 경고: " + warnings);
        }

        batchInsert(tx, """
            INSERT INTO kras.land_price_file_row(item_id, org_cd, row_no, land_cd, base_year, base_mon, jiga, pyo_yn)
            VALUES (?,?,?,?,?,?,?,?)
            """, batch);

        int n = batch.size();
        boolean promotable = warnings.isEmpty();
        if (promotable) {
            markSuccess(tx, itemId, n);
        } else {
            log.warn("[KrasTxtIngest] land_price_file 경고 {}건으로 SUCCESS 보류 (item_id={})", warnings.size(), itemId);
        }
        return new IngestResult(runId, itemId, n, promotable, warnings);
    }

    // ── 공통 ──────────────────────────────────────────────────────────

    /** 네 적재 서비스가 공유하는 수동 트랜잭션 패턴(§3-4) — 대상 DB가 런타임에 바뀌어 @Transactional을 못 쓴다. */
    private <T> T inTransaction(java.sql.Connection conn, String datasetCode,
                                 java.util.function.Function<JdbcTemplate, T> work) throws java.sql.SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            T result = work.apply(tx);
            conn.commit();
            return result;
        } catch (Exception e) {
            conn.rollback();
            log.error("[KrasTxtIngest] {} 실패, 롤백: {}", datasetCode, e.getMessage(), e);
            throw new IllegalStateException(datasetCode + " 실패: " + e.getMessage(), e);
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /** @return {runId, itemId}. FULL 수집모드라 scope_key는 기관 전체를 뜻하는 'ALL'이다. */
    private long[] createRunAndItem(JdbcTemplate tx, String orgCd, String datasetCode, String triggeredBy) {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        Long runId = tx.queryForObject("""
            INSERT INTO kras.sync_run(org_cd, job_kind, triggered_by, period_start, period_end_exclusive)
            VALUES (?, 'MANUAL', ?, ?, ?)
            RETURNING run_id
            """, Long.class, orgCd, triggeredBy, today, tomorrow);
        Long itemId = tx.queryForObject("""
            INSERT INTO kras.sync_item(run_id, org_cd, dataset_code, scope_key, window_start, window_end_exclusive)
            VALUES (?, ?, ?, 'ALL', ?, ?)
            RETURNING item_id
            """, Long.class, runId, orgCd, datasetCode, today, tomorrow);
        return new long[]{ runId, itemId };
    }

    private void markSuccess(JdbcTemplate tx, long itemId, int n) {
        tx.update("""
            UPDATE kras.sync_item SET status='SUCCESS', rows_received=?, rows_valid=?, rows_rejected=0, is_complete=true
            WHERE item_id=?
            """, n, n, itemId);
    }

    private void requireSuccess(JdbcTemplate tx, String orgCd, long itemId) {
        String status = tx.queryForObject(
                "SELECT status FROM kras.sync_item WHERE item_id=? AND org_cd=? AND dataset_code=?",
                String.class, itemId, orgCd, LAND_BASIC_DATASET);
        if (!"SUCCESS".equals(status)) {
            throw new IllegalStateException("item_id=" + itemId + "은 SUCCESS 상태가 아닙니다(파싱 경고로 보류됐거나 "
                    + "잘못된 item일 수 있습니다). status=" + status);
        }
    }

    private void batchInsert(JdbcTemplate tx, String sql, List<Object[]> batch) {
        for (int i = 0; i < batch.size(); i += BATCH_SIZE) {
            tx.batchUpdate(sql, batch.subList(i, Math.min(i + BATCH_SIZE, batch.size())));
        }
    }

    /**
     * ASCII 11(kras.md §16의 ♂)을 먼저 보고, 없으면 파이프/탭/콤마 순으로 내려간다.
     * 첫 행이 숫자로 시작하지 않으면 헤더로 보고 건너뛴다(기존 로더와 동일한 판정).
     */
    private List<String[]> parseTxt(byte[] data, int expectedCols) {
        String text = new String(data, EUC_KR);
        String delimiter = detectDelimiter(text);
        List<String[]> result = new ArrayList<>();
        boolean first = true;
        for (String line : text.split("\r?\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split(delimiter, -1);
            if (first) {
                first = false;
                if (parts.length > 0 && !startsWithDigit(parts[0].trim())) continue;
            }
            if (parts.length < expectedCols) continue;
            result.add(parts);
        }
        return result;
    }

    /** kras.md §16이 ♂로 표기한 구분자 = ASCII 11(VT). 소스에 제어문자를 직접 넣으면 안 보여서 코드값으로 쓴다. */
    private static final char ASCII_VT = 11;

    private static String detectDelimiter(String text) {
        int nl = text.indexOf('\n');
        String firstLine = nl > 0 ? text.substring(0, nl) : text;
        if (firstLine.indexOf(ASCII_VT) >= 0) return String.valueOf(ASCII_VT);   // ASCII 11 — kras.md §16 정본
        if (firstLine.indexOf('|') >= 0) return "\\|";
        if (firstLine.indexOf('\t') >= 0) return "\t";
        return ",";
    }

    private static boolean startsWithDigit(String s) {
        return !s.isEmpty() && Character.isDigit(s.charAt(0));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * 파싱 실패를 조용히 NULL로 뭉개지 않는다(§4 원칙) — 경고로 남겨 item이 SUCCESS로 못 가게 한다.
     * 다른 매퍼가 쓰는 KrasFieldParsers는 행마다 Map을 만드는 구조라, 수십만 행을 batchUpdate용
     * 배열로 쌓는 이 경로에는 안 맞아 값만 돌려주는 형태로 따로 뒀다.
     */
    private static BigDecimal toDecimal(String raw, int rowNo, String field, List<String> warnings) {
        String v = trimToNull(raw);
        if (v == null) return null;
        try {
            return new BigDecimal(v.replace(",", ""));
        } catch (NumberFormatException e) {
            addWarning(warnings, rowNo + "행: " + field + " 숫자 파싱 실패 — " + v);
            return null;
        }
    }

    private static void addWarning(List<String> warnings, String message) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(message);
        } else if (warnings.size() == MAX_WARNINGS) {
            warnings.add("... (경고가 " + MAX_WARNINGS + "건을 넘어 이후는 생략)");
        }
    }
}
