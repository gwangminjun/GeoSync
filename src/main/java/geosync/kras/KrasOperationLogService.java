package geosync.kras;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
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
            operationId = startOperation(jdbc, orgCd, datasetCode, action, itemId, requestSummary);
        } catch (Exception e) {
            log.error("[KrasOperation] 실행 기록 시작 실패", e);
            return Map.of("success", false, "message", "실행 이력을 저장할 수 없어 작업을 시작하지 않았습니다. DB 연결과 kras 스키마 쓰기 권한을 확인하세요.");
        }
        Map<String, Object> result = runAndCollect(datasetCode, action, operationId, operation);
        return finishOperation(jdbc, orgCd, operationId, itemId, result);
    }

    /**
     * 실행 기록만 동기로 남기고(호출자가 바로 operationId를 받아야 하므로) 실제 작업은
     * {@link #runAsync}로 백그라운드에 넘긴다 — 오래 걸리는 작업(전체 TXT 수집, 용도지역 레이어 순회)이
     * HTTP 요청을 붙잡지 않게 하기 위함. 진행 상태는 operationId로 폴링한다(`/kras-db/operations/{id}`).
     */
    public Long startOperation(JdbcTemplate jdbc, String orgCd, String datasetCode, String action,
                                Long itemId, String requestSummary) {
        ensureTable(jdbc);
        String summary = requestSummary == null ? "" : requestSummary;
        if (summary.length() > 300) summary = summary.substring(0, 300);
        Long operationId = jdbc.queryForObject("""
            INSERT INTO kras.ui_operation_log(org_cd, dataset_code, action, item_id, request_summary)
            VALUES (?, ?, ?, ?, ?) RETURNING operation_id
            """, Long.class, orgCd, datasetCode, action, itemId, summary);
        if (operationId == null) throw new IllegalStateException("실행 ID를 받지 못했습니다.");
        return operationId;
    }

    /**
     * startOperation()이 이미 만든 RUNNING 행을 백그라운드 스레드에서 마무리한다.
     * 이 메서드는 반드시 컨트롤러(외부 빈)에서 호출해야 @Async 프록시가 적용된다 — 같은 클래스 안에서
     * this.runAsync(...)로 호출하면 프록시를 안 거쳐 그냥 동기 실행된다(Spring AOP 자기호출 제약).
     */
    @Async
    public void runAsync(JdbcTemplate jdbc, String orgCd, String datasetCode, String action, Long operationId,
                          Long itemId, Callable<Map<String, Object>> operation, Runnable onComplete) {
        try {
            Map<String, Object> result = runAndCollect(datasetCode, action, operationId, operation);
            finishOperation(jdbc, orgCd, operationId, itemId, result);
        } finally {
            onComplete.run();
        }
    }

    private Map<String, Object> runAndCollect(String datasetCode, String action, Long operationId,
                                               Callable<Map<String, Object>> operation) {
        try {
            return new LinkedHashMap<>(operation.call());
        } catch (Exception e) {
            log.error("[KrasOperation] {} {} 실패 (operationId={})", datasetCode, action, operationId, e);
            return new LinkedHashMap<>(Map.of("success", false,
                    "message", e.getMessage() == null ? "작업 처리 실패" : e.getMessage()));
        }
    }

    private Map<String, Object> finishOperation(JdbcTemplate jdbc, String orgCd, Long operationId,
                                                 Long fallbackItemId, Map<String, Object> result) {
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
                """, status, result.getOrDefault("itemId", fallbackItemId), result.get("releaseId"),
                    message, details, operationId, orgCd);
        } catch (Exception e) {
            log.error("[KrasOperation] 종료 기록 실패 operationId={}", operationId, e);
            result.put("auditWarning", "작업 결과의 이력 저장에 실패했습니다. 중복 실행 전에 실제 반영 상태를 확인하세요.");
        }
        return result;
    }

    private void ensureTable(JdbcTemplate jdbc) {
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
