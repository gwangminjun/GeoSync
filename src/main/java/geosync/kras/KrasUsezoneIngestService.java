package geosync.kras;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import geosync.ods.UsezoneCodeService;
import geosync.settings.RuntimeSettingsService;
import geosync.synchronization.TableMapper;
import geosync.synchronization.model.SyncTableDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * kras 스키마 용도지역(usezone_file) 배치 적재 — 설계 §9.
 * 연속지적(KrasCadastralIngestService)과 다른 점: 기관 전체 레이어가 kras.spatial_release로
 * 한 번에 검증돼야 게시된다(부분 레이어만 성공하면 publish_spatial_release()가 막는다 — F2 재현 방지).
 *
 * 절차 0~6은 설계 §9.1 그대로:
 * 0. 카탈로그 수집 — krasApiClient.layerList()로 받은 전체 USEZONE 레이어 목록을
 *    dataset_code='layer_list' item + sync_record로 동결(이후 매니페스트 검증의 기준이 됨).
 * 1~4. 레이어 순회 — release(DRAFT) 생성 → expected 등록 → seal(READY, 매니페스트 검증) →
 *    레이어별로 SHP 다운로드/파싱 → spatial_feature/usezone_feature 적재 → release_member 등록.
 *    레이어당 별도 트랜잭션이라 일부만 실패해도 나머지는 유지되고, 실패한 레이어만 재시도된다.
 * 5. release 발행 — kras.publish_spatial_release()가 전체 완전성을 검증(하나라도 미완료면 막힘).
 * 6. public 승격 — 기존 kras.sync_public_usezone() 재사용(새로 안 만듦, docs/database/kras-schema-public-sync.sql).
 *
 * theme_code/theme_name(ucode/uname)은 SHP에 없다 — KrasWorker.deriveUsezoneFields와 같은 규칙
 * (mnum 21~26번째 자리 파생 + mt_usezone_cd 코드 테이블 조회)을 그대로 재사용한다. 이 로직 자체는
 * 문서가 아니라 이미 검증된 구 시스템 동작이라 "추측 금지" 원칙의 예외가 아니라 재사용 대상이다.
 */
@Service
public class KrasUsezoneIngestService {

    private static final Logger log = LoggerFactory.getLogger(KrasUsezoneIngestService.class);

    private static final int SOURCE_EPSG = 5174;
    private static final int STORAGE_EPSG = 5186;
    private static final String CATALOG_DATASET = "layer_list";
    private static final String LAYER_DATASET = "usezone_file";
    private static final String TARGET_TABLE = "ods.lt_c_uzone";

    private final KrasApiClient krasApiClient;
    private final KrasWorkspaceScanner workspaceScanner;
    private final TableMapper tableMapper;
    private final RuntimeSettingsService settings;
    private final UsezoneCodeService usezoneCodeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record CatalogResult(long itemId, List<String> layerCodes) {}
    public record LayerResult(String layerCode, boolean success, String message) {}
    public record SweepResult(long releaseId, List<LayerResult> layers) {}

    public KrasUsezoneIngestService(KrasApiClient krasApiClient, KrasWorkspaceScanner workspaceScanner,
                                     TableMapper tableMapper, RuntimeSettingsService settings,
                                     UsezoneCodeService usezoneCodeService) {
        this.krasApiClient = krasApiClient;
        this.workspaceScanner = workspaceScanner;
        this.tableMapper = tableMapper;
        this.settings = settings;
        this.usezoneCodeService = usezoneCodeService;
    }

    // ── 0. 카탈로그 수집 ──────────────────────────────────────────────

