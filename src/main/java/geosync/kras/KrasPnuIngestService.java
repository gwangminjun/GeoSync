package geosync.kras;

import com.fasterxml.jackson.databind.ObjectMapper;
import geosync.common.xml.XmlUtil;
import geosync.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final KorepsApiClient korepsApiClient;
    private final KrasStagePromotionService promotionService;
    private final RuntimeSettingsService settings;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record IngestResult(long runId, long itemId, boolean promotable,
                                List<String> warnings, Map<String, Object> preview) {}
    public record PromoteResult(long itemId) {}

    public KrasPnuIngestService(KrasApiClient krasApiClient, KorepsApiClient korepsApiClient,
                                 KrasStagePromotionService promotionService, RuntimeSettingsService settings) {
        this.krasApiClient = krasApiClient;
        this.korepsApiClient = korepsApiClient;
        this.promotionService = promotionService;
        this.settings = settings;
    }

    /** 설계 §6.3 절차 1~8, 하나의 트랜잭션. PNU만으로 되는 서비스용 — extraParams 없이 위임. */
    public IngestResult ingest(JdbcTemplate targetJdbc, String orgCd, KrasXmlServiceMapper mapper,
                                String pnu, String triggeredBy) {
        return ingest(targetJdbc, orgCd, mapper, pnu, Map.of(), triggeredBy);
    }

    /**
     * PNU 외 추가 요청 파라미터가 필요한 드릴다운 서비스(집합건물 전유부 등)용. extraParams는
     * (1) krasApiClient.query()로 그대로 전달되고, (2) scope_key에 섞여 같은 PNU라도 파라미터가
     * 다르면 별개 item이 되고, (3) 모든 stage 행의 extra_attributes에 자동으로 남아(_pnu/_extra_params)
     * 승격 단계에서 상위 서로게이트 ID를 조회하는 등의 용도로 쓸 수 있다.
     */
    public IngestResult ingest(JdbcTemplate targetJdbc, String orgCd, KrasXmlServiceMapper mapper,
                                String pnu, Map<String, String> extraParams, String triggeredBy) {
        if (!pnu.matches("\\d{19}")) {
            throw new IllegalArgumentException("PNU 형식 오류: 19자리 숫자여야 합니다");
        }
        return targetJdbc.execute((ConnectionCallback<IngestResult>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                IngestResult result = ingestInTransaction(tx, orgCd, mapper, pnu, extraParams, triggeredBy);
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
                                              String pnu, Map<String, String> extraParams, String triggeredBy) {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        Long runId = findOrCreateTodayRun(tx, orgCd, triggeredBy, today, tomorrow);

        String scopeKey = tx.queryForObject(
                "SELECT kras.entity_key(jsonb_build_object('pnu', ?::text) || ?::jsonb)",
                String.class, pnu, writeJson(extraParams));

        Long itemId = tx.queryForObject("""
            INSERT INTO kras.sync_item(run_id, org_cd, dataset_code, scope_key, window_start, window_end_exclusive)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING item_id
            """, Long.class, runId, orgCd, mapper.datasetCode(), scopeKey, today, tomorrow);

        Document doc;
        try {
            Map<String, String> extra = extraParams.isEmpty() ? null : extraParams;
            byte[] xml = "KOREPS".equals(mapper.sourceSystem())
                    ? korepsApiClient.query(mapper.connSvcId(), pnu, extra)
                    : krasApiClient.query(mapper.connSvcId(), pnu, extra);
            doc = XmlUtil.parse(xml);
        } catch (Exception e) {
            throw new IllegalStateException(mapper.connSvcId() + " 호출/파싱 실패: " + e.getMessage(), e);
        }
        KrasXmlServiceMapper.MappingResult mapped = mapper.map(doc, pnu, extraParams);

        // stage 테이블 하나에 여러 행이 몰릴 수 있다(반복 그룹 응답) — 테이블별로 row_no를 1부터 증가시킨다.
        Map<String, Integer> rowNoByTable = new java.util.LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> columnsByTable = new java.util.LinkedHashMap<>();
        for (KrasXmlServiceMapper.StageRow row : mapped.rows()) {
            int rowNo = rowNoByTable.merge(row.stageTable(), 1, Integer::sum);
            insertStageRow(tx, row, itemId, rowNo, orgCd, mapper.datasetCode(), pnu, extraParams);
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

    /**
     * extra_attributes에 항상 _pnu/_extra_params를 함께 적어둔다(매퍼가 이미 뭘 넣어놨든 병합) —
     * 승격 단계에서 상위 서로게이트 ID를 조회해야 하는 드릴다운 매퍼(예: collective_unit이
     * collective_building_id를 찾을 때)가 이걸로 pnu/요청 파라미터를 다시 알아낸다. 일반 매퍼는
     * 그냥 부가 정보로 남을 뿐 기존 승격 로직(copyColumns에 extra_attributes를 안 넣음)엔 영향 없다.
     */
    @SuppressWarnings("unchecked")
    private void insertStageRow(JdbcTemplate tx, KrasXmlServiceMapper.StageRow row, long itemId, int rowNo,
                                 String orgCd, String datasetCode, String pnu, Map<String, String> extraParams) {
        Map<String, Object> columns = new java.util.LinkedHashMap<>(row.columns());
        resolveFileColumns(tx, columns, itemId, orgCd, datasetCode, pnu, extraParams);
        Map<String, Object> extra = (Map<String, Object>) columns.computeIfAbsent(
                "extra_attributes", k -> new java.util.LinkedHashMap<String, Object>());
        extra.put("_pnu", pnu);
        if (!extraParams.isEmpty()) {
            extra.put("_extra_params", extraParams);
        }

        List<String> names = new ArrayList<>(List.of("item_id", "row_no"));
        List<String> placeholders = new ArrayList<>(List.of("?", "?"));
        List<Object> values = new ArrayList<>(List.of((Object) itemId, (Object) (long) rowNo));
        for (Map.Entry<String, Object> e : columns.entrySet()) {
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

    /**
     * StageRow.columns()에 "_file_bytes"(byte[])가 있으면 building_image처럼 응답 자체가 파일인 서비스로
     * 보고 kras.sync_file에 원본을 저장한 뒤 file_id/request_key를 채운다. 다른 매퍼는 이 키를 안 써서
     * 그냥 조용히 리턴한다 — 기존 15개 매퍼는 영향 없음. 파일은 KrasWorkspaceScanner와 같은 워크스페이스
     * 루트(kras.work-dir/{orgCd}/) 아래 files/{datasetCode}/에 저장한다.
     */
    private void resolveFileColumns(JdbcTemplate tx, Map<String, Object> columns, long itemId,
                                     String orgCd, String datasetCode, String pnu, Map<String, String> extraParams) {
        // request_key(NOT NULL)를 선언한 매퍼는 값 자리에 표시만 넣어둔다 — 해시는 DB 함수로 계산한다.
        if (KrasSpecMapper.COMPUTE_REQUEST_KEY.equals(columns.get("request_key"))) {
            columns.put("request_key", tx.queryForObject(
                    "SELECT kras.request_key(?, '1', ?, '', ?::jsonb)", String.class,
                    datasetCode, pnu, writeJson(extraParams)));
        }

        byte[] bytes = (byte[]) columns.remove("_file_bytes");
        if (bytes == null) return;
        String fileType = (String) columns.remove("_file_type");
        if (fileType == null) fileType = datasetCode.toUpperCase();

        Path dir = Path.of(settings.krasWorkDir(), orgCd, "files", datasetCode);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("파일 저장 디렉토리 생성 실패: " + e.getMessage(), e);
        }
        Path filePath = dir.resolve(pnu + "_" + itemId + ".bin");
        try {
            Files.write(filePath, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("파일 저장 실패: " + e.getMessage(), e);
        }

        Long fileId = tx.queryForObject("""
            INSERT INTO kras.sync_file(item_id, file_type, storage_uri, sha256, byte_size)
            VALUES (?, ?, ?, encode(sha256(?), 'hex')::kras.sha256, ?)
            RETURNING file_id
            """, Long.class, itemId, fileType, filePath.toString(), bytes, (long) bytes.length);
        columns.put("file_id", fileId);

        String requestKey = tx.queryForObject(
                "SELECT kras.request_key(?, '1', ?, '', ?::jsonb)", String.class,
                datasetCode, pnu, writeJson(extraParams));
        columns.put("request_key", requestKey);
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
                "SELECT status FROM kras.sync_item WHERE item_id=? AND org_cd=? AND dataset_code=?",
                String.class, itemId, orgCd, mapper.datasetCode());
        if (!"SUCCESS".equals(status)) {
            throw new IllegalStateException("item_id=" + itemId + "은 SUCCESS 상태가 아닙니다(파싱 경고로 보류됐거나 "
                    + "잘못된 item일 수 있습니다). status=" + status);
        }
        List<KrasStagePromotionService.StagePromotionSpec> specs = mapper.promotionSpecs();
        // 탐색→UPDATE/INSERT 사이 경쟁 상태가 있는 패턴(C, 부모 조회 후 신원 매칭)은 직렬화가 필요하다
        // (설계 §8.7). 패턴 A/B는 원자적 SQL 한 문장이라 불필요 — 필요한 경우에만 건다.
        boolean needsSerialization = specs.stream().anyMatch(s ->
                s.pattern() == KrasStagePromotionService.PromotionPattern.IDENTITY_MATCH_UPSERT
                        || s.pattern() == KrasStagePromotionService.PromotionPattern.PARENT_LOOKUP_IDENTITY_MATCH_UPSERT);
        if (needsSerialization) {
            // 모든 stage 행에 _pnu가 자동으로 남아 있어(insertStageRow) 어느 spec의 stage 테이블에서든 읽을 수 있다.
            String pnu = tx.queryForObject(
                    "SELECT extra_attributes->>'_pnu' FROM " + specs.get(0).stageTable() + " WHERE item_id=? LIMIT 1",
                    String.class, itemId);
            tx.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                    "kras-promote:" + orgCd + ":" + pnu);
        }
        promotionService.promote(tx, itemId, specs);
        return new PromoteResult(itemId);
    }
}
