package geosync.kras;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XML 매퍼 공용 필드 파싱 — 날짜/숫자 파싱 실패 시 stage 컬럼은 NULL로 두고 원문을
 * extra_attributes에 보존한다(잘못된 값을 조용히 NULL로 뭉개지 않는다는 원칙, 설계 §6.2/§3.2).
 * land_info에서 먼저 쓰던 걸 shr_ymb에서도 그대로 필요해져 공용으로 뺐다.
 */
final class KrasFieldParsers {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** YYYYMMDD/YYYY-MM-DD만 정상 날짜로 인정한다. */
    static void putDate(Map<String, Object> row, String col, String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) { row.put(col, null); return; }
        String normalized = raw.length() == 10 ? raw.replace("-", "") : raw;
        try {
            row.put(col, LocalDate.parse(normalized, YYYYMMDD));
        } catch (DateTimeParseException e) {
            row.put(col, null);
            markUnparsed(row, col, raw, warnings);
        }
    }

    static void putDecimal(Map<String, Object> row, String col, String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) { row.put(col, null); return; }
        try {
            row.put(col, new BigDecimal(raw.trim()));
        } catch (NumberFormatException e) {
            row.put(col, null);
            markUnparsed(row, col, raw, warnings);
        }
    }

    static void putInt(Map<String, Object> row, String col, String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) { row.put(col, null); return; }
        try {
            row.put(col, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            row.put(col, null);
            markUnparsed(row, col, raw, warnings);
        }
    }

    @SuppressWarnings("unchecked")
    private static void markUnparsed(Map<String, Object> row, String col, String raw, List<String> warnings) {
        Map<String, Object> extra = (Map<String, Object>) row.computeIfAbsent(
                "extra_attributes", k -> new LinkedHashMap<String, Object>());
        extra.put(col + "_raw", raw);
        warnings.add(col + " 파싱 실패, 원문 보존: " + raw);
    }

    private KrasFieldParsers() {}
}
