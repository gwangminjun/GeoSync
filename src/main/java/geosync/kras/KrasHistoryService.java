package geosync.kras;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class KrasHistoryService {
    private final Map<String, List<String>> stageTables = new LinkedHashMap<>();
    private final Map<String, Supplier<String>> serviceCodes = new LinkedHashMap<>();
    private final Map<String, String> promotePaths = new LinkedHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private static final Set<String> FILE_DATASETS = Set.of(
            "cadastral_file", "layer_list", "usezone_file", "land_basic_file", "land_price_file");

    public KrasHistoryService(List<KrasXmlServiceMapper> mappers, List<KrasDateRangeServiceMapper> rangeMappers) {
        for (KrasXmlServiceMapper mapper : mappers) {
            register(mapper.datasetCode(), mapper.promotionSpecs(), mapper::connSvcId, "/kras-db/promote/");
        }
        for (KrasDateRangeServiceMapper mapper : rangeMappers) {
            register(mapper.datasetCode(), mapper.promotionSpecs(), mapper::connSvcId, "/kras-db/promote-range/");
        }
        stageTables.put("land_basic_file", List.of("kras.stage_parcel", "kras.stage_land_basic"));
        promotePaths.put("land_basic_file", "/kras-db/promote/land-basic-file");
    }

    private void register(String dataset, List<KrasStagePromotionService.StagePromotionSpec> specs,
                          Supplier<String> code, String prefix) {
        Set<String> tables = new LinkedHashSet<>();
        for (var spec : specs) {
            tables.add(spec.stageTable());
            if (spec.children() != null) spec.children().forEach(child -> tables.add(child.stageTable()));
        }
        tables.forEach(table -> {
            if (!table.matches("kras\\.stage_[a-z0-9_]+")) throw new IllegalArgumentException("허용되지 않은 stage 테이블");
        });
        stageTables.put(dataset, List.copyOf(tables));
        serviceCodes.put(dataset, code);
        promotePaths.put(dataset, prefix + dataset.replace('_', '-'));
    }

    public List<String> readiness(JdbcTemplate jdbc, String dataset) {
        List<String> reasons = new ArrayList<>();
        if (!stageTables.containsKey(dataset) && !FILE_DATASETS.contains(dataset)) {
            return List.of("등록되지 않은 연계입니다.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT contract_status, enabled, service_code FROM kras.sync_dataset WHERE dataset_code=?
            """, dataset);
        if (rows.isEmpty()) return List.of("데이터셋이 DB에 없습니다. 스키마 초기화 상태를 확인하세요.");
        Map<String, Object> row = rows.get(0);
        if (!"VERIFIED".equals(row.get("contract_status"))) reasons.add("운영 응답 검증이 필요합니다.");
        if (!Boolean.TRUE.equals(row.get("enabled"))) reasons.add("데이터셋이 비활성 상태입니다.");
        if (row.get("service_code") == null || row.get("service_code").toString().isBlank()) {
            reasons.add("서비스 ID가 확정되지 않았습니다.");
        }
        Supplier<String> code = serviceCodes.get(dataset);
        if (code != null) {
            try {
                String actual = code.get();
                if (actual == null || actual.isBlank()) reasons.add("호출할 서비스 ID가 없습니다.");
                else if (row.get("service_code") != null && !actual.equals(row.get("service_code"))) {
                    reasons.add("DB 서비스 ID와 매퍼의 호출 ID가 다릅니다.");
                }
            } catch (UnsupportedOperationException e) {
                reasons.add("매퍼의 서비스 ID가 미확정입니다. 연계 규격 확인 후 매퍼 설정이 필요합니다.");
            }
        }
        return reasons;
    }

    public void requireReady(JdbcTemplate jdbc, String dataset) {
        List<String> reasons = readiness(jdbc, dataset);
        if (!reasons.isEmpty()) throw new IllegalStateException(String.join(" ", reasons));
    }

    public void requirePnuInput(String orgCd, String pnu, String dataset, Map<String, String> extraParams) {
        if (pnu == null || !pnu.matches("[0-9]{19}")) {
            throw new IllegalArgumentException("PNU는 19자리 숫자여야 합니다.");
        }
        if (!pnu.startsWith(orgCd)) {
            throw new IllegalArgumentException("PNU의 기관코드가 현재 설정된 기관과 다릅니다.");
        }
        if (Set.of("bldg_ho_info", "bldg_hds_info", "cbldg_hds_info", "cbldg_dfhs_info").contains(dataset)
                && extraParams.getOrDefault("bldg_gbn_no", "").isBlank()) {
            throw new IllegalArgumentException("건물 동 조회 결과의 bldg_gbn_no를 추가 파라미터에 입력하세요.");
        }
    }

    public Map<String, Object> history(JdbcTemplate jdbc, String orgCd, String dataset, int limit) {
        int boundedLimit = Math.max(1, Math.min(100, limit));
        String filter = dataset == null ? "" : dataset;
        boolean hasLog = jdbc.queryForObject("SELECT to_regclass('kras.ui_operation_log')::text", String.class) != null;
        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT si.item_id, si.dataset_code, si.scope_key, si.status, si.rows_valid, si.rows_rejected,
                   si.window_start, si.window_end_exclusive, sr.started_at
            FROM kras.sync_item si JOIN kras.sync_run sr ON sr.run_id=si.run_id
            WHERE si.org_cd=? AND (?='' OR si.dataset_code=?)
            ORDER BY si.item_id DESC LIMIT ?
            """, orgCd, filter, filter, boundedLimit);
        List<Map<String, Object>> operations = hasLog ? jdbc.queryForList("""
            SELECT operation_id, dataset_code, action, status, item_id, release_id,
                   started_at, ended_at, request_summary, message, details::text AS details
            FROM kras.ui_operation_log
            WHERE org_cd=? AND (?='' OR dataset_code=?) ORDER BY operation_id DESC LIMIT ?
            """, orgCd, filter, filter, boundedLimit) : List.of();
        return Map.of("items", items, "operations", operations, "hasOperationLog", hasLog);
    }

    /** 비동기 실행(전체 TXT 수집, 용도지역 레이어 순회)의 진행 상태 폴링용. */
    public Map<String, Object> operation(JdbcTemplate jdbc, String orgCd, long operationId) {
        boolean hasLog = jdbc.queryForObject("SELECT to_regclass('kras.ui_operation_log')::text", String.class) != null;
        if (!hasLog) throw new IllegalArgumentException("실행 기록이 없습니다.");
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT operation_id, dataset_code, action, status, item_id, release_id,
                   started_at, ended_at, request_summary, message, details::text AS details
            FROM kras.ui_operation_log WHERE operation_id=? AND org_cd=?
            """, operationId, orgCd);
        if (rows.isEmpty()) throw new IllegalArgumentException("현재 기관에서 해당 실행 기록을 찾을 수 없습니다.");
        return rows.get(0);
    }

    public Map<String, Object> preview(JdbcTemplate jdbc, String orgCd, long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT si.item_id, si.dataset_code, si.status, si.scope_key, si.rows_valid, si.rows_rejected,
                   si.window_start, si.window_end_exclusive
            FROM kras.sync_item si WHERE si.item_id=? AND si.org_cd=?
            """, itemId, orgCd);
        if (rows.isEmpty()) throw new IllegalArgumentException("현재 기관에서 해당 수집 건을 찾을 수 없습니다.");
        Map<String, Object> item = rows.get(0);
        String dataset = item.get("dataset_code").toString();
        List<String> reasons = new ArrayList<>(readiness(jdbc, dataset));
        if (!"SUCCESS".equals(item.get("status"))) reasons.add("수집 검증을 통과한 SUCCESS 상태만 반영할 수 있습니다.");
        String promotePath = promotePaths.get(dataset);
        if (promotePath == null) reasons.add("이 데이터셋은 이력에서 직접 반영하지 않습니다. 해당 수집 카드에서 후속 작업을 확인하세요.");

        List<Map<String, Object>> previews = new ArrayList<>();
        for (String table : stageTables.getOrDefault(dataset, List.of())) {
            List<Object> sample = jdbc.query("SELECT row_to_json(t)::text FROM (SELECT * FROM " + table
                    + " WHERE item_id=? ORDER BY row_no LIMIT 101) t", (rs, rowNum) -> {
                        try { return json.readValue(rs.getString(1), Object.class); }
                        catch (Exception e) { throw new IllegalStateException("미리보기 변환 실패", e); }
                    }, itemId);
            previews.add(Map.of("table", table, "rows", sample.subList(0, Math.min(100, sample.size())),
                    "truncated", sample.size() > 100));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item", item);
        result.put("preview", previews);
        result.put("promotable", reasons.isEmpty());
        result.put("reasons", reasons);
        result.put("promotePath", promotePath);
        return result;
    }
}
