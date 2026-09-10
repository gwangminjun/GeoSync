package geomex.sync.synchronization.model;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public record SyncHistory(
        String type,             // "KRAS" | "KAIS"
        LocalDateTime startTime,
        LocalDateTime endTime,   // null = 실행 중
        int successCount,
        int errorCount,
        String status            // "RUNNING" | "SUCCESS" | "FAILED"
) {
    public boolean isRunning() {
        return "RUNNING".equals(status);
    }

    public String elapsedSeconds() {
        if (endTime == null || startTime == null) return "-";
        long sec = ChronoUnit.SECONDS.between(startTime, endTime);
        return sec + "초";
    }
}
