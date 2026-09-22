package geosync.kras;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** UI 실행 기록은 수집 서비스의 트랜잭션 밖에서 저장한다. */
@Service
public class KrasOperationLogService {
    private static final Logger log = LoggerFactory.getLogger(KrasOperationLogService.class);
    private final ObjectMapper json = new ObjectMapper();

    public Map<String, Object> execute(JdbcTemplate jdbc, String orgCd, String datasetCode,
                                      String action, Long itemId, Callable<Map<String, Object>> operation) {
        return execute(jdbc, orgCd, datasetCode, action, itemId, "", operation);
    }

    public Map<String, Object> execute(JdbcTemplate jdbc, String orgCd, String datasetCode,
                                      String action, Long itemId, String requestSummary,
                                      Callable<Map<String, Object>> operation) {
        Long operationId;
        try {
            if (jdbc.queryForObject("SELECT to_regclass('kras.ui_operation_log')::text", String.class) == null) {
                jdbc.execute("""
                CREATE TABLE IF NOT EXISTS kras.ui_operation_log (
                    operation_id bigserial PRIMARY KEY,
                    org_cd varchar(5) NOT NULL,
                    dataset_code varchar(80) NOT NULL,
                    action varchar(30) NOT NULL,
                    status varchar(16) NOT NULL DEFAULT 'RUNNING',
                    item_id bigint,
                    release_id bigint,
                    started_at timestamptz NOT NULL DEFAULT now(),
                    ended_at timestamptz,
                    request_summary varchar(300) NOT NULL DEFAULT '',
                    message text,
                    details jsonb NOT NULL DEFAULT '{}'
                )
                """);
            }
            String summary = requestSummary == null ? "" : requestSummary;
            if (summary.length() > 300) summary = summary.substring(0, 300);
            operationId = jdbc.queryForObject("""
                INSERT INTO kras.ui_operation_log(org_cd, dataset_code, action, item_id, request_summary)
                VALUES (?, ?, ?, ?, ?) RETURNING operation_id
                """, Long.class, orgCd, datasetCode, action, itemId, summary);
            if (operationId == null) throw new IllegalStateException("실행 ID를 받지 못했습니다.");
        } catch (Exception e) {
            log.error("[KrasOperation] 실행 기록 시작 실패", e);
            return Map.of("success", false, "message", "실행 이력을 저장할 수 없어 작업을 시작하지 않았습니다. DB 연결과 kras 스키마 쓰기 권한을 확인하세요.");
        }

        Map<String, Object> result;
        try {
            result = new LinkedHashMap<>(operation.call());
        } catch (Exception e) {
            log.error("[KrasOperation] {} {} 실패", datasetCode, action, e);
            result = new LinkedHashMap<>(Map.of("success", false,
                    "message", e.getMessage() == null ? "작업 처리 실패" : e.getMessage()));
        }
        String status = outcome(result);
        result.put("outcome", status);
        result.put("operationId", operationId);
        try {
            // 원문 미리보기나 요청 파라미터는 실행 로그에 복제하지 않는다.
            String details = result.containsKey("layers")
                    ? json.writeValueAsString(Map.of("layers", result.get("layers"))) : "{}";
            String message = String.valueOf(result.getOrDefault("message", result.getOrDefault("error", status)));
            if (message.length() > 4000) message = message.substring(0, 4000);
            jdbc.update("""
                UPDATE kras.ui_operation_log SET status=?, item_id=?, release_id=?,
                    ended_at=now(), message=?, details=?::jsonb
                WHERE operation_id=? AND org_cd=?
                """, status, result.getOrDefault("itemId", itemId), result.get("releaseId"),
                    message, details, operationId, orgCd);
        } catch (Exception e) {
            log.error("[KrasOperation] 종료 기록 실패 operationId={}", operationId, e);
            result.put("auditWarning", "작업 결과의 이력 저장에 실패했습니다. 중복 실행 전에 실제 반영 상태를 확인하세요.");
        }
        return result;
    }

    static String outcome(Map<String, Object> result) {
        if (!Boolean.TRUE.equals(result.get("success"))) return "FAILED";
        if (result.get("layers") instanceof List<?> layers) {
            long successful = layers.stream()
                    .filter(layer -> layer instanceof KrasUsezoneIngestService.LayerResult r && r.success()).count();
            if (successful < layers.size()) return successful == 0 ? "FAILED" : "PARTIAL";
        }
        return Boolean.FALSE.equals(result.get("promotable")) ? "WARNING" : "SUCCESS";
    }
}
