package geosync.kras;

import com.fasterxml.jackson.databind.ObjectMapper;
import geosync.common.xml.XmlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기간(날짜 범위) 조회 서비스(§10~12 계열) 수집/승격. KrasPnuIngestService와 같은 패턴(수동
 * 트랜잭션, 수집/승격 분리 호출)을 재사용하되, 최상위 식별자가 PNU가 아니라 날짜 범위라 별도
 * 서비스로 뒀다 — 억지로 하나로 합치지 않는다.
 *
 * land_change_event처럼 kras.sync_record를 참조하는 업무 테이블(패턴 APPEND_ONLY)을 위해,
 * StageRow에 "payload" 컬럼이 있으면 stage 삽입과 함께 kras.sync_record도 같이 넣고
 * payload_hash를 DB(sha256)로 계산한다 — Java에서 해시 재구현 안 함.
 */
@Service
public class KrasDateRangeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KrasDateRangeIngestService.class);

    private final KrasApiClient krasApiClient;
    private final KrasStagePromotionService promotionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record IngestResult(long runId, long itemId, boolean promotable,
                                List<String> warnings, Map<String, Object> preview) {}
    public record PromoteResult(long itemId) {}

    public KrasDateRangeIngestService(KrasApiClient krasApiClient, KrasStagePromotionService promotionService) {
        this.krasApiClient = krasApiClient;
        this.promotionService = promotionService;
    }

    public IngestResult ingest(JdbcTemplate targetJdbc, String orgCd, KrasDateRangeServiceMapper mapper,
                                LocalDate startDate, LocalDate endDate, Map<String, String> extraParams,
                                String triggeredBy) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("시작일이 종료일보다 늦습니다.");
        }
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days > mapper.maxQueryDays()) {
            throw new IllegalArgumentException(
                    "조회 기간이 %d일 — 이 데이터셋의 최대 조회 기간(%d일)을 초과했습니다.".formatted(days, mapper.maxQueryDays()));
        }
        return targetJdbc.execute((ConnectionCallback<IngestResult>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                IngestResult result = ingestInTransaction(tx, orgCd, mapper, startDate, endDate, extraParams, triggeredBy);
                conn.commit();
                log.info("[KrasDateRangeIngest] 수집 완료 dataset={} itemId={} promotable={} warnings={}",
                        mapper.datasetCode(), result.itemId(), result.promotable(), result.warnings());
                return result;
            } catch (Exception e) {
                conn.rollback();
                log.error("[KrasDateRangeIngest] 수집 실패, 롤백: {}", e.getMessage(), e);
                throw new IllegalStateException(mapper.datasetCode() + " 수집 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    private IngestResult ingestInTransaction(JdbcTemplate tx, String orgCd, KrasDateRangeServiceMapper mapper,
                                              LocalDate startDate, LocalDate endDate, Map<String, String> extraParams,
                                              String triggeredBy) {
        Long runId = findOrCreateTodayRun(tx, orgCd, triggeredBy);

        String scopeKey = tx.queryForObject("""
            SELECT kras.entity_key(jsonb_build_object('start', ?::text, 'end', ?::text) || ?::jsonb)
            """, String.class, startDate.toString(), endDate.toString(), writeJson(extraParams));

        Long itemId = tx.queryForObject("""
            INSERT INTO kras.sync_item(run_id, org_cd, dataset_code, scope_key, window_start, window_end_exclusive)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING item_id
            """, Long.class, runId, orgCd, mapper.datasetCode(), scopeKey, startDate, endDate.plusDays(1));

        Document doc;
        try {
            byte[] xml = krasApiClient.query(mapper.connSvcId(), "", extraParams.isEmpty() ? null : extraParams);
            doc = XmlUtil.parse(xml);
        } catch (Exception e) {
            throw new IllegalStateException(mapper.connSvcId() + " 호출/파싱 실패: " + e.getMessage(), e);
        }
        KrasXmlServiceMapper.MappingResult mapped = mapper.map(doc, orgCd, startDate, endDate);

        Map<String, Integer> rowNoByTable = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> columnsByTable = new LinkedHashMap<>();
        for (KrasXmlServiceMapper.StageRow row : mapped.rows()) {
            int rowNo = rowNoByTable.merge(row.stageTable(), 1, Integer::sum);
            insertStageRow(tx, row, itemId, rowNo);
            columnsByTable.computeIfAbsent(row.stageTable(), k -> new ArrayList<>()).add(row.columns());
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : columnsByTable.entrySet()) {
            if (e.getValue().size() == 1) {
                preview.putAll(e.getValue().get(0));
            } else {
                preview.put(e.getKey(), e.getValue());
            }
        }

        int total = mapped.rows().size();
        boolean promotable = mapped.fieldWarnings().isEmpty();
        if (promotable) {
            tx.update("""
                UPDATE kras.sync_item
                SET status='SUCCESS', rows_received=?, rows_valid=?, rows_rejected=0, is_complete=true
                WHERE item_id=?
                """, total, total, itemId);
        }
        return new IngestResult(runId, itemId, promotable, mapped.fieldWarnings(), preview);
    }

    private Long findOrCreateTodayRun(JdbcTemplate tx, String orgCd, String triggeredBy) {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        List<Long> existing = tx.query("""
            SELECT run_id FROM kras.sync_run
            WHERE org_cd=? AND job_kind='MANUAL' AND period_start=? AND period_end_exclusive=?
            ORDER BY run_id DESC LIMIT 1
            """, (rs, i) -> rs.getLong(1), orgCd, today, tomorrow);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return tx.queryForObject("""
            INSERT INTO kras.sync_run(org_cd, job_kind, triggered_by, period_start, period_end_exclusive)
            VALUES (?, 'MANUAL', ?, ?, ?)
            RETURNING run_id
            """, Long.class, orgCd, triggeredBy, today, tomorrow);
    }

    /**
     * "payload" 컬럼이 있는 행은 kras.sync_record에도 원본을 같이 남긴다(land_change_event처럼
     * source_item_id,record_no로 FK 참조하는 업무 테이블용) — payload_hash는 stage/sync_record 둘 다
     * DB의 sha256으로 계산한다.
     */
    private void insertStageRow(JdbcTemplate tx, KrasXmlServiceMapper.StageRow row, long itemId, int rowNo) {
        Map<String, Object> columns = row.columns();
        String payloadJson = columns.containsKey("payload") ? writeJson(columns.get("payload")) : null;

        List<String> names = new ArrayList<>(List.of("item_id", "row_no"));
        List<String> placeholders = new ArrayList<>(List.of("?", "?"));
        List<Object> values = new ArrayList<>(List.of((Object) itemId, (Object) (long) rowNo));
        for (Map.Entry<String, Object> e : columns.entrySet()) {
            names.add(e.getKey());
            if ("payload".equals(e.getKey())) {
                placeholders.add("?::jsonb");
                values.add(payloadJson);
            } else if ("extra_attributes".equals(e.getKey()) || "field_presence".equals(e.getKey())) {
                placeholders.add("?::jsonb");
                values.add(writeJson(e.getValue()));
            } else {
                placeholders.add("?");
                values.add(e.getValue());
            }
        }
        if (payloadJson != null) {
            names.add("payload_hash");
            placeholders.add("encode(sha256(convert_to(?, 'UTF8')), 'hex')::kras.sha256");
            values.add(payloadJson);
        }
        String sql = "INSERT INTO " + row.stageTable() + "(" + String.join(",", names) + ") VALUES ("
                + String.join(",", placeholders) + ")";
        tx.update(sql, values.toArray());

        if (payloadJson != null) {
            tx.update("""
                INSERT INTO kras.sync_record(item_id, record_no, payload, payload_hash, parser_version, validation_status)
                VALUES (?, ?, ?::jsonb, encode(sha256(convert_to(?, 'UTF8')), 'hex')::kras.sha256, '1', 'VALID')
                """, itemId, rowNo, payloadJson, payloadJson);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("jsonb 직렬화 실패: " + e.getMessage(), e);
        }
    }

    public PromoteResult promote(JdbcTemplate targetJdbc, String orgCd, KrasDateRangeServiceMapper mapper, long itemId) {
        return targetJdbc.execute((ConnectionCallback<PromoteResult>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                PromoteResult result = promoteInTransaction(tx, orgCd, mapper, itemId);
                conn.commit();
                log.info("[KrasDateRangeIngest] 승격 완료 dataset={} itemId={}", mapper.datasetCode(), itemId);
                return result;
            } catch (Exception e) {
                conn.rollback();
                log.error("[KrasDateRangeIngest] 승격 실패, 롤백: {}", e.getMessage(), e);
                throw new IllegalStateException(mapper.datasetCode() + " 승격 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    private PromoteResult promoteInTransaction(JdbcTemplate tx, String orgCd, KrasDateRangeServiceMapper mapper,
                                                long itemId) {
        String status = tx.queryForObject(
                "SELECT status FROM kras.sync_item WHERE item_id=? AND org_cd=? AND dataset_code=?",
                String.class, itemId, orgCd, mapper.datasetCode());
        if (!"SUCCESS".equals(status)) {
            throw new IllegalStateException("item_id=" + itemId + "은 SUCCESS 상태가 아닙니다(파싱 경고로 보류됐거나 "
                    + "잘못된 item일 수 있습니다). status=" + status);
        }
        promotionService.promote(tx, itemId, mapper.promotionSpecs());
        return new PromoteResult(itemId);
    }
}