    public CatalogResult collectCatalog(JdbcTemplate targetJdbc, String orgCd, String triggeredBy) throws Exception {
        JsonNode res = krasApiClient.layerList();
        List<String> layerCodes = new ArrayList<>();
        for (JsonNode layer : res.path("layers")) {
            String name = layer.path("layerName").asText("");
            if (!name.isBlank() && isUsezoneLayer(name)) layerCodes.add(shortCode(name));
        }
        if (layerCodes.isEmpty()) {
            throw new IllegalStateException("KRAS 레이어 목록에서 USEZONE 레이어를 찾지 못했습니다.");
        }
        return targetJdbc.execute((ConnectionCallback<CatalogResult>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                CatalogResult result = collectCatalogInTransaction(tx, orgCd, layerCodes, triggeredBy);
                conn.commit();
                log.info("[KrasUsezoneIngest] 카탈로그 수집 완료 itemId={} layers={}",
                        result.itemId(), result.layerCodes().size());
                return result;
            } catch (Exception e) {
                conn.rollback();
                log.error("[KrasUsezoneIngest] 카탈로그 수집 실패, 롤백: {}", e.getMessage(), e);
                throw new IllegalStateException("카탈로그 수집 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    private CatalogResult collectCatalogInTransaction(JdbcTemplate tx, String orgCd, List<String> layerCodes,
                                                        String triggeredBy) {
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
            """, Long.class, runId, orgCd, CATALOG_DATASET, today, tomorrow);

        int recordNo = 1;
        for (String layerCode : layerCodes) {
            String payload = writeJson(Map.of("dataset_code", LAYER_DATASET, "layer_code", layerCode));
            tx.update("""
                INSERT INTO kras.sync_record(item_id, record_no, payload, payload_hash, parser_version, validation_status)
                VALUES (?, ?, ?::jsonb, encode(sha256(convert_to(?,'UTF8')),'hex')::kras.sha256, '1', 'VALID')
                """, itemId, recordNo, payload, payload);
            recordNo++;
        }

        int n = layerCodes.size();
        tx.update("""
            UPDATE kras.sync_item SET status='SUCCESS', rows_received=?, rows_valid=?, rows_rejected=0, is_complete=true
            WHERE item_id=?
            """, n, n, itemId);

        return new CatalogResult(itemId, layerCodes);
    }

    // ── 1~4. 레이어 순회 ──────────────────────────────────────────────

    public SweepResult runLayerSweep(JdbcTemplate targetJdbc, String orgCd, long catalogItemId,
                                      String triggeredBy) throws Exception {
        List<String> expectedCodes = targetJdbc.query("""
            SELECT payload->>'layer_code' FROM kras.sync_record
            WHERE item_id=? AND payload->>'dataset_code'=?
            ORDER BY record_no
            """, (rs, i) -> rs.getString(1), catalogItemId, LAYER_DATASET);
        if (expectedCodes.isEmpty()) {
            throw new IllegalStateException("catalog item_id=" + catalogItemId + "에 등록된 레이어가 없습니다.");
        }

        long releaseId = ensureRelease(targetJdbc, orgCd, catalogItemId, expectedCodes);

        Set<String> alreadyDone = new LinkedHashSet<>(targetJdbc.query(
                "SELECT layer_code FROM kras.spatial_release_member WHERE release_id=?",
                (rs, i) -> rs.getString(1), releaseId));

        JsonNode liveLayers = krasApiClient.layerList();
        Map<String, String> fullNameByCode = new LinkedHashMap<>();
        for (JsonNode layer : liveLayers.path("layers")) {
            String name = layer.path("layerName").asText("");
            if (!name.isBlank() && isUsezoneLayer(name)) fullNameByCode.put(shortCode(name), name);
        }

        SyncTableDef def = findUsezoneDef();
        Path workDir = Path.of(settings.krasWorkDir(), settings.orgCode());

        List<LayerResult> results = new ArrayList<>();
        for (String layerCode : expectedCodes) {
            if (alreadyDone.contains(layerCode)) {
                results.add(new LayerResult(layerCode, true, "이미 완료(재사용)"));
                continue;
            }
            String fullName = fullNameByCode.get(layerCode);
            if (fullName == null) {
                results.add(new LayerResult(layerCode, false,
                        "현재 KRAS 레이어 목록에 없음(카탈로그 수집 이후 변경된 것으로 보임)"));
                continue;
            }
            try {
                String baseName = krasApiClient.downloadLayer(fullName, workDir);
                Path shpPath = workDir.resolve(baseName + ".shp");
                List<Map<String, Object>> rows = workspaceScanner.loadShpFile(shpPath, def);
                if (rows.isEmpty()) {
                    throw new IllegalStateException("SHP에서 읽은 행이 0건입니다.");
                }
                deriveThemeFields(rows);
                ingestLayer(targetJdbc, orgCd, releaseId, layerCode, fullName, rows, triggeredBy);
                results.add(new LayerResult(layerCode, true, rows.size() + "건 적재"));
            } catch (Exception e) {
                log.error("[KrasUsezoneIngest] 레이어 {} 처리 실패: {}", layerCode, e.getMessage(), e);
                results.add(new LayerResult(layerCode, false, e.getMessage()));
            }
        }
        return new SweepResult(releaseId, results);
    }

    /** 조직+카탈로그에 대해 아직 PUBLISHED 안 된 release가 있으면 재사용, 없으면 새로 만들고 seal까지 진행. */
    private long ensureRelease(JdbcTemplate targetJdbc, String orgCd, long catalogItemId, List<String> expectedCodes) {
        return targetJdbc.execute((ConnectionCallback<Long>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                List<Map<String, Object>> existing = tx.queryForList("""
                    SELECT release_id, status FROM kras.spatial_release
                    WHERE org_cd=? AND catalog_item_id=? AND status<>'PUBLISHED'
                    ORDER BY release_id DESC LIMIT 1
                    """, orgCd, catalogItemId);
                long releaseId;
                String status;
                if (existing.isEmpty()) {
                    releaseId = tx.queryForObject("""
                        INSERT INTO kras.spatial_release(org_cd, catalog_item_id) VALUES (?, ?)
                        RETURNING release_id
                        """, Long.class, orgCd, catalogItemId);
                    status = "DRAFT";
                } else {
                    releaseId = ((Number) existing.get(0).get("release_id")).longValue();
                    status = (String) existing.get(0).get("status");
                }
                if ("DRAFT".equals(status)) {
                    for (String layerCode : expectedCodes) {
                        tx.update("""
                            INSERT INTO kras.spatial_release_expected(release_id, layer_code) VALUES (?, ?)
                            ON CONFLICT DO NOTHING
                            """, releaseId, layerCode);
                    }
                    tx.execute("SELECT kras.seal_spatial_release(" + releaseId + ")");
                }
                conn.commit();
                return releaseId;
            } catch (Exception e) {
                conn.rollback();
                throw new IllegalStateException("release 준비 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    private void ingestLayer(JdbcTemplate targetJdbc, String orgCd, long releaseId, String layerCode,
                              String layerName, List<Map<String, Object>> rows, String triggeredBy) {
        targetJdbc.execute((ConnectionCallback<Void>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                ingestLayerInTransaction(tx, orgCd, releaseId, layerCode, layerName, rows, triggeredBy);
                conn.commit();
                return null;
            } catch (Exception e) {
                conn.rollback();
                throw new IllegalStateException(layerCode + " 적재 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    private void ingestLayerInTransaction(JdbcTemplate tx, String orgCd, long releaseId, String layerCode,
                                           String layerName, List<Map<String, Object>> rows, String triggeredBy) {
        tx.update("""
            INSERT INTO kras.spatial_layer(layer_code, dataset_code, layer_name, source_epsg, target_epsg)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (layer_code) DO NOTHING
            """, layerCode, LAYER_DATASET, layerName, SOURCE_EPSG, STORAGE_EPSG);

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        Long runId = tx.queryForObject("""
            INSERT INTO kras.sync_run(org_cd, job_kind, triggered_by, period_start, period_end_exclusive)
            VALUES (?, 'MANUAL', ?, ?, ?)
            RETURNING run_id
            """, Long.class, orgCd, triggeredBy, today, tomorrow);

        Long itemId = tx.queryForObject("""
            INSERT INTO kras.sync_item(run_id, org_cd, dataset_code, scope_key, window_start, window_end_exclusive)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING item_id
            """, Long.class, runId, orgCd, LAYER_DATASET, "LAYER:" + layerCode, today, tomorrow);

        List<Object[]> featureParams = new ArrayList<>(rows.size());
        List<Object[]> usezoneParams = new ArrayList<>(rows.size());
        int featureNo = 1;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            String wkt = (String) row.get("geometry");
            if (wkt == null || wkt.isBlank()) { skipped++; continue; }
            featureParams.add(new Object[]{ itemId, featureNo, orgCd, layerCode, wkt });
            usezoneParams.add(new Object[]{ row.get("mnum"), row.get("remark"), row.get("alias"),
                    row.get("ucode"), row.get("uname"), itemId, featureNo });
            featureNo++;
        }
        if (featureParams.isEmpty()) {
            throw new IllegalStateException(
                    "유효한 도형이 있는 행이 0건입니다(전체 " + rows.size() + "건 중 " + skipped + "건 도형 없음).");
        }
        if (skipped > 0) {
            log.warn("[KrasUsezoneIngest] {} 도형 없는 행 {}건 건너뜀 (item_id={})", layerCode, skipped, itemId);
        }

        tx.batchUpdate("""
            INSERT INTO kras.spatial_feature(item_id, feature_no, org_cd, layer_code, geom)
            VALUES (?, ?, ?, ?, ST_Transform(ST_GeomFromText(?, %d), %d))
            """.formatted(SOURCE_EPSG, STORAGE_EPSG), featureParams);

        // usezone_feature는 spatial_feature에서 geom/pnu를 SELECT로 그대로 가져온다 — WKT 재파싱 없음
        // (guard_geometry가 ST_Equals로 정확히 같은지 검사하므로 재파싱 오차를 원천 차단한다).
        tx.batchUpdate("""
            INSERT INTO kras.usezone_feature(item_id, feature_no, org_cd, layer_code, pnu, mnum, remark, alias,
                theme_code, theme_name, geom)
            SELECT item_id, feature_no, org_cd, layer_code, pnu, ?, ?, ?, ?, ?, geom
            FROM kras.spatial_feature WHERE item_id = ? AND feature_no = ?
            """, usezoneParams);

        int n = featureParams.size();
        tx.update("""
            UPDATE kras.sync_item SET status='SUCCESS', rows_received=?, rows_valid=?, rows_rejected=0, is_complete=true
            WHERE item_id=?
            """, n, n, itemId);

        tx.update("""
            INSERT INTO kras.spatial_release_member(release_id, org_cd, layer_code, item_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (release_id, layer_code) DO UPDATE SET item_id=EXCLUDED.item_id
            """, releaseId, orgCd, layerCode, itemId);
    }

    // ── 5. release 발행 ──────────────────────────────────────────────

    public void publishRelease(JdbcTemplate targetJdbc, long releaseId) {
        targetJdbc.execute((ConnectionCallback<Void>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                tx.execute("SELECT kras.publish_spatial_release(" + releaseId + ")");
                conn.commit();
                return null;
            } catch (Exception e) {
                conn.rollback();
                log.error("[KrasUsezoneIngest] release 발행 실패, 롤백: {}", e.getMessage(), e);
                throw new IllegalStateException("release 발행 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    // ── 6. public 승격 ──────────────────────────────────────────────

    /** 기존 kras.sync_public_usezone() 재사용 — public.lt_c_uzone을 여기서만 건드린다. */
    public long syncPublic(JdbcTemplate targetJdbc) {
        return targetJdbc.execute((ConnectionCallback<Long>) conn -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                Long n = tx.queryForObject("SELECT kras.sync_public_usezone()", Long.class);
                conn.commit();
                return n != null ? n : 0L;
            } catch (Exception e) {
                conn.rollback();
                log.error("[KrasUsezoneIngest] public 승격 실패, 롤백: {}", e.getMessage(), e);
                throw new IllegalStateException("public.lt_c_uzone 승격 실패: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        });
    }

    // ── 공통 헬퍼 ──────────────────────────────────────────────

    /** USEZONE 레이어 패턴: LSMD_CONT_U{코드} (예: LSMD_CONT_UB201) — KrasWorker/KrasFileDownloadService와 동일 규칙. */
    private static boolean isUsezoneLayer(String layerName) {
        int idx = layerName.indexOf("LSMD_CONT_U");
        if (idx < 0) return false;
        int codeStart = idx + "LSMD_CONT_U".length();
        return codeStart < layerName.length() && Character.isLetter(layerName.charAt(codeStart));
    }

    /** "LSMD_CONT_UB201" → "UB201" (마지막 '_' 뒤 부분) — KrasWorker.injectUsezoneCode와 동일 규칙. */
    private static String shortCode(String layerName) {
        int idx = layerName.lastIndexOf('_');
        return idx >= 0 ? layerName.substring(idx + 1).toUpperCase() : layerName.toUpperCase();
    }

    /**
     * ucode(theme_code)/uname(theme_name)는 SHP 속성에 없다 — mnum 21~26번째 자리에서 파생하고
     * mt_usezone_cd 코드 테이블(UsezoneCodeService)에서 이름을 조회한다. KrasWorker.deriveUsezoneFields와
     * 같은 규칙(구 시스템 호환) — 짧은 mnum이면 파생하지 않고 그대로 둔다(어차피 null).
     */
    private void deriveThemeFields(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            String mnum = (String) row.get("mnum");
            if (mnum == null || mnum.length() < 26) continue;
            String code = mnum.substring(20, 26);
            row.put("ucode", code);
            String name = usezoneCodeService.getName(code);
            if (name != null) row.put("uname", name);
        }
    }

    private SyncTableDef findUsezoneDef() {
        return tableMapper.load(settings.krasConfig()).stream()
                .filter(d -> TARGET_TABLE.equals(d.tgtTableName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("base-tables.xml에 " + TARGET_TABLE + " 정의가 없습니다."));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("jsonb 직렬화 실패: " + e.getMessage(), e);
        }
    }
}
