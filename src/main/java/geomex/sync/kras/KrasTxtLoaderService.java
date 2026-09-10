package geomex.sync.kras;

import geomex.sync.settings.RuntimeSettingsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * KRAS TXT 파일 DB 적재 서비스 (Phase 5).
 *
 * KRAS000039 공시지가 TXT → sync_public_tables.sql의 anvm_jiga 테이블
 * KRAS000040 토지대장 TXT → sync_public_tables.sql의 land_frst_ledg 테이블
 *
 * TXT 포맷: EUC-KR, 파이프('|') 구분. 헤더 행 없음.
 * KRAS000039 컬럼 순서: land_cd | base_year | jiga | base_mon | pyo_yn
 * KRAS000040 컬럼 순서: adm_sec_cd | land_loc_cd | ledg_gbn | bobn | bubn | jimok | parea | owngbn
 */
@Service
public class KrasTxtLoaderService {

    private static final Logger log = LoggerFactory.getLogger(KrasTxtLoaderService.class);

    private static final Charset EUC_KR;
    static {
        Charset cs;
        try { cs = Charset.forName("EUC-KR"); } catch (Exception e) { cs = StandardCharsets.UTF_8; }
        EUC_KR = cs;
    }
    private static final int BATCH_SIZE = 2000;

    private final KrasApiClient krasApiClient;
    private final JdbcTemplate jdbc;
    private final RuntimeSettingsService settings;

    public KrasTxtLoaderService(KrasApiClient krasApiClient, JdbcTemplate jdbc,
                                 RuntimeSettingsService settings) {
        this.krasApiClient = krasApiClient;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    // ── 공시지가 (KRAS000039 → anvm_jiga) ──────────────────────────────

    /** API에서 직접 다운로드 후 적재 */
    @Transactional
    public int loadJigaTxt() throws Exception {
        byte[] data = krasApiClient.downloadJigaTxt();
        return loadJigaFromBytes(data);
    }

    /** 기존 파일에서 적재 */
    @Transactional
    public int loadJigaFromFile(Path filePath) throws Exception {
        byte[] data = Files.readAllBytes(filePath);
        return loadJigaFromBytes(data);
    }

    public int loadJigaFromBytes(byte[] data) throws Exception {
        String orgCode = settings.orgCode();
        String schema = settings.odsSchema();
        String table = "\"" + schema + "\".\"anvm_jiga\"";

        List<String[]> rows = parseTxt(data, 5);

        jdbc.update("DELETE FROM " + table + " WHERE org_cd = ?", orgCode);

        String sql = "INSERT INTO " + table
                + " (land_cd, base_year, jiga, base_mon, pyo_yn, org_cd)"
                + " VALUES (?,?,?,?,?,?)";
        List<Object[]> batch = new ArrayList<>();
        for (String[] cols : rows) {
            batch.add(new Object[]{
                    safeGet(cols, 0),          // land_cd
                    safeGet(cols, 1),          // base_year
                    toDecimal(safeGet(cols, 2)), // jiga
                    safeGet(cols, 3),          // base_mon
                    safeGet(cols, 4),          // pyo_yn
                    orgCode
            });
            if (batch.size() >= BATCH_SIZE) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);

        log.info("[KrasTxt] 공시지가 {} rows → {}", rows.size(), table);
        return rows.size();
    }

    // ── 토지대장 (KRAS000040 → land_frst_ledg) ─────────────────────────

    /** API에서 직접 다운로드 후 적재 */
    @Transactional
    public int loadLandTxt() throws Exception {
        byte[] data = krasApiClient.downloadLandTxt();
        return loadLandFromBytes(data);
    }

    /** 기존 파일에서 적재 */
    @Transactional
    public int loadLandFromFile(Path filePath) throws Exception {
        byte[] data = Files.readAllBytes(filePath);
        return loadLandFromBytes(data);
    }

    public int loadLandFromBytes(byte[] data) throws Exception {
        String orgCode = settings.orgCode();
        String schema = settings.odsSchema();
        String table = "\"" + schema + "\".\"land_frst_ledg\"";

        List<String[]> rows = parseTxt(data, 8);

        jdbc.update("DELETE FROM " + table + " WHERE org_cd = ?", orgCode);

        String sql = "INSERT INTO " + table
                + " (adm_sec_cd, land_loc_cd, ledg_gbn, bobn, bubn, jimok, parea, owngbn, org_cd)"
                + " VALUES (?,?,?,?,?,?,?,?,?)";
        List<Object[]> batch = new ArrayList<>();
        for (String[] cols : rows) {
            batch.add(new Object[]{
                    safeGet(cols, 0), // adm_sec_cd
                    safeGet(cols, 1), // land_loc_cd
                    safeGet(cols, 2), // ledg_gbn
                    safeGet(cols, 3), // bobn
                    safeGet(cols, 4), // bubn
                    safeGet(cols, 5), // jimok
                    toDecimal(safeGet(cols, 6)), // parea
                    safeGet(cols, 7), // owngbn
                    orgCode
            });
            if (batch.size() >= BATCH_SIZE) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);

        log.info("[KrasTxt] 토지대장 {} rows → {}", rows.size(), table);
        return rows.size();
    }

    // ── 공통 파싱 ────────────────────────────────────────────────────────

    private List<String[]> parseTxt(byte[] data, int minCols) {
        String text;
        try {
            text = new String(data, EUC_KR);
        } catch (Exception e) {
            text = new String(data, StandardCharsets.UTF_8);
        }

        String delimiter = detectDelimiter(text);
        List<String[]> result = new ArrayList<>();

        for (String line : text.split("\r?\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split(delimiter, -1);
            // 첫 행이 숫자로 시작하지 않으면 헤더로 간주하고 스킵
            if (result.isEmpty() && parts.length > 0 && !startsWithDigit(parts[0].trim())) {
                continue;
            }
            if (parts.length < minCols) continue;
            result.add(parts);
        }
        return result;
    }

    private String detectDelimiter(String text) {
        int firstNewline = text.indexOf('\n');
        String firstLine = firstNewline > 0 ? text.substring(0, firstNewline) : text;
        long pipes = firstLine.chars().filter(c -> c == '|').count();
        long tabs  = firstLine.chars().filter(c -> c == '\t').count();
        long commas = firstLine.chars().filter(c -> c == ',').count();
        if (pipes >= tabs && pipes >= commas) return "\\|";
        if (tabs  >= commas) return "\t";
        return ",";
    }

    private static boolean startsWithDigit(String s) {
        return !s.isEmpty() && Character.isDigit(s.charAt(0));
    }

    private static String safeGet(String[] cols, int idx) {
        if (idx >= cols.length) return null;
        String v = cols[idx].trim();
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal toDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
