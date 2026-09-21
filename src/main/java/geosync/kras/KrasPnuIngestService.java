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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PNU 단건 서비스(§6.3) 수집/승격. KrasCadastralIngestService와 같은 패턴(수동 트랜잭션, 수집/승격
 * 분리 호출)을 재사용한다 — 새 메커니즘을 안 만든다.
 */
@Service
public class KrasPnuIngestService {

    private static final Logger log = LoggerFactory.getLogger(KrasPnuIngestService.class);

    private final KrasApiClient krasApiClient;
    private final KrasStagePromotionService promotionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record IngestResult(long runId, long itemId, boolean promotable,
                                List<String> warnings, Map<String, Object> preview) {}
    public record PromoteResult(long itemId) {}

    public KrasPnuIngestService(KrasApiClient krasApiClient, KrasStagePromotionService promotionService) {
        this.krasApiClient = krasApiClient;
        this.promotionService = promotionService;
    }

    /** 설계 §6.3 절차 1~8, 하나의 트랜잭션. */
    public IngestResult ingest(JdbcTemplate targetJdbc, String orgCd, KrasXmlServiceMapper mapper,
                                String pnu, String triggeredBy) {
        if (!pnu.matches("\\d{19}")) {
            throw new IllegalArgumentException("PNU 형식 오류: 19자리 숫자여야 합니다");
        }
        return targetJdbc.execute((ConnectionCallback<IngestResult>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                IngestResult result = ingestInTransaction(tx, orgCd, mapper, pnu, triggeredBy);
                conn.commit();
                log.info("[KrasPnuIngest] 수집 완료 dataset={} itemId={} promotable={} warnings={}",
                        mapper.datasetCode(), result.itemId(), result.promotable(), result.warnings());
                return result;
            } catch (Exception e) {
                conn.rollback();
                log.error("[KrasPnuIngest] 수집 실패, 롤백: {}", e.getMessage(), e);
                throw new IllegalStateException(mapper.datasetCode() + " 수집 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    private IngestResult ingestInTransaction(JdbcTemplate tx, String orgCd, KrasXmlServiceMapper mapper,
                                              String pnu, String triggeredBy) {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        Long runId = findOrCreateTodayRun(tx, orgCd, triggeredBy, today, tomorrow);

        String scopeKey = tx.queryForObject(
                "SELECT kras.entity_key(jsonb_build_object('pnu', ?::text))", String.class, pnu);

        Long itemId = tx.queryForObject("""
            INSERT INTO kras.sync_item(run_id, org_cd, dataset_code, scope_key, window_start, window_end_exclusive)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING item_id
            """, Long.class, runId, orgCd, mapper.datasetCode(), scopeKey, today, tomorrow);

        Document doc;
        try {
            byte[] xml = krasApiClient.query(mapper.connSvcId(), pnu, null);
            doc = XmlUtil.parse(xml);
        } catch (Exception e) {
            throw new IllegalStateException(mapper.connSvcId() + " 호출/파싱 실패: " + e.getMessage(), e);
        }
        KrasXmlServiceMapper.MappingResult mapped = mapper.map(doc, pnu);

        // stage 테이블 하나에 여러 행이 몰릴 수 있다(반복 그룹 응답) — 테이블별로 row_no를 1부터 증가시킨다.
        Map<String, Integer> rowNoByTable = new java.util.LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> columnsByTable = new java.util.LinkedHashMap<>();
        for (KrasXmlServiceMapper.StageRow row : mapped.rows()) {
            int rowNo = rowNoByTable.merge(row.stageTable(), 1, Integer::sum);
            insertStageRow(tx, row, itemId, rowNo);
            columnsByTable.computeIfAbsent(row.stageTable(), k -> new ArrayList<>()).add(row.columns());
        }
        Map<String, Object> preview = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : columnsByTable.entrySet()) {
            if (e.getValue().size() == 1) {
                preview.putAll(e.getValue().get(0));
            } else {
                preview.put(e.getKey(), e.getValue());
            }
        }

        boolean promotable = mapped.fieldWarnings().isEmpty();
        if (promotable) {
            tx.update("""
                UPDATE kras.sync_item
                SET status='SUCCESS', rows_received=1, rows_valid=1, rows_rejected=0, is_complete=true
                WHERE item_id=?
                """, itemId);
        }
        return new IngestResult(runId, itemId, promotable, mapped.fieldWarnings(), preview);
    }

    private Long findOrCreateTodayRun(JdbcTemplate tx, String orgCd, String triggeredBy,
                                       LocalDate today, LocalDate tomorrow) {
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

    private void insertStageRow(JdbcTemplate tx, KrasXmlServiceMapper.StageRow row, long itemId, int rowNo) {
        List<String> names = new ArrayList<>(List.of("item_id", "row_no"));
        List<String> placeholders = new ArrayList<>(List.of("?", "?"));
        List<Object> values = new ArrayList<>(List.of((Object) itemId, (Object) (long) rowNo));
        for (Map.Entry<String, Object> e : row.columns().entrySet()) {
            names.add(e.getKey());
            if ("extra_attributes".equals(e.getKey()) || "field_presence".equals(e.getKey())) {
                placeholders.add("?::jsonb");
                values.add(writeJson(e.getValue()));
            } else {
                placeholders.add("?");
                values.add(e.getValue());
            }
        }
        String sql = "INSERT INTO " + row.stageTable() + "(" + String.join(",", names) + ") VALUES ("
                + String.join(",", placeholders) + ")";
        tx.update(sql, values.toArray());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("jsonb 직렬화 실패: " + e.getMessage(), e);
        }
    }

    /** 설계 §6.3 절차 9, 별도 트랜잭션. item이 SUCCESS(필드 파싱 경고 없이 수집 완료)여야만 승격 가능. */
    public PromoteResult promote(JdbcTemplate targetJdbc, String orgCd, KrasXmlServiceMapper mapper, long itemId) {
        return targetJdbc.execute((ConnectionCallback<PromoteResult>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                PromoteResult result = promoteInTransaction(tx, orgCd, mapper, itemId);
                conn.commit();
                log.info("[KrasPnuIngest] 승격 완료 dataset={} itemId={}", mapper.datasetCode(), itemId);
                return result;
            } catch (Exception e) {
                conn.rollback();
                log.error("[KrasPnuIngest] 승격 실패, 롤백: {}", e.getMessage(), e);
                throw new IllegalStateException(mapper.datasetCode() + " 승격 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    private PromoteResult promoteInTransaction(JdbcTemplate tx, String orgCd, KrasXmlServiceMapper mapper,
                                                long itemId) {
        String status = tx.queryForObject(
                "SELECT status FROM kras.sync_item WHERE item_id=? AND org_cd=?", String.class, itemId, orgCd);
        if (!"SUCCESS".equals(status)) {
            throw new IllegalStateException("item_id=" + itemId + "은 SUCCESS 상태가 아닙니다(파싱 경고로 보류됐거나 "
                    + "잘못된 item일 수 있습니다). status=" + status);
        }
        List<KrasStagePromotionService.StagePromotionSpec> specs = mapper.promotionSpecs();
        // 패턴 C(신원 매칭 후 UPSERT)는 탐색→UPDATE/INSERT 사이 경쟁 상태가 있어 직렬화가 필요하다(설계 §8.7).
        // 패턴 A는 ON CONFLICT 자체가 원자적이라 불필요 — 필요한 경우에만 건다.
        boolean needsSerialization = specs.stream()
                .anyMatch(s -> s.pattern() == KrasStagePromotionService.PromotionPattern.IDENTITY_MATCH_UPSERT);
        if (needsSerialization) {
            String pnu = tx.queryForObject("SELECT pnu FROM kras.stage_parcel WHERE item_id=?", String.class, itemId);
            tx.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                    "kras-promote:" + orgCd + ":" + pnu);
        }
        promotionService.promote(tx, itemId, specs);
        return new PromoteResult(itemId);
    }
}
