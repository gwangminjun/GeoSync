package geosync.kras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * kras 스키마 연속지적(lp_pa_cbnd) 적재.
 * 설계: docs/superpowers/specs/2026-09-18-kras-ingest-implementation-design.md §4, §8.
 *
 * KrasWorker/OdsRepository는 건드리지 않는다 — SHP를 별도로 다시 읽어 kras 스키마에만 적재한다.
 * 수집(ingest, 설계 절차 1~6)과 승격(promote, 절차 7~8)을 분리해, public.lp_pa_cbnd
 * (실제 GeoServer 테이블)를 건드리는 승격은 항상 별도 호출로만 실행되게 한다.
 *
 * 대상 DB가 런타임에 바뀔 수 있는 JdbcTemplate이라 Spring @Transactional을 못 쓴다 —
 * JdbcTemplate.execute(ConnectionCallback)로 원본 Connection을 받아 그 위에서만 수동 트랜잭션을 연다.
 */
@Service
public class KrasCadastralIngestService {

    private static final Logger log = LoggerFactory.getLogger(KrasCadastralIngestService.class);

    /** KrasWorker.KRAS_EPSG(private)와 동일한 값. SHP가 이 좌표계로 내려온다. */
    private static final int SOURCE_EPSG = 5174;
    private static final int STORAGE_EPSG = 5186;
    private static final String DATASET_CODE = "cadastral_file";

    public record IngestResult(long runId, long itemId, int rowCount, int skippedCount) {}
    public record PromoteResult(long itemId, long promotedRows) {}

