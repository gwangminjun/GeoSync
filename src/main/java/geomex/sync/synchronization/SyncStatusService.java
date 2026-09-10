package geomex.sync.synchronization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import geomex.sync.synchronization.model.SyncHistory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class SyncStatusService {

    private static final int MAX_HISTORY = 100;
    private static final DateTimeFormatter ERROR_FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    // RUNNING 항목은 별도 추적 (타입별 1개)
    private final java.util.Map<String, SyncHistory> running = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, SyncProgress> progress = new java.util.concurrent.ConcurrentHashMap<>();
    private final Deque<SyncHistory> history = new ConcurrentLinkedDeque<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${sync.history-file:./logs/sync-history.json}")
    private String historyFile;

    @Value("${sync.error-dir:./logs}")
    private String errorDir;

    @PostConstruct
    public void loadHistory() {
        Path path = Path.of(historyFile).normalize();
        if (!Files.isRegularFile(path)) return;
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                    Files.readString(path, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            for (Map<String, Object> row : rows) {
                SyncHistory item = fromStored(row);
                if (item != null && !item.isRunning()) {
                    history.addLast(item);
                }
            }
            while (history.size() > MAX_HISTORY) history.removeLast();
        } catch (Exception ignored) {
            history.clear();
        }
    }

    public void recordStart(String type) {
        SyncHistory h = new SyncHistory(type, LocalDateTime.now(), null, 0, 0, "RUNNING");
        running.put(type, h);
        progress.put(type, new SyncProgress(0, 0, ""));
    }

    public void recordEnd(String type, int success, int error, boolean failed) {
        SyncHistory started = running.remove(type);
        progress.remove(type);
        LocalDateTime start = started != null ? started.startTime() : LocalDateTime.now();
        String status = failed ? "FAILED" : "SUCCESS";
        SyncHistory done = new SyncHistory(type, start, LocalDateTime.now(), success, error, status);

        history.addFirst(done);
        // 최대 100건 유지
        while (history.size() > MAX_HISTORY) history.removeLast();
        persistHistory();
        if (failed) {
            writeFailureNotice(done);
        }
    }

    public boolean isRunning(String type) {
        return running.containsKey(type);
    }

    public java.time.LocalDateTime getRunningStartTime(String type) {
        SyncHistory h = running.get(type);
        return h != null ? h.startTime() : null;
    }

    public void startProgress(String type, int total, String current) {
        progress.put(type, new SyncProgress(Math.max(total, 0), 0, current));
    }

    public void updateProgress(String type, int completed, int total, String current) {
        int safeTotal = Math.max(total, 0);
        int safeCompleted = Math.max(0, Math.min(completed, safeTotal));
        progress.put(type, new SyncProgress(safeTotal, safeCompleted, current));
    }

    public void updateRowProgress(String type, int completed, int total, String current) {
        SyncProgress existing = getProgress(type);
        int safeTotal = Math.max(total, 0);
        int safeCompleted = Math.max(0, Math.min(completed, safeTotal));
        progress.put(type, existing.withRowProgress(safeCompleted, safeTotal, current));
    }

    public SyncProgress getProgress(String type) {
        return progress.getOrDefault(type, new SyncProgress(0, 0, ""));
    }

    public List<SyncHistory> getRecentHistory(int limit) {
        Deque<SyncHistory> combined = new ArrayDeque<>();
        running.values().forEach(combined::addFirst); // 실행 중인 항목 상단에 표시
        history.stream().limit(limit).forEach(combined::addLast);
        return combined.stream().limit(limit).toList();
    }

    public Optional<SyncHistory> getLastRun(String type) {
        if (running.containsKey(type)) return Optional.of(running.get(type));
        return history.stream().filter(h -> h.type().equals(type)).findFirst();
    }

    public Optional<SyncHistory> getLastSuccess(String type) {
        return history.stream()
                .filter(h -> h.type().equals(type))
                .filter(h -> "SUCCESS".equals(h.status()))
                .findFirst();
    }

    public int todaySuccessCount() {
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        return history.stream()
                .filter(h -> h.startTime() != null && h.startTime().isAfter(todayStart))
                .mapToInt(SyncHistory::successCount)
                .sum();
    }

    public int todayErrorCount() {
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        return history.stream()
                .filter(h -> h.startTime() != null && h.startTime().isAfter(todayStart))
                .mapToInt(SyncHistory::errorCount)
                .sum();
    }

    public record SyncProgress(int total, int completed, String current,
                               int rowTotal, int rowCompleted, String rowCurrent) {
        public SyncProgress(int total, int completed, String current) {
            this(total, completed, current, 0, 0, "");
        }

        public int percent() {
            if (total <= 0) return 0;
            return Math.max(0, Math.min(100, (int) Math.floor((completed * 100.0) / total)));
        }

        public int rowPercent() {
            if (rowTotal <= 0) return 0;
            return Math.max(0, Math.min(100, (int) Math.floor((rowCompleted * 100.0) / rowTotal)));
        }

        public SyncProgress withRowProgress(int rowCompleted, int rowTotal, String rowCurrent) {
            return new SyncProgress(total, completed, current, rowTotal, rowCompleted, rowCurrent);
        }
    }

    private void persistHistory() {
        Path path = Path.of(historyFile).normalize();
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<Map<String, Object>> rows = history.stream()
                    .limit(MAX_HISTORY)
                    .map(this::toStored)
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows);
        } catch (Exception ignored) {
            // 이력 저장 실패는 동기화 본 작업에 영향을 주지 않는다.
        }
    }

    private void writeFailureNotice(SyncHistory done) {
        try {
            Path dir = Path.of(errorDir).normalize();
            Files.createDirectories(dir);
            String date = LocalDateTime.now().format(ERROR_FILE_DATE);
            Path file = dir.resolve("sync_error_" + date + ".log");
            String line = "%s type=%s status=%s success=%d error=%d%n".formatted(
                    LocalDateTime.now(), done.type(), done.status(), done.successCount(), done.errorCount());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 실패 알림 기록 실패도 본 작업 상태를 바꾸지 않는다.
        }
    }

    private Map<String, Object> toStored(SyncHistory history) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", history.type());
        row.put("startTime", history.startTime() != null ? history.startTime().toString() : null);
        row.put("endTime", history.endTime() != null ? history.endTime().toString() : null);
        row.put("successCount", history.successCount());
        row.put("errorCount", history.errorCount());
        row.put("status", history.status());
        return row;
    }

    private SyncHistory fromStored(Map<String, Object> row) {
        try {
            String type = stringValue(row.get("type"));
            String status = stringValue(row.get("status"));
            if (type == null || status == null) return null;
            return new SyncHistory(
                    type,
                    parseTime(row.get("startTime")),
                    parseTime(row.get("endTime")),
                    intValue(row.get("successCount")),
                    intValue(row.get("errorCount")),
                    status);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseTime(Object value) {
        String text = stringValue(value);
        return text != null ? LocalDateTime.parse(text) : null;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        return value != null ? Integer.parseInt(String.valueOf(value)) : 0;
    }
}
