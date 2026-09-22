package geosync.kras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 검증(VERIFIED) 전환 시 사람이 확인한 근거(응답 샘플·담당자·매퍼 버전)를 별도 테이블에 남긴다.
 * kras.sync_dataset(운영 스키마)은 건드리지 않는다 — kras.ui_operation_log와 같은 선례.
 */
@Service
public class KrasVerificationEvidenceService {
    private static final Logger log = LoggerFactory.getLogger(KrasVerificationEvidenceService.class);

    /** kras.md에 개인정보로 확인된 XML 태그 — 새 필드가 추가되면 여기 한 줄만 추가한다. */
    private static final Set<String> PII_TAGS = Set.of("OWNER_NM", "OWNER_ADDR");
    private static final Pattern PII_TAG_PATTERN = Pattern.compile(
            "(?i)<(" + String.join("|", PII_TAGS) + ")>([^<]*)</\\1>");
    /** 주민등록번호 형식 안전망 — 태그 목록에 없는 필드에 값이 그대로 남는 경우를 잡는다. */
    private static final Pattern RRN_PATTERN = Pattern.compile("\\b\\d{6}-?\\d{7}\\b");

    private static final int MAX_SAMPLE_LEN = 4000;

    public void save(JdbcTemplate jdbc, String orgCd, String datasetCode, String verifiedBy,
                      String mapperVersion, String responseSample, String note) {
        ensureTable(jdbc);
        String masked = mask(responseSample);
        if (masked != null && masked.length() > MAX_SAMPLE_LEN) masked = masked.substring(0, MAX_SAMPLE_LEN);
        jdbc.update("""
            INSERT INTO kras.ui_verification_evidence
                (org_cd, dataset_code, verified_by, mapper_version, response_sample, note)
            VALUES (?, ?, ?, ?, ?, ?)
            """, orgCd, datasetCode, blankToNull(verifiedBy), blankToNull(mapperVersion), masked, blankToNull(note));
    }

    /** 목록/상세 화면의 "마지막 검증: {일시}·{담당자}" 배지용. */
    public Map<String, Map<String, Object>> latestByDataset(JdbcTemplate jdbc, String orgCd, List<String> datasetCodes) {
        if (!tableExists(jdbc) || datasetCodes.isEmpty()) return Map.of();
        String placeholders = String.join(",", datasetCodes.stream().map(c -> "?").toList());
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT DISTINCT ON (dataset_code) dataset_code, verified_at, verified_by, mapper_version
            FROM kras.ui_verification_evidence
            WHERE org_cd=? AND dataset_code IN (""" + placeholders + """
            )
            ORDER BY dataset_code, verified_at DESC
            """, prependArg(orgCd, datasetCodes));
        Map<String, Map<String, Object>> byDataset = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) byDataset.put((String) row.get("dataset_code"), row);
        return byDataset;
    }

    static String mask(String sample) {
        if (sample == null || sample.isBlank()) return sample;
        Matcher tagMatcher = PII_TAG_PATTERN.matcher(sample);
        String masked = tagMatcher.replaceAll(mr -> "<" + mr.group(1) + ">***</" + mr.group(1) + ">");
        return RRN_PATTERN.matcher(masked).replaceAll("[MASKED]");
    }

    private static Object[] prependArg(String orgCd, List<String> rest) {
        Object[] args = new Object[rest.size() + 1];
        args[0] = orgCd;
        for (int i = 0; i < rest.size(); i++) args[i + 1] = rest.get(i);
        return args;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private boolean tableExists(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT to_regclass('kras.ui_verification_evidence')::text", String.class) != null;
    }

    private void ensureTable(JdbcTemplate jdbc) {
        if (!tableExists(jdbc)) {
            jdbc.execute("""
            CREATE TABLE IF NOT EXISTS kras.ui_verification_evidence (
                evidence_id bigserial PRIMARY KEY,
                org_cd varchar(5) NOT NULL,
                dataset_code varchar(80) NOT NULL,
                verified_at timestamptz NOT NULL DEFAULT now(),
                verified_by varchar(100),
                mapper_version varchar(100),
                response_sample text,
                note text
            )
            """);
            jdbc.execute("""
            CREATE INDEX IF NOT EXISTS ix_ui_verification_evidence_org_dataset
                ON kras.ui_verification_evidence(org_cd, dataset_code, verified_at DESC)
            """);
        }
    }
}