    /**
     * 설계 절차 1~6. rows는 workspaceScanner.loadShpFile() 결과
     * (키: pnu, jibun, bchk, xgeometry — conf/kras/base-tables.xml의 LSMD_CONT_LDREG 정의 기준).
     * 도형이 없는 행은 건너뛰고 rows_received/rows_valid에서 제외한다(guard_item_transition이
     * rows_valid=rows_received를 요구하므로, "받았지만 무효"는 이번 1차 구현에서는 sync_reject로
     * 안 남기고 로그로만 남긴다 — ponytail: 필요해지면 sync_reject 적재 추가).
     */
    public IngestResult ingest(JdbcTemplate targetJdbc, String orgCd, String layerCode,
                                List<Map<String, Object>> rows, String triggeredBy) {
        if (rows.isEmpty()) {
            throw new IllegalStateException("SHP에서 읽은 행이 0건입니다 — 적재를 중단합니다.");
        }
        return targetJdbc.execute((ConnectionCallback<IngestResult>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                IngestResult result = ingestInTransaction(tx, orgCd, layerCode, rows, triggeredBy);
                conn.commit();
                log.info("[KrasCadastralIngest] 적재 완료 runId={} itemId={} rows={} skipped={}",
                        result.runId(), result.itemId(), result.rowCount(), result.skippedCount());
                return result;
            } catch (Exception e) {
                conn.rollback();
                log.error("[KrasCadastralIngest] 적재 실패, 롤백: {}", e.getMessage(), e);
                throw new IllegalStateException("연속지적 kras 적재 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    private IngestResult ingestInTransaction(JdbcTemplate tx, String orgCd, String layerCode,
                                              List<Map<String, Object>> rows, String triggeredBy) {
        // 1. spatial_layer 존재 확인/등록
        tx.update("""
            INSERT INTO kras.spatial_layer(layer_code, dataset_code, source_epsg, target_epsg)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (layer_code) DO NOTHING
            """, layerCode, DATASET_CODE, SOURCE_EPSG, STORAGE_EPSG);

        // 2. sync_run
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        Long runId = tx.queryForObject("""
            INSERT INTO kras.sync_run(org_cd, job_kind, triggered_by, period_start, period_end_exclusive)
            VALUES (?, 'MANUAL', ?, ?, ?)
            RETURNING run_id
            """, Long.class, orgCd, triggeredBy, today, tomorrow);

        // 3. sync_item (기본 status='PLANNED' — guard_item_transition이 INSERT 시 SUCCESS 직접 지정을 막음)
        Long itemId = tx.queryForObject("""
            INSERT INTO kras.sync_item(run_id, org_cd, dataset_code, scope_key, window_start, window_end_exclusive)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING item_id
            """, Long.class, runId, orgCd, DATASET_CODE, "LAYER:" + layerCode, today, tomorrow);

        // 4~5. spatial_feature / cadastral_feature — 같은 순회에서 feature_no를 맞춰 채운다.
        List<Object[]> featureParams = new ArrayList<>(rows.size());
        List<Object[]> cadastralParams = new ArrayList<>(rows.size());
        int featureNo = 1;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            String wkt = (String) row.get("xgeometry");
            if (wkt == null || wkt.isBlank()) {
                skipped++;
                continue;
            }
            featureParams.add(new Object[]{ itemId, featureNo, orgCd, layerCode, row.get("pnu"), wkt });
            cadastralParams.add(new Object[]{ row.get("jibun"), row.get("bchk"), itemId, featureNo });
            featureNo++;
        }
        if (featureParams.isEmpty()) {
            throw new IllegalStateException("유효한 도형이 있는 행이 0건입니다(전체 " + rows.size() + "건 중 " + skipped + "건 도형 없음).");
        }
        if (skipped > 0) {
            log.warn("[KrasCadastralIngest] 도형 없는 행 {}건 건너뜀 (item_id={})", skipped, itemId);
        }

        tx.batchUpdate("""
            INSERT INTO kras.spatial_feature(item_id, feature_no, org_cd, layer_code, pnu, geom)
            VALUES (?, ?, ?, ?, ?, ST_Transform(ST_GeomFromText(?, %d), %d))
            """.formatted(SOURCE_EPSG, STORAGE_EPSG), featureParams);

        // cadastral_feature는 spatial_feature에서 geom을 SELECT로 그대로 가져온다 — WKT 재파싱 없음.
        // guard_geometry가 ST_Equals로 두 테이블 geom이 "정확히" 같은지 검사하므로 재파싱 오차를 원천 차단한다.
        tx.batchUpdate("""
            INSERT INTO kras.cadastral_feature(item_id, feature_no, org_cd, layer_code, pnu, jibun, bchk, geom)
            SELECT item_id, feature_no, org_cd, layer_code, pnu, ?, ?, geom
            FROM kras.spatial_feature WHERE item_id = ? AND feature_no = ?
            """, cadastralParams);

        // 6. sync_item을 UPDATE로 SUCCESS 전환
        //    전제조건: kras.sync_dataset.dataset_code='cadastral_file'가 enabled=true,
        //    contract_status='VERIFIED'여야 guard_item_transition을 통과한다.
        int n = featureParams.size();
        tx.update("""
            UPDATE kras.sync_item
            SET status='SUCCESS', rows_received=?, rows_valid=?, rows_rejected=0, is_complete=true
            WHERE item_id=?
            """, n, n, itemId);

        return new IngestResult(runId, itemId, n, skipped);
    }

    /**
     * 설계 절차 7~8. 별도 트랜잭션 — 수집과 항상 분리 호출된다.
     * org_cd/layer_code로 가장 최근 SUCCESS item을 찾아 게시하고 public.lp_pa_cbnd로 승격한다.
     */
    public PromoteResult promote(JdbcTemplate targetJdbc, String orgCd, String layerCode) {
        return targetJdbc.execute((ConnectionCallback<PromoteResult>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                PromoteResult result = promoteInTransaction(tx, orgCd, layerCode);
                conn.commit();
                log.info("[KrasCadastralIngest] 승격 완료 itemId={} promotedRows={}",
                        result.itemId(), result.promotedRows());
                return result;
            } catch (Exception e) {
                conn.rollback();
                log.error("[KrasCadastralIngest] 승격 실패, 롤백: {}", e.getMessage(), e);
                throw new IllegalStateException("public.lp_pa_cbnd 승격 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    private PromoteResult promoteInTransaction(JdbcTemplate tx, String orgCd, String layerCode) {
        String scopeKey = "LAYER:" + layerCode;

        Long itemId;
        try {
            itemId = tx.queryForObject("""
                SELECT item_id FROM kras.sync_item
                WHERE org_cd=? AND dataset_code=? AND scope_key=? AND status='SUCCESS'
                ORDER BY item_id DESC LIMIT 1
                """, Long.class, orgCd, DATASET_CODE, scopeKey);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new IllegalStateException("SUCCESS 상태인 수집 item이 없습니다 — 먼저 수집을 실행하세요.");
        }

        // 7. sync_publication upsert (guard_sync_publication이 완전성·역행 여부를 검증)
        List<Long> existingPubNo = tx.query("""
            SELECT publication_no FROM kras.sync_publication
            WHERE org_cd=? AND dataset_code=? AND scope_key=? FOR UPDATE
            """, (rs, i) -> rs.getLong(1), orgCd, DATASET_CODE, scopeKey);
        long nextPubNo = existingPubNo.isEmpty() ? 1 : existingPubNo.get(0) + 1;
        if (existingPubNo.isEmpty()) {
            tx.update("""
                INSERT INTO kras.sync_publication(org_cd, dataset_code, scope_key, item_id, publication_no)
                VALUES (?, ?, ?, ?, ?)
                """, orgCd, DATASET_CODE, scopeKey, itemId, nextPubNo);
        } else {
            tx.update("""
                UPDATE kras.sync_publication
                SET item_id=?, published_at=now(), publication_no=?
                WHERE org_cd=? AND dataset_code=? AND scope_key=?
                """, itemId, nextPubNo, orgCd, DATASET_CODE, scopeKey);
        }

        // 8. public.lp_pa_cbnd로 승격 (0건이면 kras.sync_public_cadastral() 자체가 예외를 던짐 — 이미 검증된 안전장치)
        Long promoted = tx.queryForObject("SELECT kras.sync_public_cadastral()", Long.class);
        return new PromoteResult(itemId, promoted != null ? promoted : 0);
    }
}
