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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KrasHistoryService.class);
    private final Map<String, List<String>> stageTables = new LinkedHashMap<>();
    private final Map<String, Supplier<String>> serviceCodes = new LinkedHashMap<>();
    private final Map<String, String> promotePaths = new LinkedHashMap<>();
    private final Map<String, List<KrasStagePromotionService.StagePromotionSpec>> promotionSpecsByDataset = new LinkedHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private static final Set<String> FILE_DATASETS = Set.of(
            "cadastral_file", "layer_list", "usezone_file", "land_basic_file");

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
        promotionSpecsByDataset.put(dataset, specs);
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

    /**
     * 같은 대상(기관·데이터셋·scope_key)의 더 최신 수집 건이 이미 반영됐는지 — 그렇다면 이 건은 DB 트리거
     * (guard_business_row: "Older collection cannot overwrite newer business data")가 막아 영원히 반영할 수 없다.
     * 바깥 쿼리의 sync_item 별칭은 si여야 한다.
     */
    private static final String NEWER_PROMOTED_SQL = """
            SELECT nx.item_id FROM kras.sync_item nx
            JOIN kras.ui_operation_log nl ON nl.item_id=nx.item_id AND nl.action='PROMOTE' AND nl.status IN ('SUCCESS','WARNING')
            WHERE nx.org_cd=si.org_cd AND nx.dataset_code=si.dataset_code AND nx.scope_key=si.scope_key
              AND nx.item_id>si.item_id
            """;

    /**
     * 수집은 SUCCESS인데 업무 테이블 반영(PROMOTE) 성공 기록이 없는 건 — 데이터셋별 건수와 가장 최근 건.
     * 더 최신 건이 이미 반영돼 반영할 수 없게 된 오래된 건은 세지 않는다.
     * ponytail: 반영 여부 전용 컬럼이 없어 ui_operation_log로 판단한다. 로그 도입 전에 반영한 건은 대기로 보인다.
     */
    public Map<String, Map<String, Object>> pendingPromotion(JdbcTemplate jdbc, String orgCd, List<String> codes) {
        if (codes.isEmpty()) return Map.of();
        if (jdbc.queryForObject("SELECT to_regclass('kras.ui_operation_log')::text", String.class) == null) return Map.of();
        List<Object> args = new ArrayList<>();
        args.add(orgCd);
        args.addAll(codes);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT si.dataset_code, count(*) AS pending_count, max(si.item_id) AS latest_item_id
            FROM kras.sync_item si
            WHERE si.org_cd=? AND si.status='SUCCESS' AND si.dataset_code IN (""" + "?,".repeat(codes.size() - 1) + """
            ?)
              AND NOT EXISTS (SELECT 1 FROM kras.ui_operation_log ol
                              WHERE ol.item_id=si.item_id AND ol.action='PROMOTE' AND ol.status IN ('SUCCESS','WARNING'))
            AND NOT EXISTS (""" + NEWER_PROMOTED_SQL + """
            )
            GROUP BY si.dataset_code
            """, args.toArray());
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) byCode.put((String) row.get("dataset_code"), row);
        return byCode;
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
        Map<String, Object> lastPromoteAttempt = null;
        if (jdbc.queryForObject("SELECT to_regclass('kras.ui_operation_log')::text", String.class) != null) {
            List<Long> newer = jdbc.queryForList("SELECT max(t.item_id) FROM kras.sync_item si, LATERAL ("
                    + NEWER_PROMOTED_SQL + ") t WHERE si.item_id=? AND si.org_cd=?", Long.class, itemId, orgCd);
            if (!newer.isEmpty() && newer.get(0) != null) {
                reasons.add("더 최신 수집 건(수집 ID " + newer.get(0) + ")이 이미 반영돼 있어 이 건은 반영할 수 없습니다. "
                        + "최신 업무 데이터를 과거 값으로 덮어쓰지 않도록 DB가 막습니다.");
            }
            List<Map<String, Object>> attempts = jdbc.queryForList("""
                SELECT status, message, started_at, ended_at FROM kras.ui_operation_log
                WHERE org_cd=? AND item_id=? AND action='PROMOTE' ORDER BY operation_id DESC LIMIT 1
                """, orgCd, itemId);
            if (!attempts.isEmpty()) {
                lastPromoteAttempt = new LinkedHashMap<>(attempts.get(0));
                lastPromoteAttempt.put("hint", promoteFailureHint(
                        (String) lastPromoteAttempt.get("status"), (String) lastPromoteAttempt.get("message")));
            }
        }

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
        result.put("lastPromoteAttempt", lastPromoteAttempt);
        // 반영 버튼 옆 참고 정보다 — 비교 조회가 실패해도 반영 자체는 막지 않는다(§2차-3 실패 조건).
        if (promotePath != null) result.put("comparison", comparePreview(jdbc, itemId, dataset));
        return result;
    }

    /** 반영 실패 메시지 중 DB·드라이버가 내는 알아보기 힘든 것만 한글 안내로 풀어준다. 모르는 메시지면 null. */
    static String promoteFailureHint(String status, String message) {
        if ("RUNNING".equals(status)) {
            return "끝난 기록이 없습니다. 지금 실행 중이 아니라면 반영 도중 중단된 것(서버 재시작 등)이니 다시 반영하세요.";
        }
        if (message == null) return null;
        if (message.contains("Older collection cannot overwrite")) {
            return "더 최신 수집 건이 이미 반영돼 있어 이 건은 반영할 수 없습니다.";
        }
        if (message.contains("expected 1, actual 0")) {
            return "상위(부모) 데이터가 업무 테이블에 아직 없습니다. 상위 데이터셋을 먼저 반영한 뒤 다시 시도하세요.";
        }
        if (message.contains("cannot populate")) {
            return "이 데이터셋이 해당 업무 테이블에 쓸 수 있도록 DB(kras.business_dataset)에 등록돼 있지 않습니다.";
        }
        if (message.contains("Stable identity")) {
            return "삭제가 금지된 업무 테이블에서 삭제가 발생했습니다. 매퍼의 반영 방식을 점검해야 합니다.";
        }
        if (message.contains("Invalid business source item/organization")) {
            return "수집 건의 상태나 기관코드가 맞지 않아 DB가 반영을 거부했습니다.";
        }
        if (message.contains("not-null")) return "필수값이 비어 있습니다. 응답 매핑(데이터 구조)을 확인하세요.";
        if (message.contains("value too long")) return "값이 컬럼 길이보다 깁니다. 응답 매핑(데이터 구조)을 확인하세요.";
        if (message.contains("invalid input syntax")) return "값의 형식이 컬럼 타입과 맞지 않습니다. 응답 매핑(데이터 구조)을 확인하세요.";
        if (message.contains("onnection") || message.contains("I/O error")) {
            return "DB 연결 문제로 실패했습니다. 잠시 후 다시 반영하세요.";
        }
        return null;
    }

    /**
     * 반영 전 건수 비교 — 패턴마다 "증감"의 의미가 달라 공통 로직 하나로 만들지 않고
     * StagePromotionSpec.pattern()으로 분기한다(§2차-3 결정). 자식 테이블(ChildSpec)은 다루지 않는다 —
     * 부모 수준 비교만으로 반영 판단에 충분하다.
     */
    private List<Map<String, Object>> comparePreview(JdbcTemplate jdbc, long itemId, String dataset) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var spec : promotionSpecsByDataset.getOrDefault(dataset, List.of())) {
            try {
                out.add(compareSpec(jdbc, itemId, spec));
            } catch (Exception e) {
                log.warn("[KrasHistory] 반영 전 비교 실패 dataset={} table={}: {}", dataset, spec.businessTable(), e.getMessage());
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("businessTable", spec.businessTable());
                failed.put("pattern", spec.pattern().name());
                failed.put("note", "비교 정보를 계산할 수 없습니다(참고용 정보일 뿐 반영 자체는 막지 않습니다).");
                out.add(failed);
            }
        }
        return out;
    }

    private Map<String, Object> compareSpec(JdbcTemplate jdbc, long itemId, KrasStagePromotionService.StagePromotionSpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessTable", spec.businessTable());
        out.put("pattern", spec.pattern().name());
        Long stageCount = jdbc.queryForObject(
                "SELECT count(*) FROM " + spec.stageTable() + " WHERE item_id=?", Long.class, itemId);
        out.put("stageCount", stageCount);

        switch (spec.pattern()) {
            case NATURAL_KEY_UPSERT -> {
                if (spec.naturalKeyColumns().isEmpty()) break;
                Map<String, Object> stageRow = jdbc.queryForMap("SELECT " + String.join(",", spec.naturalKeyColumns())
                        + " FROM " + spec.stageTable() + " WHERE item_id=? AND row_no=1", itemId);
                String where = spec.naturalKeyColumns().stream().map(c -> c + "=?").reduce((a, b) -> a + " AND " + b).orElseThrow();
                Object[] args = spec.naturalKeyColumns().stream().map(stageRow::get).toArray();
                Long exists = jdbc.queryForObject("SELECT count(*) FROM " + spec.businessTable() + " WHERE " + where, Long.class, args);
                boolean existing = exists != null && exists > 0;
                out.put("note", existing ? "기존 행 UPDATE 예상(자연키 일치)" : "신규 행 INSERT 예상(자연키 불일치)");
            }
            case IDENTITY_MATCH_UPSERT, PARENT_LOOKUP_IDENTITY_MATCH_UPSERT -> out.put("note",
                    "행별 자연키 신원 매칭 — 있으면 UPDATE, 없으면 INSERT (건별 결과는 반영 후 업무 테이블에서 확인)");
            case SCOPE_REPLACE, SCOPE_REPLACE_WITH_CHILDREN -> {
                if (spec.scopeColumn() == null) break;
                Object scopeValue = jdbc.queryForObject("SELECT " + spec.scopeColumn() + " FROM " + spec.stageTable()
                        + " WHERE item_id=? AND row_no=1", Object.class, itemId);
                Long currentCount = jdbc.queryForObject("SELECT count(*) FROM " + spec.businessTable()
                        + " WHERE " + spec.scopeColumn() + "=?", Long.class, scopeValue);
                out.put("scopeColumn", spec.scopeColumn());
                out.put("scopeValue", scopeValue);
                out.put("currentCount", currentCount);
                out.put("note", "이 범위(%s=%s)의 기존 %d건을 전체 삭제 후 %d건으로 교체"
                        .formatted(spec.scopeColumn(), scopeValue, currentCount, stageCount));
            }
            case APPEND_ONLY -> out.put("note", "추가 전용 — 이번 반영으로 최대 %d건 추가(재수집분은 중복 제외)".formatted(stageCount));
        }
        return out;
    }
}
